import {
  deviceNotFound,
  deviceNotPrimary,
  deviceNotSelf,
  invalidDevice,
  primaryDeviceTaken,
  validationError,
} from '../lib/errors';
import type {
  DeviceRecord,
  DeviceRepository,
  RefreshTokenRepository,
  SpaceRepository,
} from '../repositories/ports';

export interface DeviceDto {
  id: string;
  userId: string;
  deviceName: string;
  platform: string;
  credentialStatus: string;
  /** 是否为当前账号的主设备（同一账号最多一台） */
  isPrimary: boolean;
  /** 该设备是否正在等待主设备同意重新加入（仅被移除设备可能为 true） */
  isRejoinRequested: boolean;
  createdAt: string;
  lastSeenAt: string | null;
}

export interface DeviceServiceDeps {
  devices: DeviceRepository;
  refreshTokens: RefreshTokenRepository;
  /** 新设备登记后自动加入该账号可访问的空间，实现同账号设备自动互见 */
  spaces?: SpaceRepository;
  /** 可注入时钟，便于测试 */
  now?: () => Date;
}

function toDeviceDto(device: DeviceRecord, primaryDeviceId: string | null): DeviceDto {
  return {
    id: device.id,
    userId: device.userId,
    deviceName: device.deviceName,
    platform: device.platform,
    credentialStatus: device.credentialStatus,
    isPrimary: primaryDeviceId === device.id,
    isRejoinRequested: device.rejoinRequestedAt !== null,
    createdAt: device.createdAt.toISOString(),
    lastSeenAt: device.lastSeenAt ? device.lastSeenAt.toISOString() : null,
  };
}

export class DeviceService {
  private readonly now: () => Date;
  /** touch 内存节流：避免每个请求都查库 */
  private readonly touchedAt = new Map<string, number>();

  constructor(private readonly deps: DeviceServiceDeps) {
    this.now = deps.now ?? (() => new Date());
  }

  /**
   * 登记本机设备（Phase 1 平台固定 android）。
   * 传入 deviceId 且归属当前用户时原地复用，保证同一台手机在同一账号下
   * 始终只有一条设备记录（不重复创建）。
   * 已被主设备移除的设备重新登记时仍保持 revoked：必须由主设备同意重新加入。
   * 新设备登记后不再自动加入空间——登出会清除绑定，重新登录需手动加入空间。
   */
  async register(userId: string, deviceName: string, deviceId?: string): Promise<DeviceDto> {
    const name = deviceName.trim();
    const now = this.now();
    if (deviceId) {
      const existing = await this.deps.devices.findById(deviceId);
      if (existing && existing.userId === userId) {
        const revoked = existing.credentialStatus === 'revoked';
        const updated = await this.deps.devices.update(existing.id, {
          deviceName: name,
          lastSeenAt: now,
          // 重新登录后该设备重新出现在列表
          loggedOutAt: null,
          ...(revoked ? {} : { credentialStatus: 'active' as const }),
        });
        const primaryDeviceId = await this.deps.devices.findPrimaryDeviceId(userId);
        return toDeviceDto(updated, primaryDeviceId);
      }
    }
    const newDevice = {
      userId,
      deviceName: name,
      platform: 'android' as const,
      credentialStatus: 'active' as const,
      lastSeenAt: now,
    };
    let device: DeviceRecord;
    try {
      // 客户端可自带稳定设备 ID（由 ANDROID_ID 派生）：首次登记即固定下来，
      // 卸载重装后仍能命中同一条记录，不会重复登记。
      device = await this.deps.devices.create(deviceId ? { ...newDevice, id: deviceId } : newDevice);
    } catch (error) {
      if (!deviceId || !isUniqueConstraintError(error)) {
        throw error;
      }
      // 并发登记同一 ID 触发唯一约束时回退为服务端生成 ID，避免登记直接失败
      device = await this.deps.devices.create(newDevice);
    }
    const primaryDeviceId = await this.deps.devices.findPrimaryDeviceId(userId);
    return toDeviceDto(device, primaryDeviceId);
  }

