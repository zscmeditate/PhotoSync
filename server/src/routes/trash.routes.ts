import type { FastifyPluginAsync, FastifyRequest } from 'fastify';
import { z } from 'zod';
import { authUserId, requireAuth } from '../lib/auth';
import { spaceNotFound, trashNotFound } from '../lib/errors';
import type { JwtService } from '../lib/jwt';
import type { RealtimeHub } from '../lib/realtime';
import { parse } from '../lib/validation';
import type { SyncService } from '../services/sync.service';
import { bearerSecurity, errorResponses, eventDtoSchema, fileDtoSchema } from './schemas';

export interface TrashRoutesOptions {
  syncService: SyncService;
  jwt: JwtService;
  hub?: RealtimeHub;
}

const uuidSchema = z.string().uuid();
const spaceParamsSchema = z.object({ spaceId: uuidSchema });
const trashParamsSchema = z.object({ trashId: uuidSchema });
const deviceQuerySchema = z.object({ deviceId: uuidSchema });
const restoreBodySchema = z.object({
  deviceId: uuidSchema,
  operationId: z.string().min(1).max(128),
});

const commonErrors = {
  400: errorResponses[400],
  401: errorResponses[401],
  403: errorResponses[403],
  404: errorResponses[404],
  409: errorResponses[409],
  500: errorResponses[500],
} as const;

const trashItemDtoSchema = {
  type: 'object',
  required: [
    'trashId',
    'spaceId',
    'fileId',
    'relativePath',
    'fileName',
    'size',
    'sha256',
    'version',
    'deletedByDeviceId',
    'deletedAt',
    'purgeAfter',
  ],
  additionalProperties: false,
  properties: {
    trashId: { type: 'string' },
    spaceId: { type: 'string' },
    fileId: { type: 'string' },
    relativePath: { type: 'string' },
    fileName: { type: 'string' },
    size: { type: 'string', pattern: '^[0-9]+$' },
    sha256: { type: 'string' },
    version: { type: 'integer' },
    deletedByDeviceId: { type: 'string' },
    deletedAt: { type: 'string' },
    purgeAfter: { type: 'string' },
  },
} as const;

const trashRoutes: FastifyPluginAsync<TrashRoutesOptions> = async (app, opts) => {
  const auth = requireAuth(opts.jwt);

  app.get(
    '/spaces/:spaceId/trash',
    {
      preHandler: auth,
      schema: {
        tags: ['trash'],
        summary: '列出空间回收站条目（按删除时间倒序）',
        security: bearerSecurity,
        params: {
          type: 'object',
          required: ['spaceId'],
          properties: { spaceId: { type: 'string' } },
        },
        querystring: {
          type: 'object',
          required: ['deviceId'],
          additionalProperties: false,
          properties: { deviceId: { type: 'string' } },
        },
        response: {
          200: {
            type: 'object',
            required: ['items'],
            additionalProperties: false,
            properties: { items: { type: 'array', items: trashItemDtoSchema } },
          },
          ...commonErrors,
        },
      },
    },
    async (request: FastifyRequest) => {
      const params = spaceParamsSchema.safeParse(request.params);
      if (!params.success) throw spaceNotFound();
      const query = parse(deviceQuerySchema, request.query);
      return opts.syncService.listTrash(authUserId(request), params.data.spaceId, query.deviceId);
    },
  );

  app.post(
    '/trash/:trashId/restore',
    {
      preHandler: auth,
      schema: {
        tags: ['trash'],
        summary: '从回收站恢复文件（生成 file_restored 事件）',
        security: bearerSecurity,
        params: {
          type: 'object',
          required: ['trashId'],
          properties: { trashId: { type: 'string' } },
        },
        body: {
          type: 'object',
          required: ['deviceId', 'operationId'],
          additionalProperties: false,
          properties: {
            deviceId: { type: 'string' },
            operationId: { type: 'string', minLength: 1, maxLength: 128 },
          },
        },
        response: {
          200: {
            type: 'object',
            required: ['file', 'event'],
            additionalProperties: false,
            properties: { file: fileDtoSchema, event: eventDtoSchema },
          },
          ...commonErrors,
        },
      },
    },
    async (request: FastifyRequest) => {
      const params = trashParamsSchema.safeParse(request.params);
      if (!params.success) throw trashNotFound();
      const body = parse(restoreBodySchema, request.body);
      const result = await opts.syncService.restoreTrash(
        authUserId(request),
        params.data.trashId,
        body.deviceId,
        body.operationId,
      );
      opts.hub?.publish(result.file.spaceId, BigInt(result.event.revision), body.deviceId);
      return result;
    },
  );

  app.delete(
    '/trash/:trashId',
    {
      preHandler: auth,
      schema: {
        tags: ['trash'],
        summary: '彻底清理回收站条目及对象存储（不可恢复）',
        security: bearerSecurity,
        params: {
          type: 'object',
          required: ['trashId'],
          properties: { trashId: { type: 'string' } },
        },
        querystring: {
          type: 'object',
          required: ['deviceId'],
          additionalProperties: false,
          properties: { deviceId: { type: 'string' } },
        },
        response: {
          200: {
            type: 'object',
            required: ['purged', 'trashId'],
            additionalProperties: false,
            properties: {
              purged: { type: 'boolean' },
              trashId: { type: 'string' },
            },
          },
          ...commonErrors,
        },
      },
    },
    async (request: FastifyRequest) => {
      const params = trashParamsSchema.safeParse(request.params);
      if (!params.success) throw trashNotFound();
      const query = parse(deviceQuerySchema, request.query);
      return opts.syncService.purgeTrash(authUserId(request), params.data.trashId, query.deviceId);
    },
  );
};

export default trashRoutes;
