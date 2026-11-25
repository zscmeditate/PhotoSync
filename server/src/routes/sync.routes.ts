import type { FastifyPluginAsync, FastifyRequest } from 'fastify';
import { z } from 'zod';
import { authUserId, requireAuth } from '../lib/auth';
import { fileNotFound, objectNotFound, spaceNotFound } from '../lib/errors';
import { RangeNotSatisfiableError } from '../lib/object-store';
import type { JwtService } from '../lib/jwt';
import type { RealtimeHub } from '../lib/realtime';
import { parse } from '../lib/validation';
import type { SyncService } from '../services/sync.service';
import {
  bearerSecurity,
  errorResponses,
  eventDtoSchema,
  fileDtoSchema,
} from './schemas';

export interface SyncRoutesOptions {
  syncService: SyncService;
  jwt: JwtService;
  hub?: RealtimeHub;
}

const uuidSchema = z.string().uuid();
const spaceParamsSchema = z.object({ spaceId: uuidSchema });
const fileParamsSchema = z.object({ fileId: uuidSchema });
const objectParamsSchema = z.object({ objectId: uuidSchema });
const deviceQuerySchema = z.object({ deviceId: uuidSchema });
const changesQuerySchema = z.object({
  deviceId: uuidSchema,
  sinceRevision: z.string().regex(/^[0-9]+$/).optional().default('0'),
  limit: z.coerce.number().int().min(1).max(500).optional().default(100),
});
const relativePathPattern = /^(?!\/)(?!.*\/\/)(?!.*(?:^|\/)\.\.(?:\/|$))[^\s\\]+$/;
const operationBodySchema = z.object({
  deviceId: uuidSchema,
  operationId: z.string().min(1).max(128),
  // 类型不在路由层枚举：未知类型统一由 SyncService 返回 OPERATION_UNSUPPORTED，
  // 以便未来扩展事件类型时无需修改路由校验。
  type: z.string().min(1).max(32),
  uploadId: uuidSchema.optional(),
  fileId: uuidSchema.optional(),
  baseVersion: z.number().int().min(0),
  newRelativePath: z
    .string()
    .min(1)
    .max(512)
    .regex(relativePathPattern, 'newRelativePath is not a safe relative path')
    .optional(),
  confirmBulkDelete: z.boolean().optional(),
});
const ackBodySchema = z.object({
  deviceId: uuidSchema,
  lastAppliedRevision: z.string().regex(/^[0-9]+$/),
});
const fileByPathQuerySchema = z.object({
  deviceId: uuidSchema,
  relativePath: z
    .string()
    .min(1)
    .max(512)
    .regex(relativePathPattern, 'relativePath is not a safe relative path'),
});

const spaceParams = {
  type: 'object',
  required: ['spaceId'],
  properties: { spaceId: { type: 'string' } },
} as const;
const deviceQuery = {
  type: 'object',
  required: ['deviceId'],
  additionalProperties: false,
  properties: { deviceId: { type: 'string' } },
} as const;
const commonErrors = {
  400: errorResponses[400],
  401: errorResponses[401],
  403: errorResponses[403],
  404: errorResponses[404],
  409: errorResponses[409],
  500: errorResponses[500],
} as const;
const operationResponseSchema = {
  type: 'object',
  required: ['file', 'event', 'duplicate'],
  additionalProperties: false,
  properties: {
    file: fileDtoSchema,
    event: eventDtoSchema,
    duplicate: { type: 'boolean' },
  },
} as const;

function spaceIdOf(params: unknown): string {
  const result = spaceParamsSchema.safeParse(params);
  if (!result.success) {
    throw spaceNotFound();
  }
  return result.data.spaceId;
}