  /** 列出当前用户的全部设备 */
  async list(userId: string): Promise<DeviceDto[]> {
    const [devices, primaryDeviceId] = await Promise.all([
      this.deps.devices.listByUser(userId),
      this.deps.devices.findPrimaryDeviceId(userId),
    ]);
    return devices.map((device) => toDeviceDto(device, primaryDeviceId));
  }

  /**
   * 重命名设备：只能重命名本机（X-Device-Id 对应的设备）。
   * 即使是主设备也不能重命名他人设备；非本用户设备抛 404 DEVICE_NOT_FOUND。
   */
  async rename(
    userId: string,
    requestDeviceId: string,
    deviceId: string,
    deviceName: string,
  ): Promise<DeviceDto> {
    const device = await this.requireOwnedDevice(userId, deviceId);
    if (requestDeviceId !== device.id) {
      throw deviceNotSelf();
    }
    const updated = await this.deps.devices.update(device.id, { deviceName: deviceName.trim() });
    const primaryDeviceId = await this.deps.devices.findPrimaryDeviceId(userId);
    return toDeviceDto(updated, primaryDeviceId);
  }

  /**
   * 抢占主设备身份：只有当前账号没有主设备时才允许（幂等）。
   * 已存在的其它设备自动保持为从设备。
   */
  async claimPrimary(userId: string, deviceId: string): Promise<DeviceDto> {
    const device = await this.requireOwnedActiveDevice(userId, deviceId);
    const claimed = await this.deps.devices.claimPrimaryDevice(userId, deviceId);
    if (!claimed) {
      throw primaryDeviceTaken();
    }
    return toDeviceDto(device, deviceId);
  }

  /** 主设备主动放弃主设备身份，之后其它设备可以重新抢占。 */
  async releasePrimary(userId: string, deviceId: string): Promise<void> {
    await this.requireOwnedActiveDevice(userId, deviceId);
    const cleared = await this.deps.devices.clearPrimaryDevice(userId, deviceId);
    if (!cleared) {
      throw deviceNotPrimary();
    }
  }

  /**
   * 刷新设备在线时间。
   * 仅在设备归属当前用户且凭据有效时更新，并按最小间隔节流，避免每个请求都写库。
   */
  async touch(userId: string, deviceId: string, minIntervalMs = 60_000): Promise<void> {
    const now = this.now();
    const cached = this.touchedAt.get(deviceId);
    if (cached !== undefined && now.getTime() - cached < minIntervalMs) {
      return;
    }
    const device = await this.deps.devices.findById(deviceId);
    if (!device || device.userId !== userId || device.credentialStatus !== 'active') {
      return;
    }
    this.touchedAt.set(deviceId, now.getTime());
    const lastSeen = device.lastSeenAt ? device.lastSeenAt.getTime() : 0;
    if (now.getTime() - lastSeen < minIntervalMs) {
      return;
    }
    await this.deps.devices.update(device.id, { lastSeenAt: now });
  }

  /**
   * 由主设备吊销从设备：仅当前账号的主设备可以移除其它设备，
   * 主设备不能通过该接口移除自己（需先放弃主设备身份）。
   */
  async revoke(userId: string, requestDeviceId: string, deviceId: string): Promise<void> {
    const device = await this.requireOwnedDevice(userId, deviceId);
    const primaryDeviceId = await this.requirePrimary(userId, requestDeviceId);
    if (device.id === primaryDeviceId) {
      throw validationError('Primary device cannot remove itself; release primary first');
    }
    const now = this.now();
    const wasActive = device.credentialStatus === 'active';
    await this.deps.devices.update(device.id, {
      credentialStatus: 'revoked',
      // 重新移除时丢弃未处理的重新加入申请，需要再次申请
      ...(wasActive ? { rejoinRequestedAt: null } : {}),
    });
    await this.deps.refreshTokens.revokeByDevice(device.id, now);
    this.touchedAt.delete(device.id);
    // 退出该设备所在的空间，避免空间成员里残留失效设备
    if (this.deps.spaces) {
      const spaces = await this.deps.spaces.listAccessible(userId, [device.id]);
      for (const space of spaces) {
        await this.deps.spaces.removeMember(space.id, device.id);
      }
    }
  }

