import { createHash } from 'node:crypto';
import type { FastifyInstance } from 'fastify';
import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import { buildApp } from '../src/app';
import type { Config } from '../src/config';
import { JwtService } from '../src/lib/jwt';
import type { DeviceRecord, SyncEventRecord } from '../src/repositories/ports';
import {
  FakeDeviceRepository,
  FakeObjectStore,
  FakeRefreshTokenRepository,
  FakeSpaceRepository,
  FakeSyncRepository,
  FakeTransferRepository,
  FakeUserRepository,
} from './fakes';

const JWT_SECRET = 'test-secret-0123456789abcdef';
const PART_SIZE = 4;
const config: Config = {
  NODE_ENV: 'test',
  HOST: '127.0.0.1',
  PORT: 3000,
  DATABASE_URL: 'postgresql://test:test@localhost:5432/photosync_test',
  JWT_SECRET,
  ACCESS_TOKEN_TTL: '15m',
  REFRESH_TOKEN_TTL_DAYS: 30,
  S3_ENDPOINT: 'http://localhost:9000',
  S3_ACCESS_KEY: 'test-access',
  S3_SECRET_KEY: 'test-secret',
  S3_BUCKET: 'photosync-test',
  S3_REGION: 'us-east-1',
  S3_FORCE_PATH_STYLE: true,
  UPLOAD_PART_SIZE: PART_SIZE,
};
const jwt = new JwtService(JWT_SECRET, '15m');
const auth = { authorization: `Bearer ${jwt.signAccessToken('user-1')}` };

let app: FastifyInstance;
let devices: FakeDeviceRepository;
let spaces: FakeSpaceRepository;
let transfers: FakeTransferRepository;
let sync: FakeSyncRepository;
let objectStore: FakeObjectStore;
let deviceA: DeviceRecord;
let deviceB: DeviceRecord;
let deviceD: DeviceRecord;
let spaceId: string;

beforeEach(async () => {
  devices = new FakeDeviceRepository();
  spaces = new FakeSpaceRepository(devices);
  transfers = new FakeTransferRepository();
  sync = new FakeSyncRepository(spaces, transfers);
  objectStore = new FakeObjectStore();
  app = await buildApp({
    config,
    users: new FakeUserRepository(),
    devices,
    refreshTokens: new FakeRefreshTokenRepository(),
    spaces,
    transfers,
    sync,
    objectStore,
    logger: false,
  });
  await app.ready();
  deviceA = await addDevice('user-1', 'A');
  deviceB = await addDevice('user-1', 'B');
  deviceD = await addDevice('user-1', 'D');
  const created = await app.inject({
    method: 'POST',
    url: '/api/v1/spaces',
    headers: auth,
    payload: { name: 'Photos', deviceId: deviceA.id },
  });
  spaceId = (created.json() as { space: { id: string } }).space.id;
  const joined = await app.inject({
    method: 'POST',
    url: `/api/v1/spaces/${spaceId}/members`,
    headers: auth,
    payload: { deviceId: deviceB.id },
  });
  expect(joined.statusCode).toBe(201);
});

afterEach(async () => {
  await app.close();
});

async function addDevice(userId: string, deviceName: string): Promise<DeviceRecord> {
  return devices.create({
    userId,
    deviceName,
    platform: 'android',
    credentialStatus: 'active',
    lastSeenAt: new Date(),
  });
}

function sha256(content: Buffer): string {
  return createHash('sha256').update(content).digest('hex');
}

function errorCode(body: unknown): string {
  return (body as { error: { code: string } }).error.code;
}

async function uploadAll(relativePath: string, content: Buffer, key: string) {
  const created = await app.inject({
    method: 'POST',
    url: '/api/v1/uploads',
    headers: auth,
    payload: {
      spaceId,
      deviceId: deviceA.id,
      relativePath,
      size: content.byteLength,
      sha256: sha256(content),
      partSize: 0,
      idempotencyKey: key,
    },
  });
  expect(created.statusCode).toBe(201);
  const upload = (created.json() as { upload: Record<string, unknown> }).upload;
  for (
    let offset = 0, partNumber = 1;
    offset < content.byteLength;
    offset += PART_SIZE, partNumber += 1
  ) {
    const part = await app.inject({
      method: 'PUT',
      url: `/api/v1/uploads/${upload.uploadId}/parts/${partNumber}?deviceId=${deviceA.id}`,
      headers: {
        ...auth,
        'content-type': 'application/octet-stream',
        'idempotency-key': `${key}-part-${partNumber}`,
      },
      payload: content.subarray(offset, offset + PART_SIZE),
    });
    expect(part.statusCode).toBe(200);
  }
  const completed = await app.inject({
    method: 'POST',
    url: `/api/v1/uploads/${upload.uploadId}/complete`,
    headers: auth,
    payload: { deviceId: deviceA.id, idempotencyKey: `${key}-complete` },
  });
  expect(completed.statusCode).toBe(200);
  return (completed.json() as { upload: Record<string, unknown> }).upload;
}

