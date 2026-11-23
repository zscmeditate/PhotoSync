import { randomUUID } from 'node:crypto';
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
  UPLOAD_PART_SIZE: 8 * 1024 * 1024,
};
const jwt = new JwtService(JWT_SECRET, '15m');
const token = jwt.signAccessToken('user-1');
const otherToken = jwt.signAccessToken('user-2');
const auth = { authorization: `Bearer ${token}` };

let app: FastifyInstance;
let devices: FakeDeviceRepository;

beforeEach(async () => {
  devices = new FakeDeviceRepository();
  const spaces = new FakeSpaceRepository(devices);
  const transfers = new FakeTransferRepository();
  app = await buildApp({
    config,
    users: new FakeUserRepository(),
    devices,
    refreshTokens: new FakeRefreshTokenRepository(),
    spaces,
    transfers,
    sync: new FakeSyncRepository(spaces, transfers),
    objectStore: new FakeObjectStore(),
    logger: false,
  });
  await app.ready();
});

afterEach(async () => {
  await app.close();
});

async function addDevice(userId = 'user-1'): Promise<DeviceRecord> {
  const device = await devices.create({
    userId,
    deviceName: 'Phone',
    platform: 'android',
    credentialStatus: 'active',
    lastSeenAt: new Date(),
  });
  // 账号下第一台设备作为主设备（创建空间需要主设备身份）
  await devices.claimPrimaryDevice(userId, device.id);
  return device;
}

function errorCode(body: unknown): string {
  return (body as { error: { code: string } }).error.code;
}

