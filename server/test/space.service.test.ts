import { randomUUID } from 'node:crypto';
import { describe, expect, it } from 'vitest';
import { AppError } from '../src/lib/errors';
import type { DeviceRecord, MemberRoleValue } from '../src/repositories/ports';
import {
  normalizeRemotePath,
  SpaceService,
} from '../src/services/space.service';
import { FakeDeviceRepository, FakeObjectStore, FakeSpaceRepository } from './fakes';

const NOW = new Date('2026-01-02T03:04:05.000Z');
const HASH_A = 'a'.repeat(64);
const HASH_B = 'b'.repeat(64);

function makeService() {
  const devices = new FakeDeviceRepository();
  const spaces = new FakeSpaceRepository(devices);
  const objectStore = new FakeObjectStore();
  const service = new SpaceService({ devices, spaces, objectStore, now: () => NOW });
  return { service, devices, spaces, objectStore };
}

async function addDevice(
  devices: FakeDeviceRepository,
  userId: string,
  credentialStatus: DeviceRecord['credentialStatus'] = 'active',
): Promise<DeviceRecord> {
  const device = await devices.create({
    userId,
    deviceName: `${userId} device`,
    platform: 'android',
    credentialStatus,
    lastSeenAt: NOW,
  });
  // 账号下第一台设备作为主设备（创建空间需要主设备身份）
  await devices.claimPrimaryDevice(userId, device.id);
  return device;
}

function seedMembership(
  spaces: FakeSpaceRepository,
  spaceId: string,
  deviceId: string,
  role: MemberRoleValue,
): void {
  spaces.members.set(`${spaceId}:${deviceId}`, {
    id: randomUUID(),
    spaceId,
    deviceId,
    role,
    joinedAt: NOW,
  });
}

function seedFile(
  spaces: FakeSpaceRepository,
  spaceId: string,
  relativePath: string,
  sha256: string,
  isDeleted = false,
): string {
  const id = randomUUID();
  const objectId = randomUUID();
  spaces.files.set(id, {
    id,
    spaceId,
    relativePath,
    fileName: relativePath.split('/').at(-1)!,
    objectId,
    size: 123n,
    sha256,
    version: 2,
    isDeleted,
    createdAt: NOW,
    updatedAt: NOW,
    lastModifiedByDeviceId: randomUUID(),
  });
  return objectId;
}

async function expectAppError(fn: () => Promise<unknown>, statusCode: number, code: string) {
  try {
    await fn();
    expect.unreachable('expected AppError to be thrown');
  } catch (err) {
    expect(err).toBeInstanceOf(AppError);
    expect((err as AppError).statusCode).toBe(statusCode);
    expect((err as AppError).code).toBe(code);
  }
}

async function setupSpace(userId = 'user-1') {
  const context = makeService();
  const device = await addDevice(context.devices, userId);
  const space = await context.service.createSpace(userId, { name: 'Photos', deviceId: device.id });
  return { ...context, device, space, userId };
}