async function commitOp(payload: Record<string, unknown>) {
  return app.inject({
    method: 'POST',
    url: `/api/v1/spaces/${spaceId}/operations`,
    headers: auth,
    payload,
  });
}

async function createFile(relativePath: string, content: Buffer, key: string) {
  const upload = await uploadAll(relativePath, content, key);
  const operation = await commitOp({
    deviceId: deviceA.id,
    operationId: `${key}-op`,
    type: 'file_created',
    uploadId: upload.uploadId,
    baseVersion: 0,
  });
  expect(operation.statusCode).toBe(201);
  const body = operation.json() as { file: Record<string, any>; event: Record<string, any> };
  return { upload, fileId: body.file.fileId as string, version: body.file.version as number };
}

async function deleteFile(fileId: string, baseVersion: number, key: string, extra: Record<string, unknown> = {}) {
  return commitOp({
    deviceId: deviceA.id,
    operationId: key,
    type: 'file_deleted',
    fileId,
    baseVersion,
    ...extra,
  });
}

async function listTrash(deviceId = deviceA.id) {
  const response = await app.inject({
    method: 'GET',
    url: `/api/v1/spaces/${spaceId}/trash?deviceId=${deviceId}`,
    headers: auth,
  });
  return { response, body: response.json() as { items: Array<Record<string, any>> } };
}

async function listConflicts(deviceId = deviceA.id) {
  const response = await app.inject({
    method: 'GET',
    url: `/api/v1/spaces/${spaceId}/conflicts?deviceId=${deviceId}`,
    headers: auth,
  });
  return { response, body: response.json() as { conflicts: Array<Record<string, any>> } };
}

describe('Phase 5 回收站（Tombstone）', () => {
  it('删除写入回收站，恢复生成 file_restored 事件并清空记录', async () => {
    const created = await createFile('Camera/a.jpg', Buffer.from('hello'), 'trash-create');

    const deleted = await deleteFile(created.fileId, 1, 'trash-delete');
    expect(deleted.statusCode).toBe(201);

    const afterDelete = await listTrash();
    expect(afterDelete.response.statusCode).toBe(200);
    expect(afterDelete.body.items).toHaveLength(1);
    expect(afterDelete.body.items[0]).toMatchObject({
      fileId: created.fileId,
      relativePath: 'Camera/a.jpg',
      deletedByDeviceId: deviceA.id,
      version: 1,
    });
    expect(new Date(afterDelete.body.items[0].purgeAfter).getTime()).toBeGreaterThan(Date.now());

    const trashId = afterDelete.body.items[0].trashId as string;
    const restored = await app.inject({
      method: 'POST',
      url: `/api/v1/trash/${trashId}/restore`,
      headers: auth,
      payload: { deviceId: deviceA.id, operationId: 'restore-op' },
    });
    expect(restored.statusCode).toBe(200);
    const restoredBody = restored.json() as { file: Record<string, any>; event: Record<string, any> };
    expect(restoredBody.file).toMatchObject({
      fileId: created.fileId,
      relativePath: 'Camera/a.jpg',
      isDeleted: false,
    });
    expect(restoredBody.event.type).toBe('file_restored');

    const afterRestore = await listTrash();
    expect(afterRestore.body.items).toHaveLength(0);

    // 恢复后文件重新可修改。
    const upload = await uploadAll('Camera/a.jpg', Buffer.from('hello again'), 'trash-restore-v2');
    const updated = await commitOp({
      deviceId: deviceA.id,
      operationId: 'trash-restore-update',
      type: 'file_updated',
      uploadId: upload.uploadId,
      fileId: created.fileId,
      baseVersion: 1,
    });
    expect(updated.statusCode).toBe(201);
  });

  it('恢复时原路径被其他活动文件占用返回 PATH_CONFLICT', async () => {
    const created = await createFile('Camera/b.jpg', Buffer.from('original'), 'trash-occupy-create');
    const deleted = await deleteFile(created.fileId, 1, 'trash-occupy-delete');
    expect(deleted.statusCode).toBe(201);
    // 删除后原路径可重新创建，占用路径。
    await createFile('Camera/b.jpg', Buffer.from('new file'), 'trash-occupy-recreate');

    const trash = await listTrash();
    const trashId = trash.body.items[0].trashId as string;
    const restored = await app.inject({
      method: 'POST',
      url: `/api/v1/trash/${trashId}/restore`,
      headers: auth,
      payload: { deviceId: deviceA.id, operationId: 'restore-occupied' },
    });
    expect(restored.statusCode).toBe(409);
    expect(errorCode(restored.json())).toBe('PATH_CONFLICT');
  });

  it('清理回收站删除记录与对象，且不可重复操作', async () => {
    const created = await createFile('Camera/c.jpg', Buffer.from('purge me'), 'trash-purge-create');
    await deleteFile(created.fileId, 1, 'trash-purge-delete');
    const trash = await listTrash();
    const trashId = trash.body.items[0].trashId as string;

    const purged = await app.inject({
      method: 'DELETE',
      url: `/api/v1/trash/${trashId}?deviceId=${deviceA.id}`,
      headers: auth,
    });
    expect(purged.statusCode).toBe(200);
    expect(purged.json()).toMatchObject({ purged: true, trashId });

    const after = await listTrash();
    expect(after.body.items).toHaveLength(0);

    const again = await app.inject({
      method: 'DELETE',
      url: `/api/v1/trash/${trashId}?deviceId=${deviceA.id}`,
      headers: auth,
    });
    expect(again.statusCode).toBe(404);
    expect(errorCode(again.json())).toBe('TRASH_NOT_FOUND');
  });

  it('非成员设备访问回收站被拒绝', async () => {
    const { response } = await listTrash(deviceD.id);
    expect(response.statusCode).toBe(409);
    expect(errorCode(response.json())).toBe('NOT_MEMBER');
  });
});

