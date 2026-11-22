import type { FastifyPluginAsync, FastifyRequest } from 'fastify';
import { z } from 'zod';
import { authUserId, requireAuth } from '../lib/auth';
import { uploadNotFound, validationError } from '../lib/errors';
import type { JwtService } from '../lib/jwt';
import { parse } from '../lib/validation';
import type { TransferService } from '../services/transfer.service';
import { bearerSecurity, errorResponses, uploadResponseSchema, uploadSchema } from './schemas';

export interface UploadsRoutesOptions {
  transferService: TransferService;
  jwt: JwtService;
  uploadPartSize: number;
}

const uuidSchema = z.string().uuid();
const uploadParamsSchema = z.object({ uploadId: uuidSchema });
const partParamsSchema = z.object({
  uploadId: uuidSchema,
  partNumber: z.coerce.number().int(),
});
const deviceQuerySchema = z.object({ deviceId: uuidSchema });
const createUploadBodySchema = z.object({
  spaceId: uuidSchema,
  deviceId: uuidSchema,
  relativePath: z.string().min(1).max(512),
  size: z.number().int().nonnegative().max(Number.MAX_SAFE_INTEGER),
  sha256: z.string().regex(/^[0-9a-fA-F]{64}$/),
  partSize: z.number().int().nonnegative().optional(),
  idempotencyKey: z.string().min(1).max(128),
});
const completeBodySchema = z.object({
  deviceId: uuidSchema,
  idempotencyKey: z.string().min(1).max(128),
});

const uploadParams = {
  type: 'object',
  required: ['uploadId'],
  properties: { uploadId: { type: 'string' } },
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

function uploadIdOf(params: unknown): string {
  const result = uploadParamsSchema.safeParse(params);
  if (!result.success) {
    throw uploadNotFound();
  }
  return result.data.uploadId;
}

const uploadsRoutes: FastifyPluginAsync<UploadsRoutesOptions> = async (app, opts) => {
  const auth = requireAuth(opts.jwt);

  app.post(
    '/uploads',
    {
      preHandler: auth,
      schema: {
        tags: ['uploads'],
        summary: '创建或重放分片上传会话',
        security: bearerSecurity,
        body: {
          type: 'object',
          required: ['spaceId', 'deviceId', 'relativePath', 'size', 'sha256', 'idempotencyKey'],
          additionalProperties: false,
          properties: {
            spaceId: { type: 'string' },
            deviceId: { type: 'string' },
            relativePath: { type: 'string', minLength: 1, maxLength: 512 },
            size: { type: 'integer', minimum: 0 },
            sha256: { type: 'string', pattern: '^[0-9a-fA-F]{64}$' },
            partSize: { type: 'integer', minimum: 0 },
            idempotencyKey: { type: 'string', minLength: 1, maxLength: 128 },
          },
        },
        response: { 200: uploadResponseSchema, 201: uploadResponseSchema, ...commonErrors },
      },
    },
    async (request: FastifyRequest, reply) => {
      const body = parse(createUploadBodySchema, request.body);
      const result = await opts.transferService.createUpload(authUserId(request), body);
      return reply.status(result.created ? 201 : 200).send({ upload: result.upload });
    },
  );

  app.get(
    '/uploads/:uploadId',
    {
      preHandler: auth,
      schema: {
        tags: ['uploads'],
        summary: '查询上传会话以断点续传',
        security: bearerSecurity,
        params: uploadParams,
        querystring: deviceQuery,
        response: { 200: uploadResponseSchema, ...commonErrors },
      },
    },
    async (request: FastifyRequest) => {
      const uploadId = uploadIdOf(request.params);
      const query = parse(deviceQuerySchema, request.query);
      return { upload: await opts.transferService.getUpload(authUserId(request), uploadId, query.deviceId) };
    },
  );

  app.put(
    '/uploads/:uploadId/parts/:partNumber',
    {
      preHandler: auth,
      bodyLimit: opts.uploadPartSize,
      schema: {
        tags: ['uploads'],
        summary: '上传一个固定大小的原始字节分片（幂等）',
        security: bearerSecurity,
        consumes: ['application/octet-stream'],
        params: {
          type: 'object',
          required: ['uploadId', 'partNumber'],
          properties: { uploadId: { type: 'string' }, partNumber: { type: 'integer', minimum: 1 } },
        },
        querystring: deviceQuery,
        headers: {
          type: 'object',
          required: ['idempotency-key'],
          properties: { 'idempotency-key': { type: 'string', minLength: 1, maxLength: 128 } },
        },
        body: {
          anyOf: [{ type: 'object' }, { type: 'string', format: 'binary' }],
          description: 'application/octet-stream 原始分片字节',
        },
        response: {
          200: {
            type: 'object',
            required: ['part'],
            additionalProperties: false,
            properties: {
              part: {
                type: 'object',
                required: ['partNumber', 'etag', 'size', 'sha256'],
                additionalProperties: false,
                properties: {
                  partNumber: { type: 'integer' },
                  etag: { type: 'string' },
                  size: { type: 'string' },
                  sha256: { type: 'string' },
                },
              },
            },
          },
          ...commonErrors,
          413: errorResponses[413],
        },
      },
    },
    async (request: FastifyRequest) => {
      const paramsResult = partParamsSchema.safeParse(request.params);
      if (!paramsResult.success) {
        if (!uploadParamsSchema.safeParse(request.params).success) {
          throw uploadNotFound();
        }
        throw validationError('partNumber must be an integer starting at 1');
      }
      const query = parse(deviceQuerySchema, request.query);
      const idempotencyKey = request.headers['idempotency-key'];
      if (typeof idempotencyKey !== 'string' || idempotencyKey.length < 1 || idempotencyKey.length > 128) {
        throw validationError('Idempotency-Key header is required');
      }
      if (!Buffer.isBuffer(request.body)) {
        throw validationError('Expected an application/octet-stream request body');
      }
      const result = await opts.transferService.uploadPart(
        authUserId(request),
        paramsResult.data.uploadId,
        query.deviceId,
        paramsResult.data.partNumber,
        request.body,
      );
      return {
        part: {
          partNumber: result.part.partNumber,
          etag: result.part.etag,
          size: result.part.size.toString(),
          sha256: result.part.sha256,
        },
      };
    },
  );

  app.post(
    '/uploads/:uploadId/complete',
    {
      preHandler: auth,
      schema: {
        tags: ['uploads'],
        summary: '合并分片并流式校验最终对象',
        security: bearerSecurity,
        params: uploadParams,
        body: {
          type: 'object',
          required: ['deviceId', 'idempotencyKey'],
          additionalProperties: false,
          properties: {
            deviceId: { type: 'string' },
            idempotencyKey: { type: 'string', minLength: 1, maxLength: 128 },
          },
        },
        response: { 200: uploadResponseSchema, ...commonErrors, 422: errorResponses[422] },
      },
    },
    async (request: FastifyRequest) => {
      const uploadId = uploadIdOf(request.params);
      const body = parse(completeBodySchema, request.body);
      return {
        upload: await opts.transferService.completeUpload(
          authUserId(request),
          uploadId,
          body.deviceId,
        ),
      };
    },
  );
};

export default uploadsRoutes;
