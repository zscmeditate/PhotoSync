import { createHash } from 'node:crypto';
import type { AddressInfo } from 'node:net';
import type { FastifyInstance } from 'fastify';
import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import WebSocket from 'ws';
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
let deviceC: DeviceRecord;
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
  deviceC = await addDevice('user-2', 'C');
  deviceD = await addDevice('user-1', 'D');
  const created = await app.inject({
    method: 'POST',
    url: '/api/v1/spaces',
    headers: auth,
    payload: { name: 'Photos', deviceId: deviceA.id },
  });
  spaceId = (created.json() as { space: { id: string } }).space.id;
  for (const device of [deviceB]) {
    const joined = await app.inject({
      method: 'POST',
      url: `/api/v1/spaces/${spaceId}/members`,
      headers: auth,
      payload: { deviceId: device.id },
    });
    expect(joined.statusCode).toBe(201);
  }
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

async function createUpload(relativePath: string, content: Buffer, key: string) {
  return app.inject({
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
  const upload = (created.json() as { upload: Record<string, unknown> }).upload;
  for (let offset = 0, partNumber = 1; offset < content.byteLength; offset += PART_SIZE, partNumber += 1) {
    const part = await putPart(upload.uploadId as string, partNumber, content.subarray(offset, offset + PART_SIZE));
    expect(part.statusCode).toBe(200);
  }
  const completed = await completeUpload(upload.uploadId as string, `${key}-complete`);
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
  return { upload, fileId: body.file.fileId as string, revision: body.event.revision as string };
}

function nextMessage(ws: WebSocket, timeoutMs = 2000): Promise<string> {
  return new Promise((resolve, reject) => {
    const timer = setTimeout(() => reject(new Error('ws message timeout')), timeoutMs);
    ws.once('message', (data) => {
      clearTimeout(timer);
      resolve(data.toString());
    });
    ws.once('error', (err) => {
      clearTimeout(timer);
      reject(err);
    });
  });
}

describe('Phase 4 修改/删除/重命名 HTTP 流程', () => {
  it('修改文件产生 v2 与 file_updated 事件，B 可下载并查询两个版本', async () => {
    const v1 = Buffer.from('hello world');
    const v2 = Buffer.from('hello world updated');
    const created = await createFile('Camera/a.jpg', v1, 'upload-a');

    const upload2 = await uploadAll('Camera/a.jpg', v2, 'upload-a-v2');
    const updated = await commitOp({
      deviceId: deviceA.id,
      operationId: 'update-a',
      type: 'file_updated',
      uploadId: upload2.uploadId,
      fileId: created.fileId,
      baseVersion: 1,
    });
    expect(updated.statusCode).toBe(201);
    const body = updated.json() as { file: Record<string, any>; event: Record<string, any>; duplicate: boolean };
    expect(body.duplicate).toBe(false);
    expect(body.file).toMatchObject({
      relativePath: 'Camera/a.jpg',
      objectId: upload2.objectId,
      size: String(v2.byteLength),
      sha256: sha256(v2),
      version: 2,
    });
    expect(body.event).toMatchObject({ revision: '2', type: 'file_updated' });
    expect(body.event.payload).toMatchObject({ baseVersion: 1, version: 2, size: String(v2.byteLength) });

    const changes = await app.inject({
      method: 'GET',
      url: `/api/v1/spaces/${spaceId}/changes?deviceId=${deviceB.id}&sinceRevision=0&limit=100`,
      headers: auth,
    });
    const events = (changes.json() as { events: Array<Record<string, any>> }).events;
    expect(events.map((e) => e.type)).toEqual(['file_created', 'file_updated']);

    const download = await app.inject({
      method: 'GET',
      url: `/api/v1/objects/${upload2.objectId}/download?deviceId=${deviceB.id}`,
      headers: auth,
    });
    expect(download.rawPayload).toEqual(v2);

    const versions = await app.inject({
      method: 'GET',
      url: `/api/v1/files/${created.fileId}/versions?deviceId=${deviceB.id}`,
      headers: auth,
    });
    expect((versions.json() as { versions: unknown[] }).versions).toHaveLength(2);
  });

  it('重命名产生 file_renamed 事件且 fileId 稳定，目标路径冲突返回 PATH_CONFLICT', async () => {
    const created = await createFile('Camera/a.jpg', Buffer.from('a'), 'upload-a');
    await createFile('Camera/b.jpg', Buffer.from('b'), 'upload-b');

    const renamed = await commitOp({
      deviceId: deviceA.id,
      operationId: 'rename-a',
      type: 'file_renamed',
      fileId: created.fileId,
      baseVersion: 1,
      newRelativePath: 'Camera/renamed.jpg',
    });
    expect(renamed.statusCode).toBe(201);
    const body = renamed.json() as { file: Record<string, any>; event: Record<string, any> };
    expect(body.file).toMatchObject({ fileId: created.fileId, relativePath: 'Camera/renamed.jpg', fileName: 'renamed.jpg' });
    expect(body.event).toMatchObject({ revision: '3', type: 'file_renamed' });
    expect(body.event.payload).toMatchObject({
      fileId: created.fileId,
      oldPath: 'Camera/a.jpg',
      newPath: 'Camera/renamed.jpg',
      oldFileName: 'a.jpg',
      newFileName: 'renamed.jpg',
      baseVersion: 1,
    });

    // 目标路径已被活动文件占用
    const conflict = await commitOp({
      deviceId: deviceA.id,
      operationId: 'rename-to-b',
      type: 'file_renamed',
      fileId: created.fileId,
      baseVersion: 1,
      newRelativePath: 'Camera/b.jpg',
    });
    expect(conflict.statusCode).toBe(409);
    expect(errorCode(conflict.json())).toBe('PATH_CONFLICT');
  });

  it('删除产生 file_deleted 事件，已删文件拒绝修改，原路径可重新创建', async () => {
    const created = await createFile('Camera/a.jpg', Buffer.from('a'), 'upload-a');

    const deleted = await commitOp({
      deviceId: deviceA.id,
      operationId: 'delete-a',
      type: 'file_deleted',
      fileId: created.fileId,
      baseVersion: 1,
    });
    expect(deleted.statusCode).toBe(201);
    const body = deleted.json() as { file: Record<string, any>; event: Record<string, any> };
    expect(body.file.isDeleted).toBe(true);
    expect(body.event).toMatchObject({ revision: '2', type: 'file_deleted' });
    expect(body.event.payload).toMatchObject({ fileId: created.fileId, relativePath: 'Camera/a.jpg', baseVersion: 1 });
    expect(body.event.payload).not.toHaveProperty('objectId');

    // 对已删除文件修改 → FILE_IS_DELETED
    const upload2 = await uploadAll('Camera/a.jpg', Buffer.from('a2'), 'upload-a2');
    const modifyDeleted = await commitOp({
      deviceId: deviceA.id,
      operationId: 'update-deleted',
      type: 'file_updated',
      uploadId: upload2.uploadId,
      fileId: created.fileId,
      baseVersion: 1,
    });
    expect(modifyDeleted.statusCode).toBe(409);
    expect(errorCode(modifyDeleted.json())).toBe('FILE_IS_DELETED');

    // 删除后原路径可重新创建（部分唯一索引允许已删路径复用）
    const recreated = await commitOp({
      deviceId: deviceA.id,
      operationId: 'recreate-a',
      type: 'file_created',
      uploadId: upload2.uploadId,
      baseVersion: 0,
    });
    expect(recreated.statusCode).toBe(201);
    const recreatedFile = (recreated.json() as { file: Record<string, any> }).file;
    expect(recreatedFile.relativePath).toBe('Camera/a.jpg');
    expect(recreatedFile.isDeleted).toBe(false);
    expect(recreatedFile.fileId).not.toBe(created.fileId);
  });

  it('baseVersion 不匹配返回 VERSION_CONFLICT 且 revision 不增长', async () => {
    const created = await createFile('Camera/a.jpg', Buffer.from('a'), 'upload-a');
    const upload2 = await uploadAll('Camera/a.jpg', Buffer.from('a2'), 'upload-a-v2');

    const first = await commitOp({
      deviceId: deviceA.id,
      operationId: 'update-first',
      type: 'file_updated',
      uploadId: upload2.uploadId,
      fileId: created.fileId,
      baseVersion: 1,
    });
    expect(first.statusCode).toBe(201);
    expect((first.json() as { event: { revision: string } }).event.revision).toBe('2');

    // 用过期的 baseVersion=1 再次修改 → 冲突
    const upload3 = await uploadAll('Camera/a.jpg', Buffer.from('a3'), 'upload-a-v3');
    const conflict = await commitOp({
      deviceId: deviceA.id,
      operationId: 'update-stale',
      type: 'file_updated',
      uploadId: upload3.uploadId,
      fileId: created.fileId,
      baseVersion: 1,
    });
    expect(conflict.statusCode).toBe(409);
    expect(errorCode(conflict.json())).toBe('VERSION_CONFLICT');

    const status = await app.inject({
      method: 'GET',
      url: `/api/v1/spaces/${spaceId}/status?deviceId=${deviceB.id}`,
      headers: auth,
    });
    expect((status.json() as { serverRevision: string }).serverRevision).toBe('2');
  });

  it('操作重放幂等不增长 revision', async () => {
    const created = await createFile('Camera/a.jpg', Buffer.from('a'), 'upload-a');

    const payload = {
      deviceId: deviceA.id,
      operationId: 'rename-once',
      type: 'file_renamed' as const,
      fileId: created.fileId,
      baseVersion: 1,
      newRelativePath: 'Camera/b.jpg',
    };
    const first = await commitOp(payload);
    expect(first.statusCode).toBe(201);
    const replay = await commitOp(payload);
    expect(replay.statusCode).toBe(200);
    expect((replay.json() as { duplicate: boolean }).duplicate).toBe(true);

    const status = await app.inject({
      method: 'GET',
      url: `/api/v1/spaces/${spaceId}/status?deviceId=${deviceB.id}`,
      headers: auth,
    });
    expect((status.json() as { serverRevision: string }).serverRevision).toBe('2');
  });

  it('非成员设备的操作被拒绝', async () => {
    const created = await createFile('Camera/a.jpg', Buffer.from('a'), 'upload-a');

    // deviceD 属于 user-1 但未加入该空间
    const forbidden = await commitOp({
      deviceId: deviceD.id,
      operationId: 'delete-by-d',
      type: 'file_deleted',
      fileId: created.fileId,
      baseVersion: 1,
    });
    expect(forbidden.statusCode).toBe(409);
    expect(errorCode(forbidden.json())).toBe('NOT_MEMBER');

    // 其他用户的设备与 token 操作 user-1 的空间
    const otherUser = await app.inject({
      method: 'POST',
      url: `/api/v1/spaces/${spaceId}/operations`,
      headers: otherAuth,
      payload: {
        deviceId: deviceC.id,
        operationId: 'delete-by-other',
        type: 'file_deleted',
        fileId: created.fileId,
        baseVersion: 1,
      },
    });
    expect(otherUser.statusCode).toBe(409);
    expect(errorCode(otherUser.json())).toBe('NOT_MEMBER');
  });
});

describe('Phase 4 WebSocket 实时提示', () => {
  it('订阅空间后 commit 会收到 revision 提示', async () => {
    await app.listen({ port: 0, host: '127.0.0.1' });
    const port = (app.server.address() as AddressInfo).port;
    const token = jwt.signAccessToken('user-1');
    const ws = new WebSocket(`ws://127.0.0.1:${port}/api/v1/ws?token=${token}`);
    await new Promise<void>((resolve, reject) => {
      ws.once('open', () => resolve());
      ws.once('error', reject);
    });

    ws.send(JSON.stringify({ type: 'subscribe', spaceId, deviceId: deviceA.id }));
    // 给订阅消息一点处理时间
    await new Promise((r) => setTimeout(r, 50));

    const message = nextMessage(ws);
    await createFile('Camera/a.jpg', Buffer.from('a'), 'upload-a');
    const parsed = JSON.parse(await message);
    expect(parsed).toMatchObject({ type: 'revision', spaceId, revision: '1' });

    ws.close();
  });

  it('非成员设备订阅会被关闭，未订阅空间不推送', async () => {
    await app.listen({ port: 0, host: '127.0.0.1' });
    const port = (app.server.address() as AddressInfo).port;
    const token = jwt.signAccessToken('user-1');

    // deviceC 不是成员，订阅应被关闭
    const wsC = new WebSocket(`ws://127.0.0.1:${port}/api/v1/ws?token=${token}`);
    await new Promise<void>((resolve, reject) => {
      wsC.once('open', () => resolve());
      wsC.once('error', reject);
    });
    wsC.send(JSON.stringify({ type: 'subscribe', spaceId, deviceId: deviceC.id }));
    await new Promise<void>((resolve) => wsC.once('close', () => resolve()));
    expect(wsC.readyState).toBe(WebSocket.CLOSED);

    // deviceA 订阅，deviceC 的关闭不影响
    const wsA = new WebSocket(`ws://127.0.0.1:${port}/api/v1/ws?token=${token}`);
    await new Promise<void>((resolve, reject) => {
      wsA.once('open', () => resolve());
      wsA.once('error', reject);
    });
    wsA.send(JSON.stringify({ type: 'subscribe', spaceId, deviceId: deviceA.id }));
    await new Promise((r) => setTimeout(r, 50));
    const message = nextMessage(wsA);
    await createFile('Camera/b.jpg', Buffer.from('b'), 'upload-b');
    const parsed = JSON.parse(await message);
    expect(parsed).toMatchObject({ type: 'revision', spaceId, revision: '1' });
    wsA.close();
  });
});
