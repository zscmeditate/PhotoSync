import { randomUUID } from 'node:crypto';
import type { FastifyPluginAsync } from 'fastify';
import type WebSocket from 'ws';
import type { JwtService } from '../lib/jwt';
import type { RealtimeHub } from '../lib/realtime';
import type { DeviceRepository, SpaceRepository } from '../repositories/ports';

export interface WsRoutesOptions {
  hub: RealtimeHub;
  jwt: JwtService;
  spaces: SpaceRepository;
  devices: DeviceRepository;
}

interface ControlMessage {
  type: 'subscribe' | 'unsubscribe';
  spaceId?: unknown;
  deviceId?: unknown;
}

/**
 * WebSocket 实时提示通道。
 *
 * 连接：GET /api/v1/ws?token=<accessToken>
 * 客户端发送：{"type":"subscribe","spaceId":"...","deviceId":"..."}
 *   服务端校验设备归属与空间成员身份后加入房间。
 * 服务端推送：{"type":"revision","spaceId":"...","revision":"123"}
 *   仅提示有新 revision，客户端通过 changes 接口拉取增量，保证顺序与幂等。
 */
const wsRoutes: FastifyPluginAsync<WsRoutesOptions> = async (app, opts) => {
  app.get('/ws', { websocket: true }, (socket: WebSocket, request) => {
    const token = (request.query as { token?: string } | undefined)?.token;
    let userId = '';
    try {
      userId = opts.jwt.verifyAccessToken(token ?? '').userId;
    } catch {
      socket.close(1008, 'Unauthorized');
      return;
    }

    const conn = {
      id: randomUUID(),
      userId,
      send: (message: string) => socket.send(message),
      isOpen: () => socket.readyState === 1,
    };

    socket.on('message', async (raw) => {
      let message: ControlMessage;
      try {
        message = JSON.parse((raw as Buffer).toString('utf8')) as ControlMessage;
      } catch {
        return;
      }
      if (
        !message ||
        (message.type !== 'subscribe' && message.type !== 'unsubscribe') ||
        typeof message.spaceId !== 'string' ||
        typeof message.deviceId !== 'string'
      ) {
        return;
      }
      const device = await opts.devices.findById(message.deviceId);
      if (!device || device.userId !== userId || device.credentialStatus !== 'active') {
        socket.close(1008, 'Forbidden');
        return;
      }
      const member = await opts.spaces.findMember(message.spaceId, message.deviceId);
      if (!member) {
        socket.close(1008, 'Forbidden');
        return;
      }
      if (message.type === 'subscribe') {
        opts.hub.subscribe(conn, message.spaceId, message.deviceId);
      } else {
        opts.hub.unsubscribe(conn, message.spaceId);
      }
    });

    socket.on('close', () => opts.hub.removeConnection(conn));
  });
};

export default wsRoutes;
