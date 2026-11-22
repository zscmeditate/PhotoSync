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
    });
    expect(device.id).toBeTypeOf('string');
    expect(device.createdAt).toBeTypeOf('string');
    expect(device.lastSeenAt).toBeTypeOf('string');

    const list = await app.inject({
      method: 'GET',
      url: '/api/v1/devices',
      headers: authHeader(user.accessToken),
    });
    expect(list.statusCode).toBe(200);
    expect((list.json() as { devices: unknown[] }).devices).toHaveLength(1);

    const renamed = await app.inject({
      method: 'PATCH',
      url: `/api/v1/devices/${device.id}`,
      headers: authHeader(user.accessToken),
      payload: { deviceName: 'Pixel 8 Pro' },
    });
    expect(renamed.statusCode).toBe(200);
    expect((renamed.json() as { device: Record<string, any> }).device.deviceName).toBe('Pixel 8 Pro');

    const removed = await app.inject({
      method: 'DELETE',
      url: `/api/v1/devices/${device.id}`,
      headers: authHeader(user.accessToken),
    });
    expect(removed.statusCode).toBe(204);

    // 删除是幂等的：再次吊销已吊销设备仍返回 204（便于客户端重试）
    const again = await app.inject({
      method: 'DELETE',
      url: `/api/v1/devices/${device.id}`,
      headers: authHeader(user.accessToken),
    });
    expect(again.statusCode).toBe(204);

    const list2 = await app.inject({
      method: 'GET',
      url: '/api/v1/devices',
      headers: authHeader(user.accessToken),
    });
    const listed = (list2.json() as { devices: Array<Record<string, any>> }).devices;
    expect(listed[0].credentialStatus).toBe('revoked');
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

    const patched = await app.inject({
      method: 'PATCH',
      url: `/api/v1/devices/${deviceId}`,
      headers: authHeader(bob.accessToken),
      payload: { deviceName: 'Hacked' },
    });
    expect(patched.statusCode).toBe(404);
    expect(errorOf(patched.json()).code).toBe('DEVICE_NOT_FOUND');

    const deleted = await app.inject({
      method: 'DELETE',
      url: `/api/v1/devices/${deviceId}`,
      headers: authHeader(bob.accessToken),
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
