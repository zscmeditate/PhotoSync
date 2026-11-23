import type { FastifyInstance } from 'fastify';
import { afterAll, beforeAll, describe, expect, it } from 'vitest';
import { buildApp } from '../src/app';
import type { Config } from '../src/config';
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

const testConfig: Config = {
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

let app: FastifyInstance;
let users: FakeUserRepository;
let devices: FakeDeviceRepository;
let refreshTokens: FakeRefreshTokenRepository;
let spaces: FakeSpaceRepository;

beforeAll(async () => {
  users = new FakeUserRepository();
  devices = new FakeDeviceRepository();
  refreshTokens = new FakeRefreshTokenRepository();
  spaces = new FakeSpaceRepository(devices);
  const transfers = new FakeTransferRepository();
  app = await buildApp({
    config: testConfig,
    users,
    devices,
    refreshTokens,
    spaces,
    transfers,
    sync: new FakeSyncRepository(spaces, transfers),
    objectStore: new FakeObjectStore(),
    logger: false,
  });
  await app.ready();
});

afterAll(async () => {
  await app.close();
});

function errorOf(body: unknown): { code: string; message: string } {
  return (body as { error: { code: string; message: string } }).error;
}

async function registerUser(email: string, displayName: string) {
  const res = await app.inject({
    method: 'POST',
    url: '/api/v1/auth/register',
    payload: { email, password: 'password123', displayName },
  });
  expect(res.statusCode).toBe(201);
  return res.json() as {
    user: { id: string; email: string; displayName: string };
    accessToken: string;
    refreshToken: string;
  };
}

function authHeader(accessToken: string) {
  return { authorization: `Bearer ${accessToken}` };
}

describe('GET /api/v1/health', () => {
  it('returns the contract shape without auth', async () => {
    const res = await app.inject({ method: 'GET', url: '/api/v1/health' });
    expect(res.statusCode).toBe(200);
    const body = res.json() as Record<string, unknown>;
    expect(body.status).toBe('ok');
    expect(body.version).toBe('0.1.0');
    expect(body.apiVersion).toBe('v1');
    expect(new Date(body.serverTime as string).toISOString()).toBe(body.serverTime);
  });
});

describe('POST /api/v1/auth/register', () => {
  it('registers and returns user + tokens', async () => {
    const res = await app.inject({
      method: 'POST',
      url: '/api/v1/auth/register',
      payload: { email: 'alice@example.com', password: 'password123', displayName: 'Alice' },
    });
    expect(res.statusCode).toBe(201);
    const body = res.json() as Record<string, any>;
    expect(body.user).toEqual({
      id: expect.any(String),
      email: 'alice@example.com',
      displayName: 'Alice',
    });
    expect(body.accessToken).toBeTypeOf('string');
    expect(body.refreshToken).toBeTypeOf('string');
  });

  it('returns 409 EMAIL_TAKEN for duplicate email', async () => {
    await registerUser('dup@example.com', 'Dup');
    const res = await app.inject({
      method: 'POST',
      url: '/api/v1/auth/register',
      payload: { email: 'dup@example.com', password: 'password123', displayName: 'Dup2' },
    });
    expect(res.statusCode).toBe(409);
    expect(errorOf(res.json()).code).toBe('EMAIL_TAKEN');
  });

  it('returns 400 VALIDATION_ERROR for a short password', async () => {
    const res = await app.inject({
      method: 'POST',
      url: '/api/v1/auth/register',
      payload: { email: 'short@example.com', password: '123', displayName: 'Short' },
    });
    expect(res.statusCode).toBe(400);
    expect(errorOf(res.json()).code).toBe('VALIDATION_ERROR');
  });
});

describe('POST /api/v1/auth/login', () => {
  it('returns user + tokens for valid credentials', async () => {
    await registerUser('login@example.com', 'Login');
    const res = await app.inject({
      method: 'POST',
      url: '/api/v1/auth/login',
      payload: { email: 'login@example.com', password: 'password123' },
    });
    expect(res.statusCode).toBe(200);
    const body = res.json() as Record<string, any>;
    expect(body.user.email).toBe('login@example.com');
    expect(body.accessToken).toBeTypeOf('string');
    expect(body.refreshToken).toBeTypeOf('string');
  });

  it('returns 401 INVALID_CREDENTIALS for a wrong password', async () => {
    await registerUser('wrong@example.com', 'Wrong');
    const res = await app.inject({
      method: 'POST',
      url: '/api/v1/auth/login',
      payload: { email: 'wrong@example.com', password: 'bad-password' },
    });
    expect(res.statusCode).toBe(401);
    expect(errorOf(res.json()).code).toBe('INVALID_CREDENTIALS');
  });
});

describe('POST /api/v1/auth/refresh + logout', () => {
  it('rotates the refresh token and rejects reuse', async () => {
    const registered = await registerUser('rotate@example.com', 'Rotate');

    const res = await app.inject({
      method: 'POST',
      url: '/api/v1/auth/refresh',
      payload: { refreshToken: registered.refreshToken },
    });
    expect(res.statusCode).toBe(200);
    const rotated = res.json() as { accessToken: string; refreshToken: string };
    expect(rotated.refreshToken).not.toBe(registered.refreshToken);

    const reuse = await app.inject({
      method: 'POST',
      url: '/api/v1/auth/refresh',
      payload: { refreshToken: registered.refreshToken },
    });
    expect(reuse.statusCode).toBe(401);
    expect(errorOf(reuse.json()).code).toBe('INVALID_REFRESH_TOKEN');
  });

  it('returns 401 INVALID_REFRESH_TOKEN for garbage input', async () => {
    const res = await app.inject({
      method: 'POST',
      url: '/api/v1/auth/refresh',
      payload: { refreshToken: 'garbage' },
    });
    expect(res.statusCode).toBe(401);
    expect(errorOf(res.json()).code).toBe('INVALID_REFRESH_TOKEN');
  });

  it('logout revokes the refresh token and returns 204', async () => {
    const registered = await registerUser('logout@example.com', 'Logout');

    const res = await app.inject({
      method: 'POST',
      url: '/api/v1/auth/logout',
      headers: authHeader(registered.accessToken),
      payload: { refreshToken: registered.refreshToken },
    });
    expect(res.statusCode).toBe(204);
    expect(res.body).toBe('');

    const after = await app.inject({
      method: 'POST',
      url: '/api/v1/auth/refresh',
      payload: { refreshToken: registered.refreshToken },
    });
    expect(after.statusCode).toBe(401);
    expect(errorOf(after.json()).code).toBe('INVALID_REFRESH_TOKEN');
  });

  it('removes the logged-out device from the device list', async () => {
    const user = await registerUser('logout-device@example.com', 'Logout Device');
    const created = await app.inject({
      method: 'POST',
      url: '/api/v1/devices/register',
      headers: authHeader(user.accessToken),
      payload: { deviceName: 'Logout Phone' },
    });
    const deviceId = (created.json() as { device: { id: string } }).device.id;

    const before = await app.inject({
      method: 'GET',
      url: '/api/v1/devices',
      headers: authHeader(user.accessToken),
    });
    expect((before.json() as { devices: unknown[] }).devices).toHaveLength(1);

    const loggedOut = await app.inject({
      method: 'POST',
      url: '/api/v1/auth/logout',
      headers: { ...authHeader(user.accessToken), 'x-device-id': deviceId },
      payload: { refreshToken: user.refreshToken },
    });
    expect(loggedOut.statusCode).toBe(204);

    const after = await app.inject({
      method: 'GET',
      url: '/api/v1/devices',
      headers: authHeader(user.accessToken),
    });
    expect((after.json() as { devices: unknown[] }).devices).toHaveLength(0);
  });

  it('rejects logout without a bearer token', async () => {
    const res = await app.inject({
      method: 'POST',
      url: '/api/v1/auth/logout',
      payload: { refreshToken: 'whatever' },
    });
    expect(res.statusCode).toBe(401);
    expect(errorOf(res.json()).code).toBe('UNAUTHORIZED');
  });
});

describe('devices API', () => {
  it('requires a bearer token', async () => {
    const res = await app.inject({ method: 'GET', url: '/api/v1/devices' });
    expect(res.statusCode).toBe(401);
    expect(errorOf(res.json()).code).toBe('UNAUTHORIZED');
  });

  it('registers, lists, renames and deletes a device', async () => {
    const user = await registerUser('devices@example.com', 'Devices');

    const created = await app.inject({
      method: 'POST',
      url: '/api/v1/devices/register',
      headers: authHeader(user.accessToken),
      payload: { deviceName: 'Pixel 8' },
    });
    expect(created.statusCode).toBe(201);
    const device = (created.json() as { device: Record<string, any> }).device;
    expect(device).toMatchObject({
      userId: user.user.id,
      deviceName: 'Pixel 8',
      platform: 'android',
      credentialStatus: 'active',
      isPrimary: false,
    });
    expect(device.id).toBeTypeOf('string');
    expect(device.createdAt).toBeTypeOf('string');
    expect(device.lastSeenAt).toBeTypeOf('string');

    const second = await app.inject({
      method: 'POST',
      url: '/api/v1/devices/register',
      headers: authHeader(user.accessToken),
      payload: { deviceName: 'Pixel 9' },
    });
    const secondDevice = (second.json() as { device: Record<string, any> }).device;

    const list = await app.inject({
      method: 'GET',
      url: '/api/v1/devices',
      headers: authHeader(user.accessToken),
    });
    expect(list.statusCode).toBe(200);
    expect((list.json() as { devices: unknown[] }).devices).toHaveLength(2);

    const renamed = await app.inject({
      method: 'PATCH',
      url: `/api/v1/devices/${device.id}`,
      headers: { ...authHeader(user.accessToken), 'x-device-id': device.id },
      payload: { deviceName: 'Pixel 8 Pro' },
    });
    expect(renamed.statusCode).toBe(200);
    expect((renamed.json() as { device: Record<string, any> }).device.deviceName).toBe('Pixel 8 Pro');

    // 重命名只能改本机：用 B 的身份改 A 的设备会被拒
    const renameOther = await app.inject({
      method: 'PATCH',
      url: `/api/v1/devices/${device.id}`,
      headers: { ...authHeader(user.accessToken), 'x-device-id': secondDevice.id },
      payload: { deviceName: 'Hacked' },
    });
    expect(renameOther.statusCode).toBe(403);
    expect(errorOf(renameOther.json()).code).toBe('DEVICE_NOT_SELF');

    // 没有主设备时，任意设备可以抢占主设备身份
    const claimed = await app.inject({
      method: 'POST',
      url: '/api/v1/devices/primary',
      headers: { ...authHeader(user.accessToken), 'x-device-id': device.id },
    });
    expect(claimed.statusCode).toBe(200);
    expect((claimed.json() as { device: Record<string, any> }).device.isPrimary).toBe(true);

    // 已有主设备后，其它设备不能再抢占
    const conflict = await app.inject({
      method: 'POST',
      url: '/api/v1/devices/primary',
      headers: { ...authHeader(user.accessToken), 'x-device-id': secondDevice.id },
    });
    expect(conflict.statusCode).toBe(409);
    expect(errorOf(conflict.json()).code).toBe('PRIMARY_DEVICE_TAKEN');

    // 从设备不能移除别人，也不能移除主设备
    const denied = await app.inject({
      method: 'DELETE',
      url: `/api/v1/devices/${device.id}`,
      headers: { ...authHeader(user.accessToken), 'x-device-id': secondDevice.id },
    });
    expect(denied.statusCode).toBe(403);
    expect(errorOf(denied.json()).code).toBe('DEVICE_NOT_PRIMARY');

    // 主设备不能通过移除接口移除自己
    const selfRemove = await app.inject({
      method: 'DELETE',
      url: `/api/v1/devices/${device.id}`,
      headers: { ...authHeader(user.accessToken), 'x-device-id': device.id },
    });
    expect(selfRemove.statusCode).toBe(400);

    // 主设备移除从设备
    const removed = await app.inject({
      method: 'DELETE',
      url: `/api/v1/devices/${secondDevice.id}`,
      headers: { ...authHeader(user.accessToken), 'x-device-id': device.id },
    });
    expect(removed.statusCode).toBe(204);

    // 删除是幂等的：再次吊销已吊销设备仍返回 204（便于客户端重试）
    const again = await app.inject({
      method: 'DELETE',
      url: `/api/v1/devices/${secondDevice.id}`,
      headers: { ...authHeader(user.accessToken), 'x-device-id': device.id },
    });
    expect(again.statusCode).toBe(204);

    const list2 = await app.inject({
      method: 'GET',
      url: '/api/v1/devices',
      headers: authHeader(user.accessToken),
    });
    const listed = (list2.json() as { devices: Array<Record<string, any>> }).devices;
    const primary = listed.find((item) => item.id === device.id);
    const revoked = listed.find((item) => item.id === secondDevice.id);
    expect(primary?.isPrimary).toBe(true);
    expect(revoked?.credentialStatus).toBe('revoked');

    // 主设备放弃身份后，其它设备可以再次抢占
    const released = await app.inject({
      method: 'DELETE',
      url: '/api/v1/devices/primary',
      headers: { ...authHeader(user.accessToken), 'x-device-id': device.id },
    });
    expect(released.statusCode).toBe(204);
    const releasedAgain = await app.inject({
      method: 'DELETE',
      url: '/api/v1/devices/primary',
      headers: { ...authHeader(user.accessToken), 'x-device-id': device.id },
    });
    expect(releasedAgain.statusCode).toBe(403);
    expect(errorOf(releasedAgain.json()).code).toBe('DEVICE_NOT_PRIMARY');
  });

  it('lets a removed device request rejoin and the primary device approve it', async () => {
    const user = await registerUser('rejoin@example.com', 'Rejoin');

    const primaryRes = await app.inject({
      method: 'POST',
      url: '/api/v1/devices/register',
      headers: authHeader(user.accessToken),
      payload: { deviceName: 'Primary' },
    });
    const primary = (primaryRes.json() as { device: Record<string, any> }).device;

    const secondaryRes = await app.inject({
      method: 'POST',
      url: '/api/v1/devices/register',
      headers: authHeader(user.accessToken),
      payload: { deviceName: 'Removed Phone' },
    });
    const secondary = (secondaryRes.json() as { device: Record<string, any> }).device;

    await app.inject({
      method: 'POST',
      url: '/api/v1/devices/primary',
      headers: { ...authHeader(user.accessToken), 'x-device-id': primary.id },
    });
    await app.inject({
      method: 'DELETE',
      url: `/api/v1/devices/${secondary.id}`,
      headers: { ...authHeader(user.accessToken), 'x-device-id': primary.id },
    });

    // 被移除设备重新登记不会自动恢复，仍为 revoked
    const reRegistered = await app.inject({
      method: 'POST',
      url: '/api/v1/devices/register',
      headers: authHeader(user.accessToken),
      payload: { deviceName: 'Removed Phone', deviceId: secondary.id },
    });
    expect(reRegistered.statusCode).toBe(201);
    expect((reRegistered.json() as { device: Record<string, any> }).device.credentialStatus).toBe(
      'revoked',
    );

    // 被移除设备发起重新加入申请
    const requested = await app.inject({
      method: 'POST',
      url: '/api/v1/devices/rejoin',
      headers: { ...authHeader(user.accessToken), 'x-device-id': secondary.id },
    });
    expect(requested.statusCode).toBe(200);
    expect((requested.json() as { device: Record<string, any> }).device.isRejoinRequested).toBe(
      true,
    );

    // 从设备无权同意
    const denied = await app.inject({
      method: 'POST',
      url: `/api/v1/devices/${secondary.id}/rejoin/approve`,
      headers: { ...authHeader(user.accessToken), 'x-device-id': secondary.id },
    });
    expect(denied.statusCode).toBe(403);
    expect(errorOf(denied.json()).code).toBe('DEVICE_NOT_PRIMARY');

    // 主设备同意后恢复 active
    const approved = await app.inject({
      method: 'POST',
      url: `/api/v1/devices/${secondary.id}/rejoin/approve`,
      headers: { ...authHeader(user.accessToken), 'x-device-id': primary.id },
    });
    expect(approved.statusCode).toBe(200);
    const approvedDevice = (approved.json() as { device: Record<string, any> }).device;
    expect(approvedDevice.credentialStatus).toBe('active');
    expect(approvedDevice.isRejoinRequested).toBe(false);

    // 没有待处理申请时再次同意返回 400
    const again = await app.inject({
      method: 'POST',
      url: `/api/v1/devices/${secondary.id}/rejoin/approve`,
      headers: { ...authHeader(user.accessToken), 'x-device-id': primary.id },
    });
    expect(again.statusCode).toBe(400);
  });

  it('returns 404 DEVICE_NOT_FOUND for another user device', async () => {
    const alice = await registerUser('alice-device@example.com', 'Alice');
    const bob = await registerUser('bob-device@example.com', 'Bob');

    const created = await app.inject({
      method: 'POST',
      url: '/api/v1/devices/register',
      headers: authHeader(alice.accessToken),
      payload: { deviceName: 'Alice Phone' },
    });
    const deviceId = (created.json() as { device: { id: string } }).device.id;

    const bobDevice = await app.inject({
      method: 'POST',
      url: '/api/v1/devices/register',
      headers: authHeader(bob.accessToken),
      payload: { deviceName: 'Bob Phone' },
    });
    const bobDeviceId = (bobDevice.json() as { device: { id: string } }).device.id;

    const patched = await app.inject({
      method: 'PATCH',
      url: `/api/v1/devices/${deviceId}`,
      headers: { ...authHeader(bob.accessToken), 'x-device-id': bobDeviceId },
      payload: { deviceName: 'Hacked' },
    });
    expect(patched.statusCode).toBe(404);
    expect(errorOf(patched.json()).code).toBe('DEVICE_NOT_FOUND');

    const deleted = await app.inject({
      method: 'DELETE',
      url: `/api/v1/devices/${deviceId}`,
      headers: { ...authHeader(bob.accessToken), 'x-device-id': bobDeviceId },
    });
    expect(deleted.statusCode).toBe(404);
    expect(errorOf(deleted.json()).code).toBe('DEVICE_NOT_FOUND');
  });

  it('returns 404 DEVICE_NOT_FOUND for a malformed deviceId', async () => {
    const user = await registerUser('malformed@example.com', 'Malformed');
    const res = await app.inject({
      method: 'PATCH',
      url: '/api/v1/devices/not-a-uuid',
      headers: authHeader(user.accessToken),
      payload: { deviceName: 'X' },
    });
    expect(res.statusCode).toBe(404);
    expect(errorOf(res.json()).code).toBe('DEVICE_NOT_FOUND');
  });
});
