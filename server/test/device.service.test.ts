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
  it('renames the current device', async () => {
    const { service } = makeService();
    const created = await service.register('user-1', 'Old Name');
    const renamed = await service.rename('user-1', created.id, created.id, 'New Name');

    expect(renamed.id).toBe(created.id);
    expect(renamed.deviceName).toBe('New Name');
  });

  it('returns DEVICE_NOT_SELF when renaming another device (even the primary)', async () => {
    const { service } = makeService();
    const a = await service.register('user-1', 'Phone A');
    const b = await service.register('user-1', 'Phone B');
    await service.claimPrimary('user-1', a.id);

    // 主设备 A 试图重命名从设备 B
    await expectAppError(
      () => service.rename('user-1', a.id, b.id, 'Hacked'),
      403,
      'DEVICE_NOT_SELF',
    );
  });

  it('returns DEVICE_NOT_FOUND for a device of another user', async () => {
    const { service } = makeService();
    const created = await service.register('user-1', 'Phone A');
    await expectAppError(
      () => service.rename('user-2', created.id, created.id, 'Hacked'),
      404,
      'DEVICE_NOT_FOUND',
    );
  });

  it('returns DEVICE_NOT_FOUND for an unknown device', async () => {
    const { service } = makeService();
    await expectAppError(
      () =>
        service.rename(
          'user-1',
          '00000000-0000-4000-8000-000000000000',
          '00000000-0000-4000-8000-000000000000',
          'X',
        ),
      404,
      'DEVICE_NOT_FOUND',
    );
  });
});

describe('DeviceService primary role', () => {
  it('claims primary only when none exists, and releases it for others', async () => {
    const { service } = makeService();
    const a = await service.register('user-1', 'Phone A');
    const b = await service.register('user-1', 'Phone B');

    const claimed = await service.claimPrimary('user-1', a.id);
    expect(claimed.isPrimary).toBe(true);

    await expectAppError(
      () => service.claimPrimary('user-1', b.id),
      409,
      'PRIMARY_DEVICE_TAKEN',
    );

    const list = await service.list('user-1');
    expect(list.find((device) => device.id === a.id)?.isPrimary).toBe(true);
    expect(list.find((device) => device.id === b.id)?.isPrimary).toBe(false);

    await service.releasePrimary('user-1', a.id);
    const afterRelease = await service.list('user-1');
    expect(afterRelease.every((device) => !device.isPrimary)).toBe(true);

    const reclaimed = await service.claimPrimary('user-1', b.id);
    expect(reclaimed.isPrimary).toBe(true);
  });

  it('only the primary device can release the role', async () => {
    const { service } = makeService();
    const a = await service.register('user-1', 'Phone A');
    await service.claimPrimary('user-1', a.id);

    await expectAppError(
      () => service.releasePrimary('user-1', '00000000-0000-4000-8000-000000000000'),
      404,
      'DEVICE_NOT_FOUND',
    );
  });
});

describe('DeviceService rejoin', () => {
  async function makeRevokedSecondary() {
    const ctx = makeService();
    const primary = await ctx.service.register('user-1', 'Primary');
    const secondary = await ctx.service.register('user-1', 'Phone A');
    await ctx.service.claimPrimary('user-1', primary.id);
    await ctx.service.revoke('user-1', primary.id, secondary.id);
    return { ...ctx, primary, secondary };
  }

  it('keeps a revoked device revoked when it registers again', async () => {
    const { service, secondary } = await makeRevokedSecondary();

    const reRegistered = await service.register('user-1', 'Phone A', secondary.id);
    expect(reRegistered.id).toBe(secondary.id);
    expect(reRegistered.credentialStatus).toBe('revoked');
    expect(reRegistered.isRejoinRequested).toBe(false);
  });

  it('lets a revoked device request rejoin and the primary device approve it', async () => {
    const { service, primary, secondary } = await makeRevokedSecondary();

    const requested = await service.requestRejoin('user-1', secondary.id);
    expect(requested.isRejoinRequested).toBe(true);
    expect(requested.credentialStatus).toBe('revoked');

    // 只有主设备可以同意
    await expectAppError(
      () => service.approveRejoin('user-1', secondary.id, secondary.id),
      403,
      'DEVICE_NOT_PRIMARY',
    );

    const approved = await service.approveRejoin('user-1', primary.id, secondary.id);
    expect(approved.credentialStatus).toBe('active');
    expect(approved.isRejoinRequested).toBe(false);
  });

  it('lets the primary device reject a rejoin request', async () => {
    const { service, primary, secondary } = await makeRevokedSecondary();
    await service.requestRejoin('user-1', secondary.id);

    await service.rejectRejoin('user-1', primary.id, secondary.id);

    const list = await service.list('user-1');
    const rejected = list.find((device) => device.id === secondary.id);
    expect(rejected?.credentialStatus).toBe('revoked');
    expect(rejected?.isRejoinRequested).toBe(false);

    // 请求已被清除，不能重复同意
    await expectAppError(
      () => service.approveRejoin('user-1', primary.id, secondary.id),
      400,
      'VALIDATION_ERROR',
    );
  });

  it('rejects rejoin requests from an active device', async () => {
    const { service } = makeService();
    const device = await service.register('user-1', 'Phone A');

    await expectAppError(
      () => service.requestRejoin('user-1', device.id),
      400,
      'VALIDATION_ERROR',
    );
  });
});

