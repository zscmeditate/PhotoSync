import type { FastifyPluginAsync, FastifyRequest } from 'fastify';
import { z } from 'zod';
import { authUserId, requireAuth } from '../lib/auth';
import { deviceNotFound, invalidDevice } from '../lib/errors';
import type { JwtService } from '../lib/jwt';
import { parse } from '../lib/validation';
import type { DeviceService } from '../services/device.service';
import { bearerSecurity, deviceSchema, errorResponses } from './schemas';

export interface DevicesRoutesOptions {
  deviceService: DeviceService;
  jwt: JwtService;
}

const registerDeviceBodySchema = z.object({
  deviceName: z.string().min(1).max(64),
  /** 本机已登记过的设备 ID：传入则原地复用，不再新建设备 */
  deviceId: z.string().uuid('invalid deviceId').optional(),
});

const renameDeviceBodySchema = z.object({
  deviceName: z.string().min(1).max(64),
});

const deviceIdParamsSchema = z.string().uuid('invalid deviceId');

const deviceObjectSchema = {
  type: 'object',
  required: ['device'],
  additionalProperties: true,
  properties: { device: deviceSchema },
} as const;

const deviceListSchema = {
  type: 'object',
  required: ['devices'],
  additionalProperties: true,
  properties: {
    devices: { type: 'array', items: deviceSchema },
  },
} as const;

function parseDeviceId(raw: unknown): string {
  const result = deviceIdParamsSchema.safeParse((raw as { deviceId?: unknown })?.deviceId);
  if (!result.success) {
    // 非法 UUID 不可能属于当前用户，按契约返回 404 DEVICE_NOT_FOUND
    throw deviceNotFound();
  }
  return result.data;
}

/** 从 X-Device-Id 读取本机设备；缺失或非法时返回 400 INVALID_DEVICE */
function requestDeviceId(request: FastifyRequest): string {
  const result = deviceIdParamsSchema.safeParse(request.headers['x-device-id']);
  if (!result.success) {
    throw invalidDevice();
  }
  return result.data;
}