describe('SpaceService 空间创建、列表与详情', () => {
  it('创建空间时同时写入 member 成员与零游标，并序列化 revision', async () => {
    const { service, devices, spaces } = makeService();
    const device = await addDevice(devices, 'user-1');

    const space = await service.createSpace('user-1', {
      name: '  Photos  ',
      description: 'Family',
      deviceId: device.id,
    });

    expect(space).toMatchObject({
      ownerId: 'user-1',
      name: 'Photos',
      description: 'Family',
      revision: '0',
      role: 'admin', // 主设备一律视为 admin
      memberCount: 1,
      mappingCount: 0,
    });
    expect(spaces.spaces.get(space.id)?.revision).toBe(0n);
    expect(spaces.members.get(`${space.id}:${device.id}`)?.role).toBe('member');
    expect(spaces.cursors.get(`${space.id}:${device.id}`)?.lastAppliedRevision).toBe(0n);
  });

  it('创建空间不会自动加入同账号其他设备，其他设备以 none 角色可见并需申请审批', async () => {
    const { service, devices, spaces } = makeService();
    const primary = await addDevice(devices, 'user-1');
    const secondary = await devices.create({
      userId: 'user-1',
      deviceName: 'Tablet',
      platform: 'android',
      credentialStatus: 'active',
      lastSeenAt: NOW,
    });

    const space = await service.createSpace('user-1', { name: 'Shared', deviceId: primary.id });

    expect(space.memberCount).toBe(1);
    expect(await spaces.findMember(space.id, secondary.id)).toBeNull();
    expect((await service.listSpaces('user-1', secondary.id)).find((item) => item.id === space.id)?.role)
      .toBe('none');

    const applied = await service.requestToJoinSpace('user-1', space.id, secondary.id);
    expect(applied.joined).toBe(false);
    expect(applied.request).toMatchObject({ deviceId: secondary.id, status: 'pending' });
  });

  it('拒绝他人设备和已吊销设备', async () => {
    const { service, devices } = makeService();
    const other = await addDevice(devices, 'user-2');
    const revoked = await addDevice(devices, 'user-1', 'revoked');

    await expectAppError(
      () => service.createSpace('user-1', { name: 'X', deviceId: other.id }),
      400,
      'INVALID_DEVICE',
    );
    await expectAppError(
      () => service.createSpace('user-1', { name: 'X', deviceId: revoked.id }),
      400,
      'INVALID_DEVICE',
    );
  });

  it('只有主设备可以创建空间', async () => {
    const { service, devices } = makeService();
    const primary = await addDevice(devices, 'user-1');
    const secondary = await addDevice(devices, 'user-1');

    await expectAppError(
      () => service.createSpace('user-1', { name: 'X', deviceId: secondary.id }),
      403,
      'DEVICE_NOT_PRIMARY',
    );

    const space = await service.createSpace('user-1', { name: 'Y', deviceId: primary.id });
    expect(space.ownerId).toBe('user-1');
  });

  it('列表合并拥有与设备加入的空间，并取多设备最高角色', async () => {
    const { service, devices, spaces } = makeService();
    const ownerDevice = await addDevice(devices, 'user-1');
    const otherOwnerDevice = await addDevice(devices, 'user-2');
    const viewerDevice = await addDevice(devices, 'user-1');
    const adminDevice = await addDevice(devices, 'user-1');
    const owned = await service.createSpace('user-1', {
      name: 'Owned',
      deviceId: ownerDevice.id,
    });
    const joined = await service.createSpace('user-2', {
      name: 'Joined',
      deviceId: otherOwnerDevice.id,
    });
    seedMembership(spaces, joined.id, viewerDevice.id, 'viewer');
    seedMembership(spaces, joined.id, adminDevice.id, 'admin');

    const list = await service.listSpaces('user-1');

    expect(list).toHaveLength(2);
    expect(list.find((space) => space.id === owned.id)?.role).toBe('admin'); // 主设备 = admin
    expect(list.find((space) => space.id === joined.id)?.role).toBe('admin');
  });

  it('详情返回成员在线状态；非成员 403，未知空间 404', async () => {
    const { service, devices, spaces, device, space, userId } = await setupSpace();
    const offline = await addDevice(devices, userId);
    offline.lastSeenAt = new Date(NOW.getTime() - 6 * 60 * 1000);
    device.lastSeenAt = new Date(NOW.getTime() - 4 * 60 * 1000);
    await service.addSpaceMember(userId, space.id, offline.id);

    const detail = await service.getSpace(userId, space.id);
    expect(detail.members).toHaveLength(2);
    expect(detail.members.find((member) => member.deviceId === device.id)?.online).toBe(true);
    expect(detail.members.find((member) => member.deviceId === offline.id)?.online).toBe(false);
    // 主设备在成员列表中必须显示为管理员，其余为成员
    expect(detail.members.find((member) => member.deviceId === device.id)?.role).toBe('admin');
    expect(detail.members.find((member) => member.deviceId === offline.id)?.role).toBe('member');
    expect(spaces.members.size).toBe(2);

    await expectAppError(() => service.getSpace('stranger', space.id), 403, 'SPACE_FORBIDDEN');
    await expectAppError(() => service.getSpace(userId, randomUUID()), 404, 'SPACE_NOT_FOUND');
  });
});

