import type { FastifyPluginAsync, FastifyRequest } from 'fastify';
import { z } from 'zod';
import { authUserId, requireAuth } from '../lib/auth';
import type { JwtService } from '../lib/jwt';
import { parse } from '../lib/validation';
import type { SyncLockService } from '../services/sync-lock.service';
import { bearerSecurity, errorResponses } from './schemas';

export interface SyncLockRoutesOptions {
  syncLockService: SyncLockService;
  jwt: JwtService;
}

const lockBodySchema = z.object({ deviceId: z.string().uuid() });

const lockResponseSchema = {
  type: 'object',
  required: ['acquired'],
  additionalProperties: false,
  properties: {
    acquired: { type: 'boolean' },
    holderDeviceId: { type: ['string', 'null'], description: '当前持锁设备' },
    expiresAt: {
      type: ['integer', 'null'],
      description: '持锁到期时间（epoch 毫秒）',
    },
  },
} as const;

const commonErrors = {
  400: errorResponses[400],
  401: errorResponses[401],
  500: errorResponses[500],
} as const;

/** 账号级同步锁：抢锁 / 心跳续期 / 释放。 */
const syncLockRoutes: FastifyPluginAsync<SyncLockRoutesOptions> = async (app, opts) => {
  const auth = requireAuth(opts.jwt);

  const bodySchema = {
    type: 'object',
    required: ['deviceId'],
    additionalProperties: false,
    properties: { deviceId: { type: 'string' } },
  } as const;

  const register = (
    path: string,
    summary: string,
    handle: (userId: string, deviceId: string) => unknown,
  ) => {
    app.post(
      path,
      {
        preHandler: auth,
        schema: {
          tags: ['sync'],
          summary,
          security: bearerSecurity,
          body: bodySchema,
          response: { 200: lockResponseSchema, ...commonErrors },
        },
      },
      async (request: FastifyRequest) => {
        const body = parse(lockBodySchema, request.body);
        return handle(authUserId(request), body.deviceId);
      },
    );
  };

  register(
    '/sync-lock/acquire',
    '抢账号级同步锁（其他设备同步期间返回 acquired=false，客户端等待后重试）',
    (userId, deviceId) => opts.syncLockService.acquire(userId, deviceId),
  );
  register(
    '/sync-lock/heartbeat',
    '同步锁心跳续期（仅持锁设备有效；锁被抢走或过期时 acquired=false）',
    (userId, deviceId) => opts.syncLockService.heartbeat(userId, deviceId),
  );
  register(
    '/sync-lock/release',
    '释放同步锁（仅持锁本人生效）',
    (userId, deviceId) => {
      opts.syncLockService.release(userId, deviceId);
      return { acquired: false, holderDeviceId: null, expiresAt: null };
    },
  );
};

export default syncLockRoutes;
