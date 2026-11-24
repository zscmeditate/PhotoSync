/**
 * 账号级同步锁：同一账号同一时刻只允许一台设备执行上传/下载同步，
 * 其他设备抢锁失败后等待，直到持锁设备释放或锁超时，再重新抢锁。
 *
 * 锁状态保存在内存并带 TTL：持锁设备停止心跳后锁自动过期，
 * 服务重启后锁清空——同步本身幂等（事件 + 游标），重新抢锁即可，无需持久化。
 */
export interface SyncLockResult {
  acquired: boolean;
  /** 当前持锁设备；未持锁时为 null */
  holderDeviceId: string | null;
  /** 持锁到期时间（epoch 毫秒）；未持锁时为 null */
  expiresAt: number | null;
}

interface LockEntry {
  holderDeviceId: string;
  expiresAt: number;
}

export class SyncLockService {
  private readonly locks = new Map<string, LockEntry>();

  /** 锁 TTL（毫秒）：持锁设备需定期心跳续期，超过 TTL 视为设备失联，锁自动释放 */
  constructor(private readonly ttlMs: number = 90_000) {}

  /** 抢锁：空闲/已过期/本人持有（续期）时成功，否则返回当前持锁者 */
  acquire(userId: string, deviceId: string): SyncLockResult {
    const now = Date.now();
    const current = this.locks.get(userId);
    if (current && current.expiresAt > now && current.holderDeviceId !== deviceId) {
      return {
        acquired: false,
        holderDeviceId: current.holderDeviceId,
        expiresAt: current.expiresAt,
      };
    }
    const entry: LockEntry = { holderDeviceId: deviceId, expiresAt: now + this.ttlMs };
    this.locks.set(userId, entry);
    return { acquired: true, holderDeviceId: deviceId, expiresAt: entry.expiresAt };
  }

  /** 心跳续期：仅持锁设备有效；锁已丢失/过期时返回 acquired=false */
  heartbeat(userId: string, deviceId: string): SyncLockResult {
    const now = Date.now();
    const current = this.locks.get(userId);
    if (!current || current.expiresAt <= now || current.holderDeviceId !== deviceId) {
      if (current && current.expiresAt <= now) {
        this.locks.delete(userId);
      }
      return { acquired: false, holderDeviceId: null, expiresAt: null };
    }
    current.expiresAt = now + this.ttlMs;
    return { acquired: true, holderDeviceId: deviceId, expiresAt: current.expiresAt };
  }

  /** 释放锁：仅持锁本人可释放 */
  release(userId: string, deviceId: string): void {
    const current = this.locks.get(userId);
    if (current && current.holderDeviceId === deviceId) {
      this.locks.delete(userId);
    }
  }
}