describe('SpaceService 空间修改与删除权限', () => {
  it('主设备可改名和删除自己账号下的任意空间', async () => {
    const { service, devices, spaces, device, space, userId } = await setupSpace();
    // 再建一个从设备加入空间
    const secondary = await addDevice(devices, userId);
    seedMembership(spaces, space.id, secondary.id, 'member');

    const updated = await service.updateSpace(userId, device.id, space.id, {
      name: '  New name ',
      description: 'updated',
    });
    expect(updated.name).toBe('New name');
    expect(updated.description).toBe('updated');

    await service.deleteSpace(userId, device.id, space.id);
    expect(spaces.spaces.has(space.id)).toBe(false);
  });

  it('删除空间时清理对象存储，避免历史文件长期占用磁盘', async () => {
    const { service, spaces, objectStore, device, space, userId } = await setupSpace();
    const objectId = seedFile(spaces, space.id, 'photos/a.jpg', HASH_A);
    objectStore.objects.set(objectId, Buffer.from('content'));

    await service.deleteSpace(userId, device.id, space.id);

    expect(spaces.spaces.has(space.id)).toBe(false);
    expect(objectStore.objects.has(objectId)).toBe(false);
  });

  it('从设备（非管理员角色）不能改名也不能删除空间', async () => {
    const { service, devices, spaces, device, space, userId } = await setupSpace();
    const secondary = await addDevice(devices, userId);
    seedMembership(spaces, space.id, secondary.id, 'member');
    spaces.members.get(`${space.id}:${device.id}`)!.role = 'member';

    // 管理员判定基于成员角色：member 角色即使从设备也不允许改名（SPACE_FORBIDDEN）
    await expectAppError(
      () => service.updateSpace(userId, secondary.id, space.id, { name: 'Forbidden' }),
      403,
      'SPACE_FORBIDDEN',
    );
    await expectAppError(
      () => service.deleteSpace(userId, secondary.id, space.id),
      403,
      'DEVICE_NOT_PRIMARY',
    );
  });
});

describe('SpaceService 成员管理', () => {
  it('重复加入幂等返回同一成员，且不会重置已有游标', async () => {
    const { service, devices, spaces, space, userId } = await setupSpace();
    const second = await addDevice(devices, userId);

    const first = await service.addSpaceMember(userId, space.id, second.id);
    expect(first.created).toBe(true);
    expect(first.member.role).toBe('member');
    spaces.cursors.get(`${space.id}:${second.id}`)!.lastAppliedRevision = 9n;

    const again = await service.addSpaceMember(userId, space.id, second.id);
    expect(again.created).toBe(false);
    expect(again.member.deviceId).toBe(first.member.deviceId);
    expect(again.member.joinedAt).toBe(first.member.joinedAt);
    expect(spaces.members.size).toBe(2);
    expect(spaces.cursors.get(`${space.id}:${second.id}`)?.lastAppliedRevision).toBe(9n);
  });

  it('主设备可移除任意成员，从设备只能退出自己', async () => {
    const { service, devices, spaces, device, space, userId } = await setupSpace();
    const ownSecond = await addDevice(devices, userId);
    const other = await addDevice(devices, 'user-2');
    seedMembership(spaces, space.id, ownSecond.id, 'member');
    seedMembership(spaces, space.id, other.id, 'member');
    await service.createMapping(userId, space.id, {
      deviceId: ownSecond.id,
      localPathLabel: 'DCIM',
      onlyMedia: false,
    });

    // 从设备不能踢别人
    await expectAppError(
      () => service.removeSpaceMember(userId, ownSecond.id, space.id, other.id),
      403,
      'SPACE_FORBIDDEN',
    );
    // 从设备可以退出自己
    await service.removeSpaceMember(userId, ownSecond.id, space.id, ownSecond.id);
    expect(await spaces.findMember(space.id, ownSecond.id)).toBeNull();
    expect(await spaces.listMappings(space.id, ownSecond.id)).toHaveLength(0);

    // 主设备可以踢任何人
    await service.removeSpaceMember(userId, device.id, space.id, other.id);
    expect(await spaces.findMember(space.id, other.id)).toBeNull();
    await expectAppError(
      () => service.removeSpaceMember(userId, device.id, space.id, other.id),
      404,
      'NOT_MEMBER',
    );
  });
});