const devicesRoutes: FastifyPluginAsync<DevicesRoutesOptions> = async (app, opts) => {
  const { deviceService, jwt } = opts;
  const auth = requireAuth(jwt);

  app.post(
    '/devices/register',
    {
      preHandler: auth,
      schema: {
        tags: ['devices'],
        summary: '注册设备',
        security: bearerSecurity,
        body: {
          type: 'object',
          required: ['deviceName'],
          additionalProperties: false,
          properties: {
            deviceName: { type: 'string', minLength: 1, maxLength: 64 },
            deviceId: { type: 'string', format: 'uuid', description: '已有设备 UUID（原地复用）' },
          },
        },
        response: {
          201: deviceObjectSchema,
          400: errorResponses[400],
          401: errorResponses[401],
          500: errorResponses[500],
        },
      },
    },
    async (request: FastifyRequest, reply) => {
      const body = parse(registerDeviceBodySchema, request.body);
      const device = await deviceService.register(
        authUserId(request),
        body.deviceName,
        body.deviceId,
      );
      return reply.status(201).send({ device });
    },
  );

  app.get(
    '/devices',
    {
      preHandler: auth,
      schema: {
        tags: ['devices'],
        summary: '设备列表',
        security: bearerSecurity,
        response: {
          200: deviceListSchema,
          401: errorResponses[401],
          500: errorResponses[500],
        },
      },
    },
    async (request: FastifyRequest) => {
      // 客户端用 X-Device-Id 标识本机，顺带刷新在线时间（节流在 service 内）。
      const deviceId = request.headers['x-device-id'];
      if (typeof deviceId === 'string' && deviceId.length > 0) {
        await deviceService.touch(authUserId(request), deviceId);
      }
      const devices = await deviceService.list(authUserId(request));
      return { devices };
    },
  );

  app.post(
    '/devices/primary',
    {
      preHandler: auth,
      schema: {
        tags: ['devices'],
        summary: '将本机设为主设备（无主设备时才能抢占）',
        security: bearerSecurity,
        response: {
          200: deviceObjectSchema,
          400: errorResponses[400],
          401: errorResponses[401],
          409: errorResponses[409],
          500: errorResponses[500],
        },
      },
    },
    async (request: FastifyRequest) => {
      const device = await deviceService.claimPrimary(
        authUserId(request),
        requestDeviceId(request),
      );
      return { device };
    },
  );

  app.delete(
    '/devices/primary',
    {
      preHandler: auth,
      schema: {
        tags: ['devices'],
        summary: '本机放弃主设备身份',
        security: bearerSecurity,
        response: {
          400: errorResponses[400],
          401: errorResponses[401],
          403: errorResponses[403],
          500: errorResponses[500],
        },
      },
    },
    async (request: FastifyRequest, reply) => {
      await deviceService.releasePrimary(authUserId(request), requestDeviceId(request));
      return reply.status(204).send();
    },
  );

  app.post(
    '/devices/rejoin',
    {
      preHandler: auth,
      schema: {
        tags: ['devices'],
        summary: '被移除设备申请重新加入（等待主设备同意）',
        security: bearerSecurity,
        response: {
          200: deviceObjectSchema,
          400: errorResponses[400],
          401: errorResponses[401],
          404: errorResponses[404],
          500: errorResponses[500],
        },
      },
    },
    async (request: FastifyRequest) => {
      const device = await deviceService.requestRejoin(
        authUserId(request),
        requestDeviceId(request),
      );
      return { device };
    },
  );

  app.post(
    '/devices/:deviceId/rejoin/approve',
    {
      preHandler: auth,
      schema: {
        tags: ['devices'],
        summary: '主设备同意被移除设备重新加入',
        security: bearerSecurity,
        params: {
          type: 'object',
          required: ['deviceId'],
          properties: { deviceId: { type: 'string', description: '设备 UUID' } },
        },
        response: {
          200: deviceObjectSchema,
          400: errorResponses[400],
          401: errorResponses[401],
          403: errorResponses[403],
          404: errorResponses[404],
          500: errorResponses[500],
        },
      },
    },
    async (request: FastifyRequest) => {
      const deviceId = parseDeviceId(request.params);
      const device = await deviceService.approveRejoin(
        authUserId(request),
        requestDeviceId(request),
        deviceId,
      );
      return { device };
    },
  );

  app.post(
    '/devices/:deviceId/rejoin/reject',
    {
      preHandler: auth,
      schema: {
        tags: ['devices'],
        summary: '主设备拒绝被移除设备的重新加入申请',
        security: bearerSecurity,
        params: {
          type: 'object',
          required: ['deviceId'],
          properties: { deviceId: { type: 'string', description: '设备 UUID' } },
        },
        response: {
          400: errorResponses[400],
          401: errorResponses[401],
          403: errorResponses[403],
          404: errorResponses[404],
          500: errorResponses[500],
        },
      },
    },
    async (request: FastifyRequest, reply) => {
      const deviceId = parseDeviceId(request.params);
      await deviceService.rejectRejoin(
        authUserId(request),
        requestDeviceId(request),
        deviceId,
      );
      return reply.status(204).send();
    },
  );

  app.patch(
    '/devices/:deviceId',
    {
      preHandler: auth,
      schema: {
        tags: ['devices'],
        summary: '重命名设备',
        security: bearerSecurity,
        params: {
          type: 'object',
          required: ['deviceId'],
          properties: { deviceId: { type: 'string', description: '设备 UUID' } },
        },
        body: {
          type: 'object',
          required: ['deviceName'],
          additionalProperties: false,
          properties: {
            deviceName: { type: 'string', minLength: 1, maxLength: 64 },
          },
        },
        response: {
          200: deviceObjectSchema,
          400: errorResponses[400],
          401: errorResponses[401],
          403: errorResponses[403],
          404: errorResponses[404],
          500: errorResponses[500],
        },
      },
    },
    async (request: FastifyRequest) => {
      const deviceId = parseDeviceId(request.params);
      const body = parse(renameDeviceBodySchema, request.body);
      const device = await deviceService.rename(
        authUserId(request),
        requestDeviceId(request),
        deviceId,
        body.deviceName,
      );
      return { device };
    },
  );

  app.delete(
    '/devices/:deviceId',
    {
      preHandler: auth,
      schema: {
        tags: ['devices'],
        summary: '主设备移除从设备（吊销凭据）',
        security: bearerSecurity,
        params: {
          type: 'object',
          required: ['deviceId'],
          properties: { deviceId: { type: 'string', description: '设备 UUID' } },
        },
        response: {
          400: errorResponses[400],
          401: errorResponses[401],
          403: errorResponses[403],
          404: errorResponses[404],
          500: errorResponses[500],
        },
      },
    },
    async (request: FastifyRequest, reply) => {
      const deviceId = parseDeviceId(request.params);
      await deviceService.revoke(
        authUserId(request),
        requestDeviceId(request),
        deviceId,
      );
      return reply.status(204).send();
    },
  );
};

export default devicesRoutes;