describe('Phase 5 冲突检测与解决', () => {
  it('过期 baseVersion 返回冲突并记录待处理冲突，重复提交不重复记录', async () => {
    const created = await createFile('Docs/report.pdf', Buffer.from('v1'), 'conflict-create');

    const v2 = await uploadAll('Docs/report.pdf', Buffer.from('v2 content'), 'conflict-v2');
    const ok = await commitOp({
      deviceId: deviceA.id,
      operationId: 'conflict-v2-op',
      type: 'file_updated',
      uploadId: v2.uploadId,
      fileId: created.fileId,
      baseVersion: 1,
    });
    expect(ok.statusCode).toBe(201);

    const v3 = await uploadAll('Docs/report.pdf', Buffer.from('v3 content'), 'conflict-v3');
    const stale = await commitOp({
      deviceId: deviceA.id,
      operationId: 'conflict-v3-op',
      type: 'file_updated',
      uploadId: v3.uploadId,
      fileId: created.fileId,
      baseVersion: 1,
    });
    expect(stale.statusCode).toBe(409);
    expect(errorCode(stale.json())).toBe('VERSION_CONFLICT');

    // 同内容重试同一冲突不新增记录。
    await commitOp({
      deviceId: deviceA.id,
      operationId: 'conflict-v3-retry',
      type: 'file_updated',
      uploadId: v3.uploadId,
      fileId: created.fileId,
      baseVersion: 1,
    });
    const conflicts = await listConflicts();
    expect(conflicts.response.statusCode).toBe(200);
    expect(conflicts.body.conflicts).toHaveLength(1);
    expect(conflicts.body.conflicts[0]).toMatchObject({
      fileId: created.fileId,
      serverVersion: 2,
      status: 'pending',
    });
    expect(conflicts.body.conflicts[0].submitted).toMatchObject({
      sha256: sha256(Buffer.from('v3 content')),
      size: String(Buffer.from('v3 content').byteLength),
    });
  });

  it('解决冲突选择 server：保留服务器版本且不产生内容变更事件', async () => {
    const { conflictId } = await createConflict('Docs/server.txt');

    const resolved = await app.inject({
      method: 'POST',
      url: `/api/v1/conflicts/${conflictId}/resolve`,
      headers: auth,
      payload: { deviceId: deviceA.id, operationId: 'resolve-server', resolution: 'server' },
    });
    expect(resolved.statusCode).toBe(200);
    const body = resolved.json() as { conflict: Record<string, any>; file: unknown; event: Record<string, any> };
    expect(body.conflict.status).toBe('resolved');
    expect(body.file).toBeNull();
    expect(body.event.type).toBe('conflict_resolved');

    const again = await app.inject({
      method: 'POST',
      url: `/api/v1/conflicts/${conflictId}/resolve`,
      headers: auth,
      payload: { deviceId: deviceA.id, operationId: 'resolve-server-2', resolution: 'server' },
    });
    expect(again.statusCode).toBe(409);
    expect(errorCode(again.json())).toBe('CONFLICT_ALREADY_RESOLVED');
  });

  it('解决冲突选择 client：提交版本成为新版本且可下载', async () => {
    const { conflictId, submittedContent } = await createConflict('Docs/client.txt');

    const resolved = await app.inject({
      method: 'POST',
      url: `/api/v1/conflicts/${conflictId}/resolve`,
      headers: auth,
      payload: { deviceId: deviceA.id, operationId: 'resolve-client', resolution: 'client' },
    });
    expect(resolved.statusCode).toBe(200);
    const body = resolved.json() as { file: Record<string, any>; event: Record<string, any> };
    expect(body.file).toMatchObject({
      relativePath: 'Docs/client.txt',
      version: 3,
      sha256: sha256(submittedContent),
    });
    expect(body.event.type).toBe('file_updated');

    const download = await app.inject({
      method: 'GET',
      url: `/api/v1/objects/${body.file.objectId}/download?deviceId=${deviceA.id}`,
      headers: auth,
    });
    expect(download.statusCode).toBe(200);
    expect(download.rawPayload.equals(submittedContent)).toBe(true);
  });

  it('解决冲突选择 both：保留服务器版本并写入不覆盖的冲突副本', async () => {
    const { conflictId, submittedContent } = await createConflict('Docs/both.txt');

    const resolved = await app.inject({
      method: 'POST',
      url: `/api/v1/conflicts/${conflictId}/resolve`,
      headers: auth,
      payload: { deviceId: deviceA.id, operationId: 'resolve-both', resolution: 'both' },
    });
    expect(resolved.statusCode).toBe(200);
    const body = resolved.json() as { file: Record<string, any>; event: Record<string, any> };
    expect(body.event.type).toBe('file_created');
    expect(body.file.relativePath).not.toBe('Docs/both.txt');
    expect(body.file.relativePath).toContain('conflict from');
    expect(body.file.sha256).toBe(sha256(submittedContent));

    // 原路径仍是服务器版本。
    const changes = await app.inject({
      method: 'GET',
      url: `/api/v1/spaces/${spaceId}/changes?deviceId=${deviceB.id}&sinceRevision=0&limit=100`,
      headers: auth,
    });
    const events = (changes.json() as { events: Array<Record<string, any>> }).events;
    const createdEvent = events.find((event) => event.type === 'file_created' && event.payload.relativePath === 'Docs/both.txt');
    expect(createdEvent).toBeTruthy();
  });

  it('非成员设备不能列出冲突', async () => {
    const { response } = await listConflicts(deviceD.id);
    expect(response.statusCode).toBe(409);
    expect(errorCode(response.json())).toBe('NOT_MEMBER');
  });
});