describe('SpaceService 加入空间申请', () => {
  async function setupWithSecondary() {
    const context = await setupSpace();
    const secondary = await context.devices.create({
      userId: context.userId,
      deviceName: 'Tablet',
      platform: 'android',
      credentialStatus: 'active',
      lastSeenAt: NOW,
    });
    return { ...context, secondary };
  }

  it('从设备申请后进入待审批，主设备同意后成为成员', async () => {
    const { service, spaces, device, secondary, space, userId } = await setupWithSecondary();

    const applied = await service.requestToJoinSpace(userId, space.id, secondary.id);
    expect(applied.joined).toBe(false);
    expect(applied.request).toMatchObject({
      spaceId: space.id,
      deviceId: secondary.id,
      deviceName: 'Tablet',
      status: 'pending',
    });

    // 重复申请幂等返回同一条记录
    const repeated = await service.requestToJoinSpace(userId, space.id, secondary.id);
    expect(repeated.request.id).toBe(applied.request.id);

    // 从设备不能查看待审批列表
    await expectAppError(
      () => service.listJoinRequests(userId, space.id, secondary.id),
      403,
      'DEVICE_NOT_PRIMARY',
    );

    const pending = await service.listJoinRequests(userId, space.id, device.id);
    expect(pending).toHaveLength(1);

    const approved = await service.resolveJoinRequest(userId, pending[0].id, device.id, true);
    expect(approved.status).toBe('approved');
    expect(approved.resolvedAt).toBe(NOW.toISOString());
    const members = await spaces.listMembers(space.id);
    expect(members.map((member) => member.deviceId)).toContain(secondary.id);
    expect(await service.listJoinRequests(userId, space.id, device.id)).toHaveLength(0);

    // 已处理的申请不能再次审批
    await expectAppError(
      () => service.resolveJoinRequest(userId, pending[0].id, device.id, true),
      409,
      'JOIN_REQUEST_RESOLVED',
    );
  });

  it('拒绝申请不会加入成员，且从设备无法代替主设备审批', async () => {
    const { service, spaces, device, secondary, space, userId } = await setupWithSecondary();
    const applied = await service.requestToJoinSpace(userId, space.id, secondary.id);

    await expectAppError(
      () => service.resolveJoinRequest(userId, applied.request.id, secondary.id, true),
      403,
      'DEVICE_NOT_PRIMARY',
    );

    const rejected = await service.resolveJoinRequest(userId, applied.request.id, device.id, false);
    expect(rejected.status).toBe('rejected');
    const members = await spaces.listMembers(space.id);
    expect(members.map((member) => member.deviceId)).not.toContain(secondary.id);
  });

  it('空间列表返回本机最新申请状态，拒绝后可重新申请', async () => {
    const { service, device, secondary, space, userId } = await setupWithSecondary();

    const initial = await service.listSpaces(userId, secondary.id);
    expect(initial.find((item) => item.id === space.id)?.joinRequestStatus).toBeNull();

    const applied = await service.requestToJoinSpace(userId, space.id, secondary.id);
    const pending = await service.listSpaces(userId, secondary.id);
    expect(pending.find((item) => item.id === space.id)?.joinRequestStatus).toBe('pending');

    await service.resolveJoinRequest(userId, applied.request.id, device.id, false);
    const rejected = await service.listSpaces(userId, secondary.id);
    expect(rejected.find((item) => item.id === space.id)?.joinRequestStatus).toBe('rejected');

    const reapplied = await service.requestToJoinSpace(userId, space.id, secondary.id);
    expect(reapplied.request.id).not.toBe(applied.request.id);
    const pendingAgain = await service.listSpaces(userId, secondary.id);
    expect(pendingAgain.find((item) => item.id === space.id)?.joinRequestStatus).toBe('pending');
  });

  it('主设备自己申请时直接成为成员，已是成员再申请返回 409', async () => {
    const { service, space, device, userId } = await setupSpace();
    await service.removeSpaceMember(userId, device.id, space.id, device.id);

    const joined = await service.requestToJoinSpace(userId, space.id, device.id);
    expect(joined.joined).toBe(true);
    expect(joined.request.status).toBe('approved');

    await expectAppError(
      () => service.requestToJoinSpace(userId, space.id, device.id),
      409,
      'ALREADY_MEMBER',
    );
  });

  it('非空间所有者不能为空间提交申请', async () => {
    const { service, devices, space } = await setupWithSecondary();
    const outsider = await devices.create({
      userId: 'user-2',
      deviceName: 'Other Phone',
      platform: 'android',
      credentialStatus: 'active',
      lastSeenAt: NOW,
    });
    await devices.claimPrimaryDevice('user-2', outsider.id);

    await expectAppError(
      () => service.requestToJoinSpace('user-2', space.id, outsider.id),
      403,
      'SPACE_FORBIDDEN',
    );
  });
});