describe('Phase 2 routes', () => {
  it('完成空间、成员、映射和扫描预览的 HTTP 工作流', async () => {
    const device = await addDevice();
    const second = await addDevice();

    const created = await app.inject({
      method: 'POST',
      url: '/api/v1/spaces',
      headers: auth,
      payload: { name: 'Photos', description: 'Family', deviceId: device.id },
    });
    expect(created.statusCode).toBe(201);
    const space = (created.json() as { space: Record<string, any> }).space;
    expect(space).toMatchObject({ revision: '0', role: 'admin', memberCount: 1, mappingCount: 0 });

    const list = await app.inject({ method: 'GET', url: '/api/v1/spaces', headers: auth });
    expect(list.statusCode).toBe(200);
    expect((list.json() as { spaces: unknown[] }).spaces).toHaveLength(1);

    const detail = await app.inject({
      method: 'GET',
      url: `/api/v1/spaces/${space.id}`,
      headers: auth,
    });
    expect(detail.statusCode).toBe(200);
    expect((detail.json() as { members: unknown[] }).members).toHaveLength(1);

    const existingMember = await app.inject({
      method: 'POST',
      url: `/api/v1/spaces/${space.id}/members`,
      headers: auth,
      payload: { deviceId: device.id },
    });
    expect(existingMember.statusCode).toBe(200);

    const newMember = await app.inject({
      method: 'POST',
      url: `/api/v1/spaces/${space.id}/members`,
      headers: auth,
      payload: { deviceId: second.id },
    });
    expect(newMember.statusCode).toBe(201);
    const repeatedMember = await app.inject({
      method: 'POST',
      url: `/api/v1/spaces/${space.id}/members`,
      headers: auth,
      payload: { deviceId: second.id },
    });
    expect(repeatedMember.statusCode).toBe(200);

    const renamed = await app.inject({
      method: 'PATCH',
      url: `/api/v1/spaces/${space.id}`,
      headers: auth,
      payload: { deviceId: device.id, name: 'Renamed' },
    });
    expect(renamed.statusCode).toBe(200);
    expect((renamed.json() as { space: { name: string } }).space.name).toBe('Renamed');

    const mapped = await app.inject({
      method: 'POST',
      url: `/api/v1/spaces/${space.id}/mappings`,
      headers: auth,
      payload: { deviceId: device.id, localPathLabel: 'DCIM' },
    });
    expect(mapped.statusCode).toBe(201);
    const mapping = (mapped.json() as { mapping: Record<string, any> }).mapping;
    expect(mapping).toMatchObject({
      localPathLabel: 'DCIM',
      onlyMedia: false,
    });

    const mappings = await app.inject({
      method: 'GET',
      url: `/api/v1/spaces/${space.id}/mappings?deviceId=${device.id}`,
      headers: auth,
    });
    expect(mappings.statusCode).toBe(200);
    expect((mappings.json() as { mappings: unknown[] }).mappings).toHaveLength(1);

    const duplicate = await app.inject({
      method: 'POST',
      url: `/api/v1/spaces/${space.id}/mappings`,
      headers: auth,
      payload: { deviceId: device.id, localPathLabel: 'Documents' },
    });
    expect(duplicate.statusCode).toBe(409);
    expect(errorCode(duplicate.json())).toBe('MAPPING_EXISTS');

    const remapped = await app.inject({
      method: 'PATCH',
      url: `/api/v1/mappings/${mapping.id}`,
      headers: auth,
      payload: { localPathLabel: 'Camera', onlyMedia: true },
    });
    expect(remapped.statusCode).toBe(200);
    expect((remapped.json() as { mapping: Record<string, any> }).mapping).toMatchObject({
      localPathLabel: 'Camera',
      onlyMedia: true,
    });

    const scanned = await app.inject({
      method: 'POST',
      url: `/api/v1/spaces/${space.id}/scan-manifest`,
      headers: auth,
      payload: {
        deviceId: device.id,
        mappingId: mapping.id,
        scanComplete: true,
        files: [{ relativePath: 'new.jpg', size: 10 }],
      },
    });
    expect(scanned.statusCode).toBe(200);
    expect((scanned.json() as { summary: Record<string, any> }).summary).toMatchObject({
      localCount: 1,
      remoteCount: 0,
      toUpload: 1,
      toDownload: 0,
      conflictCount: 0,
      diffComplete: true,
    });

    const removedMapping = await app.inject({
      method: 'DELETE',
      url: `/api/v1/mappings/${mapping.id}`,
      headers: auth,
    });
    expect(removedMapping.statusCode).toBe(204);
    const removedMember = await app.inject({
      method: 'DELETE',
      url: `/api/v1/spaces/${space.id}/members/${second.id}?operatorDeviceId=${device.id}`,
      headers: auth,
    });
    expect(removedMember.statusCode).toBe(204);
    const removedSpace = await app.inject({
      method: 'DELETE',
      url: `/api/v1/spaces/${space.id}?deviceId=${device.id}`,
      headers: auth,
    });
    expect(removedSpace.statusCode).toBe(204);
  });

  it('从设备申请加入空间，主设备查看并同意', async () => {
    const primary = await addDevice();
    const secondary = await addDevice();
    const created = await app.inject({
      method: 'POST',
      url: '/api/v1/spaces',
      headers: auth,
      payload: { name: 'Join', deviceId: primary.id },
    });
    const spaceId = (created.json() as { space: { id: string } }).space.id;

    const applied = await app.inject({
      method: 'POST',
      url: `/api/v1/spaces/${spaceId}/join-requests`,
      headers: auth,
      payload: { deviceId: secondary.id },
    });
    expect(applied.statusCode).toBe(201);
    const submitted = applied.json() as { request: Record<string, any>; joined: boolean };
    expect(submitted.joined).toBe(false);
    expect(submitted.request).toMatchObject({
      spaceId,
      deviceId: secondary.id,
      status: 'pending',
    });

    const notPrimary = await app.inject({
      method: 'GET',
      url: `/api/v1/spaces/${spaceId}/join-requests?deviceId=${secondary.id}`,
      headers: auth,
    });
    expect(notPrimary.statusCode).toBe(403);
    expect(errorCode(notPrimary.json())).toBe('DEVICE_NOT_PRIMARY');

    const pending = await app.inject({
      method: 'GET',
      url: `/api/v1/spaces/${spaceId}/join-requests?deviceId=${primary.id}`,
      headers: auth,
    });
    expect(pending.statusCode).toBe(200);
    const requests = (pending.json() as { requests: Array<Record<string, any>> }).requests;
    expect(requests).toHaveLength(1);
    expect(requests[0].deviceId).toBe(secondary.id);

    const approved = await app.inject({
      method: 'POST',
      url: `/api/v1/join-requests/${requests[0].id}/approve`,
      headers: auth,
      payload: { deviceId: primary.id },
    });
    expect(approved.statusCode).toBe(200);
    expect((approved.json() as { request: Record<string, any> }).request.status).toBe('approved');

    const detail = await app.inject({
      method: 'GET',
      url: `/api/v1/spaces/${spaceId}`,
      headers: auth,
    });
    const members = (detail.json() as { members: Array<{ deviceId: string }> }).members;
    expect(members.map((member) => member.deviceId)).toContain(secondary.id);

    const repeated = await app.inject({
      method: 'POST',
      url: `/api/v1/join-requests/${requests[0].id}/approve`,
      headers: auth,
      payload: { deviceId: primary.id },
    });
    expect(repeated.statusCode).toBe(409);
    expect(errorCode(repeated.json())).toBe('JOIN_REQUEST_RESOLVED');
  });

  it('非法空间 UUID 返回 SPACE_NOT_FOUND，非成员返回 SPACE_FORBIDDEN', async () => {
    const malformed = await app.inject({
      method: 'GET',
      url: '/api/v1/spaces/not-a-uuid',
      headers: auth,
    });
    expect(malformed.statusCode).toBe(404);
    expect(errorCode(malformed.json())).toBe('SPACE_NOT_FOUND');

    const device = await addDevice();
    const created = await app.inject({
      method: 'POST',
      url: '/api/v1/spaces',
      headers: auth,
      payload: { name: 'Private', deviceId: device.id },
    });
    const spaceId = (created.json() as { space: { id: string } }).space.id;
    const forbidden = await app.inject({
      method: 'GET',
      url: `/api/v1/spaces/${spaceId}`,
      headers: { authorization: `Bearer ${otherToken}` },
    });
    expect(forbidden.statusCode).toBe(403);
    expect(errorCode(forbidden.json())).toBe('SPACE_FORBIDDEN');
  });

  it('校验设备归属和扫描文件数量', async () => {
    const invalidDevice = await app.inject({
      method: 'POST',
      url: '/api/v1/spaces',
      headers: auth,
      payload: { name: 'Photos', deviceId: randomUUID() },
    });
    expect(invalidDevice.statusCode).toBe(400);
    expect(errorCode(invalidDevice.json())).toBe('INVALID_DEVICE');

    const device = await addDevice();
    const created = await app.inject({
      method: 'POST',
      url: '/api/v1/spaces',
      headers: auth,
      payload: { name: 'Photos', deviceId: device.id },
    });
    const spaceId = (created.json() as { space: { id: string } }).space.id;
    const emptyLabel = await app.inject({
      method: 'POST',
      url: `/api/v1/spaces/${spaceId}/mappings`,
      headers: auth,
      payload: { deviceId: device.id, localPathLabel: '' },
    });
    expect(emptyLabel.statusCode).toBe(400);
    expect(errorCode(emptyLabel.json())).toBe('VALIDATION_ERROR');

    const tooManyFiles = await app.inject({
      method: 'POST',
      url: `/api/v1/spaces/${spaceId}/scan-manifest`,
      headers: auth,
      payload: {
        deviceId: device.id,
        mappingId: randomUUID(),
        scanComplete: true,
        files: Array.from({ length: 10001 }, () => ({ relativePath: 'a.jpg', size: 1 })),
      },
    });
    expect(tooManyFiles.statusCode).toBe(400);
    expect(errorCode(tooManyFiles.json())).toBe('VALIDATION_ERROR');

    const malformedMapping = await app.inject({
      method: 'PATCH',
      url: '/api/v1/mappings/not-a-uuid',
      headers: auth,
      payload: { onlyMedia: true },
    });
    expect(malformedMapping.statusCode).toBe(404);
    expect(errorCode(malformedMapping.json())).toBe('MAPPING_NOT_FOUND');
  });

  it('Swagger 描述和路径已更新到 Phase 2', () => {
    const document = app.swagger();
    expect(document.info.description).toContain('Phase 2');
    expect(document.paths).toHaveProperty('/api/v1/spaces');
    expect(document.paths).toHaveProperty('/api/v1/spaces/{spaceId}/scan-manifest');
    expect(document.paths).toHaveProperty('/api/v1/mappings/{mappingId}');
  });
});