describe('DeviceService.purgeRemoved', () => {
  async function makeRevokedSecondary() {
    const ctx = makeService();
    const primary = await ctx.service.register('user-1', 'Primary');
    const secondary = await ctx.service.register('user-1', 'Phone A');
    await ctx.service.claimPrimary('user-1', primary.id);
    await ctx.service.revoke('user-1', primary.id, secondary.id);
    return { ...ctx, primary, secondary };
  }

  it('hides a removed device from the list without deleting its record', async () => {
    const { service, devices, primary, secondary } = await makeRevokedSecondary();

    await service.purgeRemoved('user-1', primary.id, secondary.id);

    const list = await service.list('user-1');
    expect(list.map((device) => device.id)).not.toContain(secondary.id);
    // 记录仍保留，只做软清理
    expect(devices.items.get(secondary.id)?.loggedOutAt).not.toBeNull();
  });

  it('brings the device back to the list when it requests to rejoin', async () => {
    const { service, primary, secondary } = await makeRevokedSecondary();
    await service.purgeRemoved('user-1', primary.id, secondary.id);

    await service.requestRejoin('user-1', secondary.id);

    const list = await service.list('user-1');
    const restored = list.find((device) => device.id === secondary.id);
    expect(restored?.credentialStatus).toBe('revoked');
    expect(restored?.isRejoinRequested).toBe(true);
  });

  it('rejects purging by a non-primary device', async () => {
    const { service, secondary } = await makeRevokedSecondary();

    await expectAppError(
      () => service.purgeRemoved('user-1', secondary.id, secondary.id),
      403,
      'DEVICE_NOT_PRIMARY',
    );
  });

  it('rejects purging an active device', async () => {
    const { service } = makeService();
    const primary = await service.register('user-1', 'Primary');
    const secondary = await service.register('user-1', 'Phone A');
    await service.claimPrimary('user-1', primary.id);

    await expectAppError(
      () => service.purgeRemoved('user-1', primary.id, secondary.id),
      400,
      'VALIDATION_ERROR',
    );
  });

  it('rejects the primary device purging its own record', async () => {
    const { service, primary } = await makeRevokedSecondary();

    await expectAppError(
      () => service.purgeRemoved('user-1', primary.id, primary.id),
      400,
      'VALIDATION_ERROR',
    );
  });
});

describe('DeviceService.revoke', () => {
  it('lets the primary device revoke a secondary device and its refresh tokens', async () => {
    const { service, refreshTokens } = makeService();
    const primary = await service.register('user-1', 'Primary');
    const secondary = await service.register('user-1', 'Phone A');
    await service.claimPrimary('user-1', primary.id);
    await refreshTokens.create({
      userId: 'user-1',
      deviceId: secondary.id,
      tokenHash: 'hash-1',
      expiresAt: new Date(Date.now() + 100000),
    });

    await service.revoke('user-1', primary.id, secondary.id);

    const list = await service.list('user-1');
    expect(list.find((device) => device.id === secondary.id)?.credentialStatus).toBe('revoked');
    expect(list.find((device) => device.id === primary.id)?.credentialStatus).toBe('active');

    const token = await refreshTokens.findByHash('hash-1');
    expect(token?.revokedAt).not.toBeNull();
  });

  it('rejects removal when the requester is not the primary device', async () => {
    const { service } = makeService();
    const primary = await service.register('user-1', 'Primary');
    const secondary = await service.register('user-1', 'Phone A');
    await service.claimPrimary('user-1', primary.id);

    await expectAppError(
      () => service.revoke('user-1', secondary.id, primary.id),
      403,
      'DEVICE_NOT_PRIMARY',
    );
    await expectAppError(
      () => service.revoke('user-1', secondary.id, secondary.id),
      403,
      'DEVICE_NOT_PRIMARY',
    );
  });

  it('rejects the primary device removing itself', async () => {
    const { service } = makeService();
    const primary = await service.register('user-1', 'Primary');
    await service.claimPrimary('user-1', primary.id);

    await expectAppError(
      () => service.revoke('user-1', primary.id, primary.id),
      400,
      'VALIDATION_ERROR',
    );
  });

  it('returns DEVICE_NOT_FOUND for a device of another user', async () => {
    const { service } = makeService();
    const created = await service.register('user-1', 'Phone A');
    await expectAppError(
      () => service.revoke('user-2', created.id, created.id),
      404,
      'DEVICE_NOT_FOUND',
    );
  });
});