describe('SpaceService 目录映射（一空间一设备一个目录）', () => {
  it('创建映射时保存本地目录标签与 onlyMedia', async () => {
    const { service, spaces, device, space, userId } = await setupSpace();
    const mapping = await service.createMapping(userId, space.id, {
      deviceId: device.id,
      localPathLabel: '  DCIM  ',
      onlyMedia: true,
    });

    expect(mapping).toMatchObject({
      localPathLabel: 'DCIM',
      onlyMedia: true,
    });
    expect(spaces.mappings.get(mapping.id)?.filters).toEqual({ onlyMedia: true });
    expect(normalizeRemotePath(' \\// ')).toBe('');
  });

  it('拒绝 ..、控制字符、超长段和超长总路径', async () => {
    for (const value of [
      'Camera/../private',
      'Camera/\u0001bad',
      'x'.repeat(129),
      `${'a/'.repeat(300)}b`,
    ]) {
      expect(() => normalizeRemotePath(value)).toThrowError(AppError);
      try {
        normalizeRemotePath(value);
      } catch (error) {
        expect((error as AppError).code).toBe('VALIDATION_ERROR');
      }
    }
  });

  it('设备未加入空间时创建映射返回 409 NOT_MEMBER', async () => {
    const { service, devices, space, userId } = await setupSpace();
    const second = await addDevice(devices, userId);
    await expectAppError(
      () =>
        service.createMapping(userId, space.id, {
          deviceId: second.id,
          localPathLabel: 'DCIM',
          onlyMedia: false,
        }),
      409,
      'NOT_MEMBER',
    );
  });

  it('同一设备同一空间只能绑定一个目录，其他设备可各自绑定', async () => {
    const { service, devices, space, device, userId } = await setupSpace();
    await service.createMapping(userId, space.id, {
      deviceId: device.id,
      localPathLabel: 'DCIM',
      onlyMedia: false,
    });
    await expectAppError(
      () =>
        service.createMapping(userId, space.id, {
          deviceId: device.id,
          localPathLabel: 'Documents',
          onlyMedia: false,
        }),
      409,
      'MAPPING_EXISTS',
    );

    const second = await addDevice(devices, userId);
    await service.addSpaceMember(userId, space.id, second.id);
    await expect(
      service.createMapping(userId, space.id, {
        deviceId: second.id,
        localPathLabel: 'Pictures',
        onlyMedia: false,
      }),
    ).resolves.toMatchObject({ deviceId: second.id, localPathLabel: 'Pictures' });
  });

  it('映射会保存设备端 treeUri，可按设备列出用于重装后恢复', async () => {
    const { service, space, device, userId } = await setupSpace();
    const treeUri = 'content://com.android.externalstorage.documents/tree/primary%3ADCIM';
    const mapping = await service.createMapping(userId, space.id, {
      deviceId: device.id,
      localPathLabel: 'DCIM',
      treeUri,
      onlyMedia: false,
    });
    expect(mapping.treeUri).toBe(treeUri);

    const listed = await service.listDeviceMappings(userId, device.id);
    expect(listed).toHaveLength(1);
    expect(listed[0]).toMatchObject({ id: mapping.id, spaceId: space.id, treeUri });

    const updated = await service.updateMapping(userId, mapping.id, {
      treeUri: 'content://com.android.externalstorage.documents/tree/primary%3APictures',
    });
    expect(updated.treeUri).toContain('primary%3APictures');
  });

  it('更新映射只改标签与过滤规则，他人的映射按 404 隐藏', async () => {
    const { service, space, device, userId } = await setupSpace();
    const mapping = await service.createMapping(userId, space.id, {
      deviceId: device.id,
      localPathLabel: 'DCIM',
      onlyMedia: false,
    });

    const updated = await service.updateMapping(userId, mapping.id, {
      localPathLabel: '  Camera  ',
      onlyMedia: true,
    });
    expect(updated.localPathLabel).toBe('Camera');
    expect(updated.onlyMedia).toBe(true);
    await expectAppError(
      () => service.updateMapping('user-2', mapping.id, { localPathLabel: 'hack' }),
      404,
      'MAPPING_NOT_FOUND',
    );
    await expectAppError(() => service.deleteMapping('user-2', mapping.id), 404, 'MAPPING_NOT_FOUND');

    await service.deleteMapping(userId, mapping.id);
    await expectAppError(() => service.deleteMapping(userId, mapping.id), 404, 'MAPPING_NOT_FOUND');
  });
});

