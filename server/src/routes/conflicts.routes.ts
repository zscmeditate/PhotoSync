import type { FastifyPluginAsync, FastifyRequest } from 'fastify';
import { z } from 'zod';
import { authUserId, requireAuth } from '../lib/auth';
import { conflictNotFound, spaceNotFound } from '../lib/errors';
import type { JwtService } from '../lib/jwt';
import type { RealtimeHub } from '../lib/realtime';
import { parse } from '../lib/validation';
import type { SyncService } from '../services/sync.service';
import { bearerSecurity, errorResponses, eventDtoSchema, fileDtoSchema } from './schemas';

export interface ConflictRoutesOptions {
  syncService: SyncService;
  jwt: JwtService;
  hub?: RealtimeHub;
}

const uuidSchema = z.string().uuid();
const spaceParamsSchema = z.object({ spaceId: uuidSchema });
const conflictParamsSchema = z.object({ conflictId: uuidSchema });
const deviceQuerySchema = z.object({ deviceId: uuidSchema });
const resolveBodySchema = z.object({
  deviceId: uuidSchema,
  operationId: z.string().min(1).max(128),
  resolution: z.enum(['server', 'client', 'both']),
});

const commonErrors = {
  400: errorResponses[400],
  401: errorResponses[401],
  403: errorResponses[403],
  404: errorResponses[404],
  409: errorResponses[409],
  500: errorResponses[500],
} as const;

const conflictDtoSchema = {
  type: 'object',
  required: [
    'conflictId',
    'spaceId',
    'fileId',
    'relativePath',
    'fileName',
    'serverVersion',
    'submitted',
    'status',
    'resolvedAt',
    'createdAt',
  ],
  additionalProperties: false,
  properties: {
    conflictId: { type: 'string' },
    spaceId: { type: 'string' },
    fileId: { type: 'string' },
    relativePath: { type: 'string' },
    fileName: { type: 'string' },
    serverVersion: { type: 'integer' },
    submitted: {
      type: 'object',
      required: [
        'objectId',
        'size',
        'sha256',
        'relativePath',
        'fileName',
        'uploadId',
        'deviceId',
      ],
      additionalProperties: false,
      properties: {
        objectId: { type: 'string' },
        size: { type: 'string' },
        sha256: { type: 'string' },
        relativePath: { type: 'string' },
        fileName: { type: 'string' },
        uploadId: { type: 'string', nullable: true },
        deviceId: { type: 'string' },
      },
    },
    status: { type: 'string' },
    resolvedAt: { type: 'string', nullable: true },
    createdAt: { type: 'string' },
  },
} as const;

const conflictRoutes: FastifyPluginAsync<ConflictRoutesOptions> = async (app, opts) => {
  const auth = requireAuth(opts.jwt);

  app.get(
    '/spaces/:spaceId/conflicts',
    {
      preHandler: auth,
      schema: {
        tags: ['conflicts'],
        summary: '列出空间内冲突（按产生时间倒序）',
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
            required: ['conflicts'],
            additionalProperties: false,
            properties: { conflicts: { type: 'array', items: conflictDtoSchema } },
          },
          ...commonErrors,
        },
      },
    },
    async (request: FastifyRequest) => {
      const params = spaceParamsSchema.safeParse(request.params);
      if (!params.success) throw spaceNotFound();
      const query = parse(deviceQuerySchema, request.query);
      return opts.syncService.listConflicts(
        authUserId(request),
        params.data.spaceId,
        query.deviceId,
      );
    },
  );

  app.post(
    '/conflicts/:conflictId/resolve',
    {
      preHandler: auth,
      schema: {
        tags: ['conflicts'],
        summary: '解决冲突：server 保留服务器版本，client 采纳提交版本，both 生成冲突副本',
        security: bearerSecurity,
        params: {
          type: 'object',
          required: ['conflictId'],
          properties: { conflictId: { type: 'string' } },
        },
        body: {
          type: 'object',
          required: ['deviceId', 'operationId', 'resolution'],
          additionalProperties: false,
          properties: {
            deviceId: { type: 'string' },
            operationId: { type: 'string', minLength: 1, maxLength: 128 },
            resolution: {
              type: 'string',
              enum: ['server', 'client', 'both'],
              description: 'server=保留服务器版本；client=采纳提交版本；both=两者都保留',
            },
          },
        },
        response: {
          200: {
            type: 'object',
            required: ['conflict', 'file', 'event'],
            additionalProperties: false,
            properties: {
              conflict: conflictDtoSchema,
              file: { anyOf: [fileDtoSchema, { type: 'null' }] },
              event: eventDtoSchema,
            },
          },
          ...commonErrors,
        },
      },
    },
    async (request: FastifyRequest) => {
      const params = conflictParamsSchema.safeParse(request.params);
      if (!params.success) throw conflictNotFound();
      const body = parse(resolveBodySchema, request.body);
      const result = await opts.syncService.resolveConflict(
        authUserId(request),
        params.data.conflictId,
        body.deviceId,
        body.resolution,
        body.operationId,
      );
      opts.hub?.publish(result.conflict.spaceId, BigInt(result.event.revision), body.deviceId);
      return result;
    },
  );
};

export default conflictRoutes;