const syncRoutes: FastifyPluginAsync<SyncRoutesOptions> = async (app, opts) => {
  const auth = requireAuth(opts.jwt);

  app.post(
    '/spaces/:spaceId/operations',
    {
      preHandler: auth,
      schema: {
        tags: ['sync'],
        summary: '提交文件同步操作（新增/修改/删除/重命名，持久幂等）',
        security: bearerSecurity,
        params: spaceParams,
        body: {
          type: 'object',
          required: ['deviceId', 'operationId', 'type', 'baseVersion'],
          additionalProperties: false,
          properties: {
            deviceId: { type: 'string' },
            operationId: { type: 'string', minLength: 1, maxLength: 128 },
            type: {
              type: 'string',
              description:
                '支持 file_created / file_updated / file_deleted / file_renamed；其他类型返回 OPERATION_UNSUPPORTED',
            },
            uploadId: { type: 'string', description: 'file_created / file_updated 必填' },
            fileId: {
              type: 'string',
              description: 'file_updated / file_deleted / file_renamed 必填',
            },
            baseVersion: {
              type: 'integer',
              minimum: 0,
              description: '新增为 0；修改/删除/重命名为已知当前版本，服务端做乐观锁校验',
            },
            newRelativePath: { type: 'string', description: 'file_renamed 必填' },
            confirmBulkDelete: {
              type: 'boolean',
              description: '大量删除保护：窗口期内删除数达到阈值后需显式传 true',
            },
          },
        },
        response: { 200: operationResponseSchema, 201: operationResponseSchema, ...commonErrors },
      },
    },
    async (request: FastifyRequest, reply) => {
      const spaceId = spaceIdOf(request.params);
      const body = parse(operationBodySchema, request.body);
      const result = await opts.syncService.commitOperation(authUserId(request), spaceId, body);
      // 幂等重放不产生新 revision，无需推送。
      if (!result.duplicate) {
        opts.hub?.publish(spaceId, BigInt(result.event.revision), body.deviceId);
      }
      return reply.status(result.duplicate ? 200 : 201).send(result);
    },
  );

  app.get(
    '/spaces/:spaceId/changes',
    {
      preHandler: auth,
      schema: {
        tags: ['sync'],
        summary: '按 revision 升序增量拉取事件',
        security: bearerSecurity,
        params: spaceParams,
        querystring: {
          type: 'object',
          required: ['deviceId'],
          additionalProperties: false,
          properties: {
            deviceId: { type: 'string' },
            sinceRevision: { type: 'string', pattern: '^[0-9]+$', default: '0' },
            limit: { type: 'integer', minimum: 1, maximum: 500, default: 100 },
          },
        },
        response: {
          200: {
            type: 'object',
            required: ['events', 'nextRevision', 'hasMore', 'serverRevision'],
            additionalProperties: false,
            properties: {
              events: { type: 'array', items: eventDtoSchema },
              nextRevision: { type: 'string' },
              hasMore: { type: 'boolean' },
              serverRevision: { type: 'string' },
            },
          },
          ...commonErrors,
        },
      },
    },
    async (request: FastifyRequest) => {
      const spaceId = spaceIdOf(request.params);
      const query = parse(changesQuerySchema, request.query);
      return opts.syncService.listChanges(
        authUserId(request),
        spaceId,
        query.deviceId,
        BigInt(query.sinceRevision),
        query.limit,
      );
    },
  );

  app.post(
    '/spaces/:spaceId/ack',
    {
      preHandler: auth,
      schema: {
        tags: ['sync'],
        summary: '推进设备已应用事件游标',
        security: bearerSecurity,
        params: spaceParams,
        body: {
          type: 'object',
          required: ['deviceId', 'lastAppliedRevision'],
          additionalProperties: false,
          properties: {
            deviceId: { type: 'string' },
            lastAppliedRevision: { type: 'string', pattern: '^[0-9]+$' },
          },
        },
        response: {
          200: {
            type: 'object',
            required: ['spaceId', 'lastAppliedRevision', 'serverRevision'],
            additionalProperties: false,
            properties: {
              spaceId: { type: 'string' },
              lastAppliedRevision: { type: 'string' },
              serverRevision: { type: 'string' },
            },
          },
          ...commonErrors,
        },
      },
    },
    async (request: FastifyRequest) => {
      const spaceId = spaceIdOf(request.params);
      const body = parse(ackBodySchema, request.body);
      return opts.syncService.acknowledge(
        authUserId(request),
        spaceId,
        body.deviceId,
        BigInt(body.lastAppliedRevision),
      );
    },
  );

  app.get(
    '/spaces/:spaceId/status',
    {
      preHandler: auth,
      schema: {
        tags: ['sync'],
        summary: '查询设备同步状态',
        security: bearerSecurity,
        params: spaceParams,
        querystring: deviceQuery,
        response: {
          200: {
            type: 'object',
            required: [
              'spaceId',
              'serverRevision',
              'lastAppliedRevision',
              'pendingEvents',
              'activeFileCount',
            ],
            additionalProperties: false,
            properties: {
              spaceId: { type: 'string' },
              serverRevision: { type: 'string' },
              lastAppliedRevision: { type: 'string' },
              pendingEvents: { type: 'integer', minimum: 0 },
              activeFileCount: { type: 'integer', minimum: 0 },
            },
          },
          ...commonErrors,
        },
      },
    },
    async (request: FastifyRequest) => {
      const spaceId = spaceIdOf(request.params);
      const query = parse(deviceQuerySchema, request.query);
      return opts.syncService.getStatus(authUserId(request), spaceId, query.deviceId);
    },
  );

  app.get(
    '/objects/:objectId/download',
    {
      preHandler: auth,
      schema: {
        tags: ['objects'],
        summary: '流式下载已提交文件当前对象或历史版本对象',
        security: bearerSecurity,
        params: {
          type: 'object',
          required: ['objectId'],
          properties: { objectId: { type: 'string' } },
        },
        querystring: deviceQuery,
        response: commonErrors,
      },
    },
    async (request: FastifyRequest, reply) => {
      const params = objectParamsSchema.safeParse(request.params);
      if (!params.success) {
        throw objectNotFound();
      }
      const query = parse(deviceQuerySchema, request.query);
      // 支持 HTTP Range 断点续传：客户端大文件下载中断后可从断点继续。
      const rangeHeader =
        typeof request.headers.range === 'string' && /^bytes=\d+-\d*$/.test(request.headers.range)
          ? request.headers.range
          : undefined;
      let result: Awaited<ReturnType<typeof opts.syncService.downloadObject>>;
      try {
        result = await opts.syncService.downloadObject(
          authUserId(request),
          params.data.objectId,
          query.deviceId,
          rangeHeader,
        );
      } catch (error) {
        if (error instanceof RangeNotSatisfiableError) {
          reply.code(416).header('Content-Type', 'application/json').send({
            error: 'RANGE_NOT_SATISFIABLE',
            message: 'Requested range not satisfiable',
          });
          return reply;
        }
        throw error;
      }
      const fallbackName = result.metadata.fileName.replace(/[^a-zA-Z0-9._-]/g, '_') || 'download';
      if (rangeHeader) {
        reply.code(206).header('Content-Range', result.content.contentRange ?? 'bytes 0-/*');
      }
      reply
        .header('Accept-Ranges', 'bytes')
        .header('Content-Type', 'application/octet-stream')
        .header('Content-Length', result.content.contentLength ?? Number(result.metadata.size))
        .header('X-SHA256', result.metadata.sha256)
        .header(
          'Content-Disposition',
          `attachment; filename="${fallbackName}"; filename*=UTF-8''${encodeURIComponent(result.metadata.fileName)}`,
        );
      return reply.send(result.content.body);
    },
  );

  app.get(
    '/spaces/:spaceId/files/by-path',
    {
      preHandler: auth,
      schema: {
        tags: ['files'],
        summary: '按相对路径查询当前占用该路径的云端文件（未占用返回 null）',
        security: bearerSecurity,
        params: spaceParams,
        querystring: {
          type: 'object',
          required: ['deviceId', 'relativePath'],
          additionalProperties: false,
          properties: {
            deviceId: { type: 'string' },
            relativePath: { type: 'string', minLength: 1, maxLength: 512 },
          },
        },
        response: {
          200: {
            type: 'object',
            required: ['file'],
            additionalProperties: false,
            properties: {
              file: { ...fileDtoSchema, nullable: true },
            },
          },
          ...commonErrors,
        },
      },
    },
    async (request: FastifyRequest) => {
      const spaceId = spaceIdOf(request.params);
      const query = parse(fileByPathQuerySchema, request.query);
      return opts.syncService.findFileByPath(
        authUserId(request),
        spaceId,
        query.deviceId,
        query.relativePath,
      );
    },
  );

  app.get(
    '/files/:fileId/versions',
    {
      preHandler: auth,
      schema: {
        tags: ['files'],
        summary: '按版本倒序列出文件历史',
        security: bearerSecurity,
        params: {
          type: 'object',
          required: ['fileId'],
          properties: { fileId: { type: 'string' } },
        },
        querystring: deviceQuery,
        response: {
          200: {
            type: 'object',
            required: ['versions'],
            additionalProperties: false,
            properties: {
              versions: {
                type: 'array',
                items: {
                  type: 'object',
                  required: [
                    'fileId',
                    'version',
                    'objectId',
                    'size',
                    'sha256',
                    'createdAt',
                    'createdByDeviceId',
                  ],
                  additionalProperties: false,
                  properties: {
                    fileId: { type: 'string' },
                    version: { type: 'integer' },
                    objectId: { type: 'string' },
                    size: { type: 'string' },
                    sha256: { type: 'string' },
                    createdAt: { type: 'string' },
                    createdByDeviceId: { type: 'string' },
                  },
                },
              },
            },
          },
          ...commonErrors,
        },
      },
    },
    async (request: FastifyRequest) => {
      const params = fileParamsSchema.safeParse(request.params);
      if (!params.success) {
        throw fileNotFound();
      }
      const query = parse(deviceQuerySchema, request.query);
      return opts.syncService.listVersions(authUserId(request), params.data.fileId, query.deviceId);
    },
  );
};

export default syncRoutes;