describe('SpaceService scan-manifest', () => {
  it('计算上传、下载和冲突，忽略软删除文件并转换 BigInt size', async () => {
    const { service, spaces, space, device, userId } = await setupSpace();
    const mapping = await service.createMapping(userId, space.id, {
      deviceId: device.id,
      localPathLabel: 'DCIM',
      onlyMedia: false,
    });
    seedFile(spaces, space.id, 'same.jpg', HASH_A);
    seedFile(spaces, space.id, 'changed.jpg', HASH_A);
    seedFile(spaces, space.id, 'missing.jpg', HASH_A);
    seedFile(spaces, space.id, 'deleted.jpg', HASH_A, true);

    const result = await service.scanManifest(userId, space.id, {
      deviceId: device.id,
      mappingId: mapping.id,
      scanComplete: true,
      files: [
        { relativePath: 'same.jpg', size: 123, sha256: HASH_A },
        { relativePath: 'changed.jpg', size: 123, sha256: HASH_B },
        { relativePath: 'new.jpg', size: 50, sha256: HASH_A },
      ],
    });

    expect(result.summary).toEqual({
      localCount: 3,
      remoteCount: 3,
      toUpload: 2,
      toDownload: 1,
      conflictCount: 1,
      diffComplete: true,
    });
    expect(result.remoteFiles).toHaveLength(3);
    expect(result.remoteFiles[0]).toMatchObject({ size: 123, version: 2 });
    expect(result.remoteFiles.some((file) => file.relativePath === 'deleted.jpg')).toBe(false);
  });

  it('scanComplete=false 时不推断下载且 diffComplete=false', async () => {
    const { service, spaces, space, device, userId } = await setupSpace();
    const mapping = await service.createMapping(userId, space.id, {
      deviceId: device.id,
      localPathLabel: 'Root',
      onlyMedia: false,
    });
    seedFile(spaces, space.id, 'remote.jpg', HASH_A);

    const result = await service.scanManifest(userId, space.id, {
      deviceId: device.id,
      mappingId: mapping.id,
      scanComplete: false,
      files: [{ relativePath: 'local.jpg', size: 1 }],
    });
    expect(result.summary).toMatchObject({ toUpload: 1, toDownload: 0, diffComplete: false });
  });

  it('mapping 与空间或设备不匹配时返回 404 MAPPING_NOT_FOUND', async () => {
    const { service, space, device, userId } = await setupSpace();
    await expectAppError(
      () =>
        service.scanManifest(userId, space.id, {
          deviceId: device.id,
          mappingId: randomUUID(),
          scanComplete: true,
          files: [],
        }),
      404,
      'MAPPING_NOT_FOUND',
    );
  });

  it('remoteFiles 截断到 10000 条并将 diffComplete 置为 false', async () => {
    const { service, spaces, space, device, userId } = await setupSpace();
    const mapping = await service.createMapping(userId, space.id, {
      deviceId: device.id,
      localPathLabel: 'Root',
      onlyMedia: false,
    });
    for (let index = 0; index < 10001; index += 1) {
      seedFile(spaces, space.id, `file-${index}.jpg`, HASH_A);
    }

    const result = await service.scanManifest(userId, space.id, {
      deviceId: device.id,
      mappingId: mapping.id,
      scanComplete: true,
      files: [],
    });
    expect(result.remoteFiles).toHaveLength(10000);
    expect(result.summary.remoteCount).toBe(10001);
    expect(result.summary.toDownload).toBe(10001);
    expect(result.summary.diffComplete).toBe(false);
  });

  it('同空间的两台设备各自绑定目录后看到同一棵树', async () => {
    const { service, devices, spaces, space, device, userId } = await setupSpace();
    const first = await service.createMapping(userId, space.id, {
      deviceId: device.id,
      localPathLabel: 'DCIM',
      onlyMedia: false,
    });
    const secondDevice = await addDevice(devices, userId);
    await service.addSpaceMember(userId, space.id, secondDevice.id);
    const second = await service.createMapping(userId, space.id, {
      deviceId: secondDevice.id,
      localPathLabel: 'Documents',
      onlyMedia: false,
    });
    seedFile(spaces, space.id, 'photo.jpg', HASH_A);
    seedFile(spaces, space.id, 'report.pdf', HASH_A);

    const firstResult = await service.scanManifest(userId, space.id, {
      deviceId: device.id,
      mappingId: first.id,
      scanComplete: true,
      files: [],
    });
    const secondResult = await service.scanManifest(userId, space.id, {
      deviceId: secondDevice.id,
      mappingId: second.id,
      scanComplete: true,
      files: [],
    });

    expect(firstResult.summary).toMatchObject({ remoteCount: 2, toDownload: 2 });
    expect(secondResult.summary).toMatchObject({ remoteCount: 2, toDownload: 2 });
    expect(firstResult.remoteFiles.map((file) => file.relativePath)).toEqual([
      'photo.jpg',
      'report.pdf',
    ]);
  });

  it('媒体过滤且不猜测缺失 hash 冲突，子目录文件参与同步', async () => {
    const { service, spaces, space, device, userId } = await setupSpace();
    const mapping = await service.createMapping(userId, space.id, {
      deviceId: device.id,
      localPathLabel: 'Root',
      onlyMedia: true,
    });
    seedFile(spaces, space.id, 'root.jpg', HASH_A);
    seedFile(spaces, space.id, 'notes.txt', HASH_A);
    seedFile(spaces, space.id, 'nested/child.jpg', HASH_A);
    seedFile(spaces, space.id, 'movie.MP4', HASH_A);

    const result = await service.scanManifest(userId, space.id, {
      deviceId: device.id,
      mappingId: mapping.id,
      scanComplete: true,
      files: [
        { relativePath: 'root.jpg', size: 123 },
        { relativePath: 'nested/new.jpg', size: 1, sha256: HASH_B },
        { relativePath: 'new.txt', size: 1, sha256: HASH_B },
      ],
    });

    expect(result.summary).toMatchObject({
      remoteCount: 3,
      toUpload: 1,
      toDownload: 2,
      conflictCount: 0,
    });
    expect(result.remoteFiles.map((file) => file.relativePath)).toEqual([
      'movie.MP4',
      'nested/child.jpg',
      'root.jpg',
    ]);
  });
});
