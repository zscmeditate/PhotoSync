/**
 * 实时通知中心：仅向订阅了某空间的连接推送 "该空间有新 revision" 的提示。
 * 不传输事件数据，客户端收到提示后通过 changes 接口拉取增量，保证顺序与幂等。
 *
 * 纯内存实现：单实例部署可用；多实例部署需替换为 Redis Pub/Sub 适配器（接口不变）。
 */
export interface RealtimeConnection {
  readonly id: string;
  readonly userId: string;
  /** 订阅时登记的设备 ID，用于把推送排除给触发操作的那台设备。 */
  deviceId?: string;
  send(message: string): void;
  isOpen(): boolean;
}

export interface RevisionNotification {
  type: 'revision';
  spaceId: string;
  revision: string;
}

export class RealtimeHub {
  private readonly rooms = new Map<string, Set<RealtimeConnection>>();
  private readonly subscriptions = new Map<string, Set<string>>();

  subscribe(connection: RealtimeConnection, spaceId: string, deviceId?: string): void {
    if (deviceId) connection.deviceId = deviceId;
    let room = this.rooms.get(spaceId);
    if (!room) {
      room = new Set();
      this.rooms.set(spaceId, room);
    }
    room.add(connection);
    let subs = this.subscriptions.get(connection.id);
    if (!subs) {
      subs = new Set();
      this.subscriptions.set(connection.id, subs);
    }
    subs.add(spaceId);
  }

  unsubscribe(connection: RealtimeConnection, spaceId: string): void {
    this.rooms.get(spaceId)?.delete(connection);
    const subs = this.subscriptions.get(connection.id);
    if (subs) {
      subs.delete(spaceId);
      if (subs.size === 0) this.subscriptions.delete(connection.id);
    }
  }

  removeConnection(connection: RealtimeConnection): void {
    const subs = this.subscriptions.get(connection.id);
    if (subs) {
      for (const spaceId of subs) {
        this.rooms.get(spaceId)?.delete(connection);
      }
    }
    this.subscriptions.delete(connection.id);
  }

  /**
   * 广播 revision 提示。
   * @param excludeDeviceId 触发本次操作的设备 ID：自己提交的变更不需要再通知自己，
   *   否则该设备会为每条上传/删除提示各排一次完整同步，形成长时间的状态抖动。
   */
  publish(spaceId: string, revision: bigint, excludeDeviceId?: string): void {
    const room = this.rooms.get(spaceId);
    if (!room || room.size === 0) return;
    const message = JSON.stringify({
      type: 'revision',
      spaceId,
      revision: revision.toString(),
    } as RevisionNotification);
    for (const connection of room) {
      try {
        if (excludeDeviceId && connection.deviceId === excludeDeviceId) continue;
        if (connection.isOpen()) connection.send(message);
      } catch {
        // 单个连接写入失败不影响其他连接
      }
    }
  }

  /** 仅用于测试断言。 */
  roomSize(spaceId: string): number {
    return this.rooms.get(spaceId)?.size ?? 0;
  }

  /** 仅用于测试断言。 */
  get connectionCount(): number {
    return this.subscriptions.size;
  }
}
