import { createHash } from 'node:crypto';
import type { FastifyInstance } from 'fastify';
import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import { buildApp } from '../src/app';
import type { Config } from '../src/config';
import { JwtService } from '../src/lib/jwt';
import type { DeviceRecord } from '../src/repositories/ports';
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
const otherAuth = { authorization: `Bearer ${jwt.signAccessToken('user-2')}` };

let app: FastifyInstance;
let devices: FakeDeviceRepository;
let spaces: FakeSpaceRepository;
let transfers: FakeTransferRepository;
let sync: FakeSyncRepository;
let objectStore: FakeObjectStore;
let deviceA: DeviceRecord;
let deviceB: DeviceRecord;
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
  const device = await devices.create({
    userId,
    deviceName,
    platform: 'android',
    credentialStatus: 'active',
    lastSeenAt: new Date(),
  });
  // 账号下第一台设备作为主设备（创建空间需要主设备身份）
  await devices.claimPrimaryDevice(userId, device.id);
  return device;
}

function sha256(content: Buffer): string {
  return createHash('sha256').update(content).digest('hex');
}

function errorCode(body: unknown): string {
  return (body as { error: { code: string } }).error.code;
}

async function createUpload(
  relativePath: string,
  content: Buffer,
  idempotencyKey: string,
  expectedSha256 = sha256(content),
) {
  return app.inject({
    method: 'POST',
    url: '/api/v1/uploads',
    headers: auth,
    payload: {
      spaceId,
      deviceId: deviceA.id,
      relativePath,
      size: content.byteLength,
      sha256: expectedSha256,
      partSize: 0,
      idempotencyKey,
    },
  });
}

async function putPart(uploadId: string, partNumber: number, content: Buffer, key?: string) {
  return app.inject({
    method: 'PUT',
    url: `/api/v1/uploads/${uploadId}/parts/${partNumber}?deviceId=${deviceA.id}`,
    headers: {
      ...auth,
      'content-type': 'application/octet-stream',
      'idempotency-key': key ?? `part-${partNumber}`,
    },
    payload: content,
  });
}

async function completeUpload(uploadId: string, key = 'complete-key') {
  return app.inject({
    method: 'POST',
    url: `/api/v1/uploads/${uploadId}/complete`,
    headers: auth,
    payload: { deviceId: deviceA.id, idempotencyKey: key },
  });
}

async function uploadAll(relativePath: string, content: Buffer, key: string) {
  const created = await createUpload(relativePath, content, key);
  expect(created.statusCode).toBe(201);
  const upload = (created.json() as { upload: Record<string, any> }).upload;
  expect(upload).toMatchObject({
    spaceId,
    relativePath,
    size: String(content.byteLength),
    sha256: sha256(content),
    partSize: String(PART_SIZE),
    status: 'pending',
    uploadedParts: [],
  });
  for (let offset = 0, partNumber = 1; offset < content.byteLength; offset += PART_SIZE, partNumber += 1) {
    const part = await putPart(upload.uploadId, partNumber, content.subarray(offset, offset + PART_SIZE));
    expect(part.statusCode).toBe(200);
  }
  const completed = await completeUpload(upload.uploadId);
  expect(completed.statusCode).toBe(200);
  return (completed.json() as { upload: Record<string, any> }).upload;
}

async function commitOperation(uploadId: string, operationId: string, deviceId = deviceA.id) {
  return app.inject({
    method: 'POST',
    url: `/api/v1/spaces/${spaceId}/operations`,
    headers: auth,
    payload: { deviceId, operationId, type: 'file_created', uploadId, baseVersion: 0 },
  });
}

