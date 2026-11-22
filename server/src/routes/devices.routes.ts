import type { FastifyPluginAsync, FastifyRequest } from 'fastify';
import { z } from 'zod';
import { authUserId, requireAuth } from '../lib/auth';
import { deviceNotFound } from '../lib/errors';
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
      const device = await deviceService.register(authUserId(request), body.deviceName);
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
      const devices = await deviceService.list(authUserId(request));
      return { devices };
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
          404: errorResponses[404],
          500: errorResponses[500],
        },
      },
    },
    async (request: FastifyRequest) => {
      const deviceId = parseDeviceId(request.params);
      const body = parse(renameDeviceBodySchema, request.body);
      const device = await deviceService.rename(authUserId(request), deviceId, body.deviceName);
      return { device };
    },
  );

  app.delete(
    '/devices/:deviceId',
    {
      preHandler: auth,
      schema: {
        tags: ['devices'],
        summary: '注销设备（吊销凭据）',
        security: bearerSecurity,
        params: {
          type: 'object',
          required: ['deviceId'],
          properties: { deviceId: { type: 'string', description: '设备 UUID' } },
        },
        response: {
          401: errorResponses[401],
          404: errorResponses[404],
          500: errorResponses[500],
        },
      },
    },
    async (request: FastifyRequest, reply) => {
      const deviceId = parseDeviceId(request.params);
      await deviceService.revoke(authUserId(request), deviceId);
      return reply.status(204).send();
    },
  );
};

export default devicesRoutes;