  /**
   * 被移除的设备发起重新加入申请（幂等）。
   * 只有 revoked 设备需要申请；申请后由主设备决定同意或拒绝。
   */
  async requestRejoin(userId: string, deviceId: string): Promise<DeviceDto> {
    const device = await this.requireOwnedDevice(userId, deviceId);
    if (device.credentialStatus !== 'revoked') {
      throw validationError('Only a removed device can request to rejoin');
    }
    const updated = device.rejoinRequestedAt
      ? device
      : await this.deps.devices.update(device.id, { rejoinRequestedAt: this.now() });
    const primaryDeviceId = await this.deps.devices.findPrimaryDeviceId(userId);
    return toDeviceDto(updated, primaryDeviceId);
  }

  /** 由主设备同意被移除设备重新加入：恢复为 active 并重新加入空间。 */
  async approveRejoin(
    userId: string,
    requestDeviceId: string,
    deviceId: string,
  ): Promise<DeviceDto> {
    const device = await this.requireOwnedDevice(userId, deviceId);
    await this.requirePrimary(userId, requestDeviceId);
    if (device.credentialStatus !== 'revoked' || device.rejoinRequestedAt === null) {
      throw validationError('Device has no pending rejoin request');
    }
    const updated = await this.deps.devices.update(device.id, {
      credentialStatus: 'active',
      rejoinRequestedAt: null,
      lastSeenAt: this.now(),
    });
    this.touchedAt.delete(device.id);
    await this.joinAccessibleSpaces(userId, device.id);
    return toDeviceDto(updated, requestDeviceId);
  }

  /** 由主设备拒绝被移除设备的重新加入申请（设备保持 revoked）。 */
  async rejectRejoin(
    userId: string,
    requestDeviceId: string,
    deviceId: string,
  ): Promise<void> {
    const device = await this.requireOwnedDevice(userId, deviceId);
    await this.requirePrimary(userId, requestDeviceId);
    if (device.credentialStatus !== 'revoked' || device.rejoinRequestedAt === null) {
      throw validationError('Device has no pending rejoin request');
    }
    await this.deps.devices.update(device.id, { rejoinRequestedAt: null });
  }

  /** 校验请求方是否为当前账号的主设备，返回主设备 ID。 */
  private async requirePrimary(userId: string, requestDeviceId: string): Promise<string> {
    const primaryDeviceId = await this.deps.devices.findPrimaryDeviceId(userId);
    if (primaryDeviceId === null || primaryDeviceId !== requestDeviceId) {
      throw deviceNotPrimary();
    }
    return primaryDeviceId;
  }

  /** 新设备自动加入该账号可访问的空间（自有空间 + 同账号其它设备已加入的空间） */
  private async joinAccessibleSpaces(userId: string, deviceId: string): Promise<void> {
    if (!this.deps.spaces) {
      return;
    }
    const devices = await this.deps.devices.listByUser(userId);
    const spaces = await this.deps.spaces.listAccessible(
      userId,
      devices.map((device) => device.id),
    );
    for (const space of spaces) {
      await this.deps.spaces.addMember(space.id, deviceId);
    }
  }

  private async requireOwnedDevice(userId: string, deviceId: string): Promise<DeviceRecord> {
    const device = await this.deps.devices.findById(deviceId);
    if (!device || device.userId !== userId) {
      throw deviceNotFound();
    }
    return device;
  }

  private async requireOwnedActiveDevice(
    userId: string,
    deviceId: string,
  ): Promise<DeviceRecord> {
    const device = await this.requireOwnedDevice(userId, deviceId);
    if (device.credentialStatus !== 'active') {
      throw invalidDevice();
    }
    return device;
  }
}

/** Prisma 唯一约束冲突（P2002）判定，用于客户端自带 ID 的并发登记回退 */
function isUniqueConstraintError(error: unknown): boolean {
  return (
    typeof error === 'object' &&
    error !== null &&
    (error as { code?: unknown }).code === 'P2002'
  );
}