describe('Phase 3 新增文件同步 HTTP 流程', () => {
  it('A 分片上传并提交后，B 拉取事件、ACK、下载并查询 v1 版本', async () => {
    const content = Buffer.from('hello world');
    const upload = await uploadAll('Camera/a.jpg', content, 'upload-a');
    expect(upload.status).toBe('completed');
    expect(upload.uploadedParts).toHaveLength(3);

    const operation = await commitOperation(upload.uploadId, 'operation-a');
    expect(operation.statusCode).toBe(201);
    const committed = operation.json() as { file: Record<string, any>; event: Record<string, any>; duplicate: boolean };
    expect(committed.duplicate).toBe(false);
    expect(committed.file).toMatchObject({
      spaceId,
      relativePath: 'Camera/a.jpg',
      fileName: 'a.jpg',
      objectId: upload.objectId,
      size: '11',
      sha256: sha256(content),
      version: 1,
      lastModifiedByDeviceId: deviceA.id,
    });
    expect(committed.event).toMatchObject({ revision: '1', type: 'file_created', deviceId: deviceA.id });
    expect(committed.event.payload).toMatchObject({ fileId: committed.file.fileId, size: '11', version: 1 });

    const changes = await app.inject({
      method: 'GET',
      url: `/api/v1/spaces/${spaceId}/changes?deviceId=${deviceB.id}&sinceRevision=0&limit=100`,
      headers: auth,
    });
    expect(changes.statusCode).toBe(200);
    expect(changes.json()).toMatchObject({ nextRevision: '1', hasMore: false, serverRevision: '1' });
    expect((changes.json() as { events: unknown[] }).events).toHaveLength(1);

    const ack = await app.inject({
      method: 'POST',
      url: `/api/v1/spaces/${spaceId}/ack`,
      headers: auth,
      payload: { deviceId: deviceB.id, lastAppliedRevision: '1' },
    });
    expect(ack.statusCode).toBe(200);
    expect(ack.json()).toEqual({ spaceId, lastAppliedRevision: '1', serverRevision: '1' });

    const download = await app.inject({
      method: 'GET',
      url: `/api/v1/objects/${upload.objectId}/download?deviceId=${deviceB.id}`,
      headers: auth,
    });
    expect(download.statusCode).toBe(200);
    expect(download.rawPayload).toEqual(content);
    expect(download.headers['content-type']).toBe('application/octet-stream');
    expect(download.headers['content-length']).toBe('11');
    expect(download.headers['x-sha256']).toBe(sha256(content));
    expect(download.headers['content-disposition']).toContain('a.jpg');

    const versions = await app.inject({
      method: 'GET',
      url: `/api/v1/files/${committed.file.fileId}/versions?deviceId=${deviceB.id}`,
      headers: auth,
    });
    expect(versions.statusCode).toBe(200);
    expect((versions.json() as { versions: Array<Record<string, any>> }).versions).toEqual([
      expect.objectContaining({
        fileId: committed.file.fileId,
        version: 1,
        objectId: upload.objectId,
        size: '11',
        sha256: sha256(content),
        createdByDeviceId: deviceA.id,
      }),
    ]);
    const status = await app.inject({
      method: 'GET',
      url: `/api/v1/spaces/${spaceId}/status?deviceId=${deviceB.id}`,
      headers: auth,
    });
    expect(status.json()).toEqual({
      spaceId,
      serverRevision: '1',
      lastAppliedRevision: '1',
      pendingEvents: 0,
      activeFileCount: 1,
    });

    // 元数据在但对象已被存储层清理：返回可识别的 404，客户端据此跳过该事件并保留失败任务。
    objectStore.objects.delete(upload.objectId);
    const missingObject = await app.inject({
      method: 'GET',
      url: `/api/v1/objects/${upload.objectId}/download?deviceId=${deviceB.id}`,
      headers: auth,
    });
    expect(missingObject.statusCode).toBe(404);
    expect(errorCode(missingObject.json())).toBe('OBJECT_NOT_FOUND');
  });

  it('下载支持 HTTP Range 断点续传（206 + Content-Range），非法区间返回 416', async () => {
    const content = Buffer.from('hello world');
    const upload = await uploadAll('Camera/range.bin', content, 'upload-range');
    const operation = await commitOperation(upload.uploadId, 'operation-range');
    expect(operation.statusCode).toBe(201);

    // 无 Range 头：完整 200
    const full = await app.inject({
      method: 'GET',
      url: `/api/v1/objects/${upload.objectId}/download?deviceId=${deviceB.id}`,
      headers: auth,
    });
    expect(full.statusCode).toBe(200);
    expect(full.headers['accept-ranges']).toBe('bytes');
    expect(full.rawPayload).toEqual(content);

    // 从第 6 字节续传：206 + 剩余内容
    const partial = await app.inject({
      method: 'GET',
      url: `/api/v1/objects/${upload.objectId}/download?deviceId=${deviceB.id}`,
      headers: { ...auth, range: 'bytes=6-' },
    });
    expect(partial.statusCode).toBe(206);
    expect(partial.headers['content-range']).toBe(`bytes 6-10/${content.byteLength}`);
    expect(partial.headers['content-length']).toBe(String(content.byteLength - 6));
    expect(partial.rawPayload).toEqual(content.subarray(6));

    // 起始字节超出对象长度：416
    const invalid = await app.inject({
      method: 'GET',
      url: `/api/v1/objects/${upload.objectId}/download?deviceId=${deviceB.id}`,
      headers: { ...auth, range: 'bytes=999-' },
    });
    expect(invalid.statusCode).toBe(416);

    // 非法 Range 格式：忽略并返回完整内容
    const malformed = await app.inject({
      method: 'GET',
      url: `/api/v1/objects/${upload.objectId}/download?deviceId=${deviceB.id}`,
      headers: { ...auth, range: 'bytes=abc-' },
    });
    expect(malformed.statusCode).toBe(200);
    expect(malformed.rawPayload).toEqual(content);
  });

  it('支持断点查询、分片与 complete 重放，并拒绝同分片不同内容', async () => {
    const content = Buffer.from('abcdefgh');
    const created = await createUpload('resume.bin', content, 'resume-upload');
    const upload = (created.json() as { upload: Record<string, any> }).upload;
    expect((await putPart(upload.uploadId, 1, content.subarray(0, 4))).statusCode).toBe(200);
    expect(objectStore.uploadPartCalls).toHaveLength(1);

    const resumed = await app.inject({
      method: 'GET',
      url: `/api/v1/uploads/${upload.uploadId}?deviceId=${deviceA.id}`,
      headers: auth,
    });
    expect(resumed.statusCode).toBe(200);
    expect((resumed.json() as { upload: Record<string, any> }).upload.uploadedParts).toEqual([
      { partNumber: 1, size: '4', sha256: sha256(content.subarray(0, 4)) },
    ]);
    expect((await putPart(upload.uploadId, 1, content.subarray(0, 4))).statusCode).toBe(200);
    expect(objectStore.uploadPartCalls).toHaveLength(1);
    const conflict = await putPart(upload.uploadId, 1, Buffer.from('WXYZ'));
    expect(conflict.statusCode).toBe(409);
    expect(errorCode(conflict.json())).toBe('PART_CONFLICT');

    expect((await putPart(upload.uploadId, 2, content.subarray(4))).statusCode).toBe(200);
    expect((await completeUpload(upload.uploadId)).statusCode).toBe(200);
    expect((await completeUpload(upload.uploadId, 'another-complete-key')).statusCode).toBe(200);
    expect(objectStore.completeCalls).toHaveLength(1);

    const replay = await createUpload('resume.bin', content, 'resume-upload');
    expect(replay.statusCode).toBe(200);
    expect((replay.json() as { upload: { uploadId: string } }).upload.uploadId).toBe(upload.uploadId);
    const different = await createUpload('different.bin', content, 'resume-upload');
    expect(different.statusCode).toBe(409);
    expect(errorCode(different.json())).toBe('IDEMPOTENCY_CONFLICT');
  });

  it('严格校验分片长度，最终 hash 不符返回 422、删除对象并中止会话', async () => {
    const content = Buffer.from('abcd');
    const wrongSize = await createUpload('size.bin', content, 'size-upload');
    const sizeUpload = (wrongSize.json() as { upload: Record<string, any> }).upload;
    const badPart = await putPart(sizeUpload.uploadId, 1, Buffer.from('abc'));
    expect(badPart.statusCode).toBe(400);
    expect(errorCode(badPart.json())).toBe('VALIDATION_ERROR');

    const mismatch = await createUpload('hash.bin', content, 'hash-upload', '0'.repeat(64));
    const hashUpload = (mismatch.json() as { upload: Record<string, any> }).upload;
    expect((await putPart(hashUpload.uploadId, 1, content)).statusCode).toBe(200);
    const completed = await completeUpload(hashUpload.uploadId);
    expect(completed.statusCode).toBe(422);
    expect(errorCode(completed.json())).toBe('UPLOAD_INTEGRITY_MISMATCH');
    expect(objectStore.objects.has(hashUpload.objectId)).toBe(false);
    expect(transfers.sessions.get(hashUpload.uploadId)?.status).toBe('aborted');
  });

  it('operation 重放不增长 revision，路径冲突不覆盖原文件', async () => {
    const firstContent = Buffer.from('first');
    const first = await uploadAll('same.txt', firstContent, 'first-upload');
    expect((await commitOperation(first.uploadId, 'first-operation')).statusCode).toBe(201);
    const replay = await commitOperation(first.uploadId, 'first-operation');
    expect(replay.statusCode).toBe(200);
    expect((replay.json() as { duplicate: boolean }).duplicate).toBe(true);
    expect(spaces.spaces.get(spaceId)?.revision).toBe(1n);
    expect(sync.events.size).toBe(1);

    const second = await uploadAll('same.txt', Buffer.from('second'), 'second-upload');
    const conflict = await commitOperation(second.uploadId, 'second-operation');
    expect(conflict.statusCode).toBe(409);
    expect(errorCode(conflict.json())).toBe('FILE_ALREADY_EXISTS');
    expect(spaces.spaces.get(spaceId)?.revision).toBe(1n);
    expect(sync.events.size).toBe(1);
    const original = [...spaces.files.values()].find((file) => file.relativePath === 'same.txt');
    expect(original?.sha256).toBe(sha256(firstContent));

    const unsupported = await app.inject({
      method: 'POST',
      url: `/api/v1/spaces/${spaceId}/operations`,
      headers: auth,
      payload: {
        deviceId: deviceA.id,
        operationId: 'unsupported',
        type: 'file_restored',
        baseVersion: 0,
      },
    });
    expect(unsupported.statusCode).toBe(400);
    expect(errorCode(unsupported.json())).toBe('OPERATION_UNSUPPORTED');
  });

  it('changes 使用 limit+1 分页且 revision 严格升序，ACK 不回退也不越过服务端', async () => {
    for (const [index, name] of ['one.txt', 'two.txt', 'three.txt'].entries()) {
      const upload = await uploadAll(name, Buffer.from(name), `upload-${index}`);
      expect((await commitOperation(upload.uploadId, `operation-${index}`)).statusCode).toBe(201);
    }
    const first = await app.inject({
      method: 'GET',
      url: `/api/v1/spaces/${spaceId}/changes?deviceId=${deviceB.id}&sinceRevision=0&limit=2`,
      headers: auth,
    });
    expect(first.json()).toMatchObject({ nextRevision: '2', hasMore: true, serverRevision: '3' });
    expect((first.json() as { events: Array<{ revision: string }> }).events.map((event) => event.revision)).toEqual(['1', '2']);
    const second = await app.inject({
      method: 'GET',
      url: `/api/v1/spaces/${spaceId}/changes?deviceId=${deviceB.id}&sinceRevision=2&limit=2`,
      headers: auth,
    });
    expect(second.json()).toMatchObject({ nextRevision: '3', hasMore: false, serverRevision: '3' });
    expect((second.json() as { events: Array<{ revision: string }> }).events.map((event) => event.revision)).toEqual(['3']);

    await app.inject({
      method: 'POST',
      url: `/api/v1/spaces/${spaceId}/ack`,
      headers: auth,
      payload: { deviceId: deviceB.id, lastAppliedRevision: '2' },
    });
    const backwards = await app.inject({
      method: 'POST',
      url: `/api/v1/spaces/${spaceId}/ack`,
      headers: auth,
      payload: { deviceId: deviceB.id, lastAppliedRevision: '1' },
    });
    expect(backwards.json()).toMatchObject({ lastAppliedRevision: '2', serverRevision: '3' });
    const ahead = await app.inject({
      method: 'POST',
      url: `/api/v1/spaces/${spaceId}/ack`,
      headers: auth,
      payload: { deviceId: deviceB.id, lastAppliedRevision: '4' },
    });
    expect(ahead.statusCode).toBe(400);
    expect(errorCode(ahead.json())).toBe('VALIDATION_ERROR');
  });

  it('拒绝无权限设备，并对非法对象和文件 UUID 返回 404', async () => {
    const upload = await uploadAll('private.txt', Buffer.from('private'), 'private-upload');
    const operation = await commitOperation(upload.uploadId, 'private-operation');
    const fileId = (operation.json() as { file: { fileId: string } }).file.fileId;
    const otherDevice = await addDevice('user-2', 'Other');

    const changes = await app.inject({
      method: 'GET',
      url: `/api/v1/spaces/${spaceId}/changes?deviceId=${otherDevice.id}`,
      headers: otherAuth,
    });
    expect(changes.statusCode).toBe(409);
    expect(errorCode(changes.json())).toBe('NOT_MEMBER');
    const download = await app.inject({
      method: 'GET',
      url: `/api/v1/objects/${upload.objectId}/download?deviceId=${otherDevice.id}`,
      headers: otherAuth,
    });
    expect(download.statusCode).toBe(409);
    expect(errorCode(download.json())).toBe('NOT_MEMBER');

    const nonMember = await addDevice('user-1', 'Non member');
    const forbiddenVersions = await app.inject({
      method: 'GET',
      url: `/api/v1/files/${fileId}/versions?deviceId=${nonMember.id}`,
      headers: auth,
    });
    expect(forbiddenVersions.statusCode).toBe(409);
    expect(errorCode(forbiddenVersions.json())).toBe('NOT_MEMBER');
    const malformedObject = await app.inject({
      method: 'GET',
      url: `/api/v1/objects/not-a-uuid/download?deviceId=${deviceA.id}`,
      headers: auth,
    });
    expect(malformedObject.statusCode).toBe(404);
    expect(errorCode(malformedObject.json())).toBe('OBJECT_NOT_FOUND');
    const malformedFile = await app.inject({
      method: 'GET',
      url: `/api/v1/files/not-a-uuid/versions?deviceId=${deviceA.id}`,
      headers: auth,
    });
    expect(malformedFile.statusCode).toBe(404);
    expect(errorCode(malformedFile.json())).toBe('FILE_NOT_FOUND');
  });
});
