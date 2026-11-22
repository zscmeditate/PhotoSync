import { randomUUID } from 'node:crypto';
import { describe, expect, it } from 'vitest';
import { AppError } from '../src/lib/errors';
import type { DeviceRecord, MemberRoleValue } from '../src/repositories/ports';
import {
  normalizeRemotePrefix,
  remotePrefixesOverlap,
  SpaceService,
} from '../src/services/space.service';
import { FakeDeviceRepository, FakeSpaceRepository } from './fakes';

const NOW = new Date('2026-01-02T03:04:05.000Z');
const HASH_A = 'a'.repeat(64);
const HASH_B = 'b'.repeat(64);

function makeService() {
  const devices = new FakeDeviceRepository();
  const spaces = new FakeSpaceRepository(devices);
  const service = new SpaceService({ devices, spaces, now: () => NOW });
  return { service, devices, spaces };
}

async function addDevice(
  devices: FakeDeviceRepository,
  userId: string,
  credentialStatus: DeviceRecord['credentialStatus'] = 'active',
): Promise<DeviceRecord> {
  return devices.create({
    userId,
    deviceName: `${userId} device`,
    platform: 'android',
    credentialStatus,
    lastSeenAt: NOW,
  });
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
): void {
  const id = randomUUID();
  spaces.files.set(id, {
    id,
    spaceId,
    relativePath,
    fileName: relativePath.split('/').at(-1)!,
    objectId: randomUUID(),
    size: 123n,
    sha256,
    version: 2,
    isDeleted,
    createdAt: NOW,
    updatedAt: NOW,
    lastModifiedByDeviceId: randomUUID(),
  });
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
  it('创建空间时同时写入 owner 成员与零游标，并序列化 revision', async () => {
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
      role: 'owner',
      memberCount: 1,
      mappingCount: 0,
    });
    expect(spaces.spaces.get(space.id)?.revision).toBe(0n);
    expect(spaces.members.get(`${space.id}:${device.id}`)?.role).toBe('owner');
    expect(spaces.cursors.get(`${space.id}:${device.id}`)?.lastAppliedRevision).toBe(0n);
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
    expect(list.find((space) => space.id === owned.id)?.role).toBe('owner');
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
    expect(spaces.members.size).toBe(2);

    await expectAppError(() => service.getSpace('stranger', space.id), 403, 'SPACE_FORBIDDEN');
    await expectAppError(() => service.getSpace(userId, randomUUID()), 404, 'SPACE_NOT_FOUND');
  });
});

