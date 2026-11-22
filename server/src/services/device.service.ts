import { deviceNotFound } from '../lib/errors';
import type {
  DeviceRecord,
  DeviceRepository,
  RefreshTokenRepository,
} from '../repositories/ports';

export interface DeviceDto {
  id: string;
  userId: string;
  deviceName: string;
  platform: string;
  credentialStatus: string;
  createdAt: string;
  lastSeenAt: string | null;
}

export interface DeviceServiceDeps {
  devices: DeviceRepository;
  refreshTokens: RefreshTokenRepository;
  /** 可注入时钟，便于测试 */
  now?: () => Date;
}

function toDeviceDto(device: DeviceRecord): DeviceDto {
  return {
    id: device.id,
    userId: device.userId,
    deviceName: device.deviceName,
    platform: device.platform,
    credentialStatus: device.credentialStatus,
    createdAt: device.createdAt.toISOString(),
    lastSeenAt: device.lastSeenAt ? device.lastSeenAt.toISOString() : null,
  };
}

export class DeviceService {
  private readonly now: () => Date;

  constructor(private readonly deps: DeviceServiceDeps) {
    this.now = deps.now ?? (() => new Date());
  }

  /** 注册设备（Phase 1 平台固定 android，凭据状态 active） */
  async register(userId: string, deviceName: string): Promise<DeviceDto> {
    const device = await this.deps.devices.create({
      userId,
      deviceName: deviceName.trim(),
      platform: 'android',
      credentialStatus: 'active',
      lastSeenAt: this.now(),
    });
    return toDeviceDto(device);
  }

  /** 列出当前用户的全部设备 */
  async list(userId: string): Promise<DeviceDto[]> {
    const devices = await this.deps.devices.listByUser(userId);
    return devices.map(toDeviceDto);
  }

  /** 重命名设备；非本用户设备抛 404 DEVICE_NOT_FOUND */
  async rename(userId: string, deviceId: string, deviceName: string): Promise<DeviceDto> {
    const device = await this.requireOwnedDevice(userId, deviceId);
    const updated = await this.deps.devices.update(device.id, { deviceName: deviceName.trim() });
    return toDeviceDto(updated);
  }

  /** 吊销设备凭据并吊销该设备全部刷新令牌；非本用户设备抛 404 DEVICE_NOT_FOUND */
  async revoke(userId: string, deviceId: string): Promise<void> {
    const device = await this.requireOwnedDevice(userId, deviceId);
    const now = this.now();
    await this.deps.devices.update(device.id, { credentialStatus: 'revoked' });
    await this.deps.refreshTokens.revokeByDevice(device.id, now);
  }

  private async requireOwnedDevice(userId: string, deviceId: string): Promise<DeviceRecord> {
    const device = await this.deps.devices.findById(deviceId);
    if (!device || device.userId !== userId) {
      throw deviceNotFound();
    }
    return device;
  }
}