describe('Phase 5 大量删除保护', () => {
  it('窗口期内删除达到阈值要求确认，确认后放行', async () => {
    const target = await createFile('Bulk/target.jpg', Buffer.from('target'), 'bulk-target');
    // 直接预置阈值数量的删除事件，避免测试创建大量文件。
    for (let index = 0; index < 50; index += 1) {
      const event: SyncEventRecord = {
        id: `bulk-event-${index}`,
        spaceId,
        revision: BigInt(1000 + index),
        type: 'file_deleted',
        deviceId: deviceA.id,
        operationId: `bulk-op-${index}`,
        payload: {
          fileId: `bulk-file-${index}`,
          relativePath: `Bulk/${index}.jpg`,
          fileName: `${index}.jpg`,
          version: 1,
          baseVersion: 1,
          lastModifiedByDeviceId: deviceA.id,
        },
        createdAt: new Date(),
      };
      sync.events.set(event.id, event);
    }

    const blocked = await deleteFile(target.fileId, 1, 'bulk-delete-blocked');
    expect(blocked.statusCode).toBe(409);
    expect(errorCode(blocked.json())).toBe('BULK_DELETE_CONFIRMATION_REQUIRED');

    const confirmed = await deleteFile(target.fileId, 1, 'bulk-delete-confirmed', {
      confirmBulkDelete: true,
    });
    expect(confirmed.statusCode).toBe(201);
  });
});

/** 构造一次待处理冲突：先提交 v2，再用 baseVersion=1 提交 v3。返回待解决冲突与 v3 内容。 */
async function createConflict(relativePath: string): Promise<{ conflictId: string; submittedContent: Buffer }> {
  const created = await createFile(relativePath, Buffer.from('v1'), `${relativePath}-create`);
  const v2 = await uploadAll(relativePath, Buffer.from('v2'), `${relativePath}-v2`);
  const ok = await commitOp({
    deviceId: deviceA.id,
    operationId: `${relativePath}-v2-op`,
    type: 'file_updated',
    uploadId: v2.uploadId,
    fileId: created.fileId,
    baseVersion: 1,
  });
  expect(ok.statusCode).toBe(201);

  const submittedContent = Buffer.from('v3 submitted');
  const v3 = await uploadAll(relativePath, submittedContent, `${relativePath}-v3`);
  const stale = await commitOp({
    deviceId: deviceA.id,
    operationId: `${relativePath}-v3-op`,
    type: 'file_updated',
    uploadId: v3.uploadId,
    fileId: created.fileId,
    baseVersion: 1,
  });
  expect(stale.statusCode).toBe(409);
  const conflicts = await listConflicts();
  const conflictId = conflicts.body.conflicts[0].conflictId as string;
  return { conflictId, submittedContent };
}