describe('SpaceService 空间修改与删除权限', () => {
  it('owner 可改名和删除', async () => {
    const { service, spaces, space, userId } = await setupSpace();
    const updated = await service.updateSpace(userId, space.id, {
      name: '  New name ',
      description: 'updated',
    });
    expect(updated.name).toBe('New name');
    expect(updated.description).toBe('updated');

    await service.deleteSpace(userId, space.id);
    expect(spaces.spaces.has(space.id)).toBe(false);
  });

  it('admin 可改名但不能删除，普通成员两种操作均为 403', async () => {
    const { service, spaces, device, space, userId } = await setupSpace();
    spaces.spaces.get(space.id)!.ownerId = 'user-2';
    const member = spaces.members.get(`${space.id}:${device.id}`)!;
    member.role = 'member';

    await expectAppError(
      () => service.updateSpace(userId, space.id, { name: 'Forbidden' }),
      403,
      'SPACE_FORBIDDEN',
    );
    await expectAppError(() => service.deleteSpace(userId, space.id), 403, 'SPACE_FORBIDDEN');

    member.role = 'admin';
    await expect(service.updateSpace(userId, space.id, { name: 'Admin edit' })).resolves.toMatchObject(
      { name: 'Admin edit', role: 'admin' },
    );
    await expectAppError(() => service.deleteSpace(userId, space.id), 403, 'SPACE_FORBIDDEN');
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

  it('非 owner 只能移除自己的活跃设备，owner 可移除任意成员；不存在返回 404', async () => {
    const { service, devices, spaces, device, space, userId } = await setupSpace();
    const ownSecond = await addDevice(devices, userId);
    const other = await addDevice(devices, 'user-2');
    seedMembership(spaces, space.id, ownSecond.id, 'member');
    seedMembership(spaces, space.id, other.id, 'member');
    spaces.spaces.get(space.id)!.ownerId = 'user-2';
    spaces.members.get(`${space.id}:${device.id}`)!.role = 'member';

    await expectAppError(
      () => service.removeSpaceMember(userId, space.id, other.id),
      400,
      'INVALID_DEVICE',
    );
    await service.removeSpaceMember(userId, space.id, ownSecond.id);
    expect(await spaces.findMember(space.id, ownSecond.id)).toBeNull();

    await service.removeSpaceMember('user-2', space.id, device.id);
    expect(await spaces.findMember(space.id, device.id)).toBeNull();
    await expectAppError(
      () => service.removeSpaceMember('user-2', space.id, device.id),
      404,
      'NOT_MEMBER',
    );
  });
});

describe('SpaceService 映射规范化与重叠', () => {
  it('规范化反斜杠和多余斜杠，存储并返回规范化值及 onlyMedia', async () => {
    const { service, spaces, device, space, userId } = await setupSpace();
    const mapping = await service.createMapping(userId, space.id, {
      deviceId: device.id,
      localPathLabel: '  DCIM  ',
      remotePrefix: '  \\Camera\\2024//Holiday/ ',
      includeSubdirs: false,
      onlyMedia: true,
    });

    expect(mapping).toMatchObject({
      localPathLabel: 'DCIM',
      remotePrefix: 'Camera/2024/Holiday',
      includeSubdirs: false,
      onlyMedia: true,
    });
    expect(spaces.mappings.get(mapping.id)?.filters).toEqual({ onlyMedia: true });
    expect(normalizeRemotePrefix(' \\// ')).toBe('');
  });

  it('拒绝 ..、控制字符、超长段和超长总路径', async () => {
    for (const value of [
      'Camera/../private',
      'Camera/\u0001bad',
      'x'.repeat(129),
      `${'a/'.repeat(300)}b`,
    ]) {
      expect(() => normalizeRemotePrefix(value)).toThrowError(AppError);
      try {
        normalizeRemotePrefix(value);
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
          remotePrefix: 'Camera',
          includeSubdirs: true,
          onlyMedia: false,
        }),
      409,
      'NOT_MEMBER',
    );
  });

  it('检测父子前缀与根前缀重叠，同设备内大小写敏感', async () => {
    const { service, devices, space, device, userId } = await setupSpace();
    const input = {
      deviceId: device.id,
      localPathLabel: 'DCIM',
      includeSubdirs: true,
      onlyMedia: false,
    };
    await service.createMapping(userId, space.id, { ...input, remotePrefix: 'Camera' });
    await expectAppError(
      () => service.createMapping(userId, space.id, { ...input, remotePrefix: 'Camera/2024' }),
      409,
      'MAPPING_OVERLAP',
    );
    await expect(
      service.createMapping(userId, space.id, { ...input, remotePrefix: 'camera' }),
    ).resolves.toMatchObject({ remotePrefix: 'camera' });

    const second = await addDevice(devices, userId);
    await service.addSpaceMember(userId, space.id, second.id);
    await service.createMapping(userId, space.id, {
      ...input,
      deviceId: second.id,
      remotePrefix: '',
    });
    expect(remotePrefixesOverlap('', 'Anything')).toBe(true);
    await expectAppError(
      () =>
        service.createMapping(userId, space.id, {
          ...input,
          deviceId: second.id,
          remotePrefix: 'Anything',
        }),
      409,
      'MAPPING_OVERLAP',
    );
  });

  it('更新时排除自身做重叠校验，他人映射按 404 隐藏', async () => {
    const { service, space, device, userId } = await setupSpace();
    const input = {
      deviceId: device.id,
      localPathLabel: 'DCIM',
      includeSubdirs: true,
      onlyMedia: false,
    };
    const first = await service.createMapping(userId, space.id, {
      ...input,
      remotePrefix: 'Camera',
    });
    const second = await service.createMapping(userId, space.id, {
      ...input,
      remotePrefix: 'Other',
    });

    const updated = await service.updateMapping(userId, first.id, {
      remotePrefix: '\\Camera\\2024//',
      onlyMedia: true,
    });
    expect(updated.remotePrefix).toBe('Camera/2024');
    expect(updated.onlyMedia).toBe(true);
    await expectAppError(
      () => service.updateMapping(userId, second.id, { remotePrefix: 'Camera/2024/child' }),
      409,
      'MAPPING_OVERLAP',
    );
    await expectAppError(
      () => service.updateMapping('user-2', first.id, { localPathLabel: 'hack' }),
      404,
      'MAPPING_NOT_FOUND',
    );
    await expectAppError(() => service.deleteMapping('user-2', first.id), 404, 'MAPPING_NOT_FOUND');

    await service.deleteMapping(userId, first.id);
    await expectAppError(() => service.deleteMapping(userId, first.id), 404, 'MAPPING_NOT_FOUND');
  });
});

describe('SpaceService scan-manifest', () => {
  it('计算上传、下载和冲突，忽略软删除文件并转换 BigInt size', async () => {
    const { service, spaces, space, device, userId } = await setupSpace();
    const mapping = await service.createMapping(userId, space.id, {
      deviceId: device.id,
      localPathLabel: 'DCIM',
      remotePrefix: 'Camera',
      includeSubdirs: true,
      onlyMedia: false,
    });
    seedFile(spaces, space.id, 'Camera/same.jpg', HASH_A);
    seedFile(spaces, space.id, 'Camera/changed.jpg', HASH_A);
    seedFile(spaces, space.id, 'Camera/missing.jpg', HASH_A);
    seedFile(spaces, space.id, 'Other/outside.jpg', HASH_A);
    seedFile(spaces, space.id, 'Camera/deleted.jpg', HASH_A, true);

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
    expect(result.remoteFiles.some((file) => file.relativePath === 'Camera/deleted.jpg')).toBe(false);
  });

  it('scanComplete=false 时不推断下载且 diffComplete=false', async () => {
    const { service, spaces, space, device, userId } = await setupSpace();
    const mapping = await service.createMapping(userId, space.id, {
      deviceId: device.id,
      localPathLabel: 'Root',
      remotePrefix: '',
      includeSubdirs: true,
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
      remotePrefix: '',
      includeSubdirs: true,
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

  it('Camera 与 Documents 前缀互不计数，remoteFiles 保留完整空间路径', async () => {
    const { service, spaces, space, device, userId } = await setupSpace();
    const camera = await service.createMapping(userId, space.id, {
      deviceId: device.id,
      localPathLabel: 'DCIM',
      remotePrefix: 'Camera',
      includeSubdirs: true,
      onlyMedia: false,
    });
    const documents = await service.createMapping(userId, space.id, {
      deviceId: device.id,
      localPathLabel: 'Documents',
      remotePrefix: 'Documents',
      includeSubdirs: true,
      onlyMedia: false,
    });
    seedFile(spaces, space.id, 'Camera/photo.jpg', HASH_A);
    seedFile(spaces, space.id, 'Documents/report.pdf', HASH_A);

    const cameraResult = await service.scanManifest(userId, space.id, {
      deviceId: device.id,
      mappingId: camera.id,
      scanComplete: true,
      files: [],
    });
    const documentsResult = await service.scanManifest(userId, space.id, {
      deviceId: device.id,
      mappingId: documents.id,
      scanComplete: true,
      files: [],
    });

    expect(cameraResult.summary).toMatchObject({ remoteCount: 1, toDownload: 1 });
    expect(cameraResult.remoteFiles.map((file) => file.relativePath)).toEqual(['Camera/photo.jpg']);
    expect(documentsResult.summary).toMatchObject({ remoteCount: 1, toDownload: 1 });
    expect(documentsResult.remoteFiles.map((file) => file.relativePath)).toEqual([
      'Documents/report.pdf',
    ]);
  });

  it('根映射和 includeSubdirs=false 只统计直接子文件，媒体过滤且不猜测缺失 hash 冲突', async () => {
    const { service, spaces, space, device, userId } = await setupSpace();
    const mapping = await service.createMapping(userId, space.id, {
      deviceId: device.id,
      localPathLabel: 'Root',
      remotePrefix: '',
      includeSubdirs: false,
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
      remoteCount: 2,
      toUpload: 0,
      toDownload: 1,
      conflictCount: 0,
    });
    expect(result.remoteFiles.map((file) => file.relativePath)).toEqual(['movie.MP4', 'root.jpg']);
  });
});
