import { describe, expect, it } from 'vitest';
import { AppError } from '../src/lib/errors';
import { DeviceService } from '../src/services/device.service';
import { FakeDeviceRepository, FakeRefreshTokenRepository } from './fakes';

function makeService() {
  const devices = new FakeDeviceRepository();
  const refreshTokens = new FakeRefreshTokenRepository();
  const service = new DeviceService({ devices, refreshTokens });
  return { service, devices, refreshTokens };
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

describe('DeviceService.register / list', () => {
  it('registers an android device with active credentials', async () => {
    const { service, devices } = makeService();
    const device = await service.register('user-1', 'Pixel 8');

    expect(device.id).toBeTypeOf('string');
    expect(device.userId).toBe('user-1');
    expect(device.deviceName).toBe('Pixel 8');
    expect(device.platform).toBe('android');
    expect(device.credentialStatus).toBe('active');
    expect(device.createdAt).toMatch(/^\d{4}-\d{2}-\d{2}T/);
    expect(device.lastSeenAt).toMatch(/^\d{4}-\d{2}-\d{2}T/);

    expect(devices.items.size).toBe(1);
  });

  it('lists only devices of the given user', async () => {
    const { service } = makeService();
    await service.register('user-1', 'Phone A');
    await service.register('user-1', 'Phone B');
    await service.register('user-2', 'Phone C');

    const devices = await service.list('user-1');
    expect(devices).toHaveLength(2);
    expect(devices.map((d) => d.deviceName).sort()).toEqual(['Phone A', 'Phone B']);
  });
});

describe('DeviceService.rename', () => {
  it('renames an owned device', async () => {
    const { service } = makeService();
    const created = await service.register('user-1', 'Old Name');
    const renamed = await service.rename('user-1', created.id, 'New Name');

    expect(renamed.id).toBe(created.id);
    expect(renamed.deviceName).toBe('New Name');
  });

  it('returns DEVICE_NOT_FOUND for a device of another user', async () => {
    const { service } = makeService();
    const created = await service.register('user-1', 'Phone A');
    await expectAppError(
      () => service.rename('user-2', created.id, 'Hacked'),
      404,
      'DEVICE_NOT_FOUND',
    );
  });

  it('returns DEVICE_NOT_FOUND for an unknown device', async () => {
    const { service } = makeService();
    await expectAppError(
      () => service.rename('user-1', '00000000-0000-4000-8000-000000000000', 'X'),
      404,
      'DEVICE_NOT_FOUND',
    );
  });
});

describe('DeviceService.revoke', () => {
  it('revokes device credentials and its refresh tokens', async () => {
    const { service, refreshTokens } = makeService();
    const device = await service.register('user-1', 'Phone A');
    await refreshTokens.create({
      userId: 'user-1',
      deviceId: device.id,
      tokenHash: 'hash-1',
      expiresAt: new Date(Date.now() + 100000),
    });

    await service.revoke('user-1', device.id);

    const list = await service.list('user-1');
    expect(list[0].credentialStatus).toBe('revoked');

    const token = await refreshTokens.findByHash('hash-1');
    expect(token?.revokedAt).not.toBeNull();
  });

  it('returns DEVICE_NOT_FOUND for a device of another user', async () => {
    const { service } = makeService();
    const created = await service.register('user-1', 'Phone A');
    await expectAppError(() => service.revoke('user-2', created.id), 404, 'DEVICE_NOT_FOUND');
  });
});
