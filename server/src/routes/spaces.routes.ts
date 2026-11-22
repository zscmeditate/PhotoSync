import type { FastifyPluginAsync, FastifyRequest } from 'fastify';
import { z } from 'zod';
import { authUserId, requireAuth } from '../lib/auth';
import { mappingNotFound, notMember, spaceNotFound } from '../lib/errors';
import type { JwtService } from '../lib/jwt';
import { parse } from '../lib/validation';
import type { SpaceService } from '../services/space.service';
import {
  bearerSecurity,
  errorResponses,
  mappingSchema,
  memberSchema,
  spaceSchema,
} from './schemas';

export interface SpacesRoutesOptions {
  spaceService: SpaceService;
  jwt: JwtService;
}

const uuidSchema = z.string().uuid();
const spaceParamsSchema = z.object({ spaceId: uuidSchema });
const memberParamsSchema = z.object({ spaceId: uuidSchema, deviceId: uuidSchema });

const createSpaceBodySchema = z.object({
  name: z.string().trim().min(1).max(64),
  description: z.string().max(256).optional(),
  deviceId: uuidSchema,
});

const updateSpaceBodySchema = z
  .object({
    name: z.string().trim().min(1).max(64).optional(),
    description: z.string().max(256).optional(),
  })
  .refine((body) => body.name !== undefined || body.description !== undefined, {
    message: 'at least one field is required',
  });

const memberBodySchema = z.object({ deviceId: uuidSchema });

const createMappingBodySchema = z.object({
  deviceId: uuidSchema,
  localPathLabel: z.string().trim().min(1).max(256),
  remotePrefix: z.string(),
  includeSubdirs: z.boolean().optional().default(true),
  onlyMedia: z.boolean().optional().default(false),
});

const mappingQuerySchema = z.object({ deviceId: uuidSchema.optional() });

const scanManifestBodySchema = z.object({
  deviceId: uuidSchema,
  mappingId: z.string().min(1),
  scanComplete: z.boolean(),
  files: z
    .array(
      z.object({
        relativePath: z.string().min(1),
        size: z.number().nonnegative(),
        sha256: z.string().regex(/^[0-9a-fA-F]{64}$/).optional(),
        modifiedAt: z.string().datetime({ offset: true }).optional(),
      }),
    )
    .max(10000),
});

const spaceParams = {
  type: 'object',
  required: ['spaceId'],
  properties: { spaceId: { type: 'string', description: '空间 UUID' } },
} as const;

const memberParams = {
  type: 'object',
  required: ['spaceId', 'deviceId'],
  properties: {
    spaceId: { type: 'string', description: '空间 UUID' },
    deviceId: { type: 'string', description: '设备 UUID' },
  },
} as const;

const spaceObjectSchema = {
  type: 'object',
  required: ['space'],
  additionalProperties: true,
  properties: { space: spaceSchema },
} as const;

const spaceListSchema = {
  type: 'object',
  required: ['spaces'],
  additionalProperties: true,
  properties: { spaces: { type: 'array', items: spaceSchema } },
} as const;

const spaceDetailSchema = {
  type: 'object',
  required: ['space', 'members'],
  additionalProperties: true,
  properties: {
    space: spaceSchema,
    members: { type: 'array', items: memberSchema },
  },
} as const;

const memberObjectSchema = {
  type: 'object',
  required: ['member'],
  additionalProperties: true,
  properties: { member: memberSchema },
} as const;

const mappingObjectSchema = {
  type: 'object',
  required: ['mapping'],
  additionalProperties: true,
  properties: { mapping: mappingSchema },
} as const;

const mappingListSchema = {
  type: 'object',
  required: ['mappings'],
  additionalProperties: true,
  properties: { mappings: { type: 'array', items: mappingSchema } },
} as const;

const scanResultSchema = {
  type: 'object',
  required: ['summary', 'remoteFiles'],
  additionalProperties: true,
  properties: {
    summary: {
      type: 'object',
      required: [
        'localCount',
        'remoteCount',
        'toUpload',
        'toDownload',
        'conflictCount',
        'diffComplete',
      ],
      properties: {
        localCount: { type: 'integer' },
        remoteCount: { type: 'integer' },
        toUpload: { type: 'integer' },
        toDownload: { type: 'integer' },
        conflictCount: { type: 'integer' },
        diffComplete: { type: 'boolean' },
      },
    },
    remoteFiles: {
      type: 'array',
      maxItems: 10000,
      items: {
        type: 'object',
        required: ['fileId', 'relativePath', 'size', 'sha256', 'version'],
        properties: {
          fileId: { type: 'string' },
          relativePath: { type: 'string' },
          size: { type: 'number' },
          sha256: { type: 'string' },
          version: { type: 'integer' },
        },
      },
    },
  },
} as const;

function parseSpaceId(params: unknown): string {
  const result = spaceParamsSchema.safeParse(params);
  if (!result.success) {
    throw spaceNotFound();
  }
  return result.data.spaceId;
}

function parseMemberParams(params: unknown): { spaceId: string; deviceId: string } {
  const result = memberParamsSchema.safeParse(params);
  if (!result.success) {
    if (!spaceParamsSchema.safeParse(params).success) {
      throw spaceNotFound();
    }
    throw notMember(404);
  }
  return result.data;
}

const spacesRoutes: FastifyPluginAsync<SpacesRoutesOptions> = async (app, opts) => {
  const { spaceService, jwt } = opts;
  const auth = requireAuth(jwt);

  app.post(
    '/spaces',
    {
      preHandler: auth,
      schema: {
        tags: ['spaces'],
        summary: '创建同步空间',
        security: bearerSecurity,
        body: {
          type: 'object',
          required: ['name', 'deviceId'],
          additionalProperties: false,
          properties: {
            name: { type: 'string', minLength: 1, maxLength: 64 },
            description: { type: 'string', maxLength: 256 },
            deviceId: { type: 'string' },
          },
        },
        response: {
          201: spaceObjectSchema,
          400: errorResponses[400],
          401: errorResponses[401],
          500: errorResponses[500],
        },
      },
    },
    async (request: FastifyRequest, reply) => {
      const body = parse(createSpaceBodySchema, request.body);
      const space = await spaceService.createSpace(authUserId(request), body);
      return reply.status(201).send({ space });
    },
  );

  app.get(
    '/spaces',
    {
      preHandler: auth,
      schema: {
        tags: ['spaces'],
        summary: '列出当前用户可访问的同步空间',
        security: bearerSecurity,
        response: {
          200: spaceListSchema,
          401: errorResponses[401],
          500: errorResponses[500],
        },
      },
    },
    async (request: FastifyRequest) => ({
      spaces: await spaceService.listSpaces(authUserId(request)),
    }),
  );

  app.get(
    '/spaces/:spaceId',
    {
      preHandler: auth,
      schema: {
        tags: ['spaces'],
        summary: '空间详情与成员列表',
        security: bearerSecurity,
        params: spaceParams,
        response: {
          200: spaceDetailSchema,
          401: errorResponses[401],
          403: errorResponses[403],
          404: errorResponses[404],
          500: errorResponses[500],
        },
      },
    },
    async (request: FastifyRequest) => {
      const spaceId = parseSpaceId(request.params);
      return spaceService.getSpace(authUserId(request), spaceId);
    },
  );

  app.patch(
    '/spaces/:spaceId',
    {
      preHandler: auth,
      schema: {
        tags: ['spaces'],
        summary: '修改空间信息',
        security: bearerSecurity,
        params: spaceParams,
        body: {
          type: 'object',
          additionalProperties: false,
          properties: {
            name: { type: 'string', minLength: 1, maxLength: 64 },
            description: { type: 'string', maxLength: 256 },
          },
        },
        response: {
          200: spaceObjectSchema,
          400: errorResponses[400],
          401: errorResponses[401],
          403: errorResponses[403],
          404: errorResponses[404],
          500: errorResponses[500],
        },
      },
    },
    async (request: FastifyRequest) => {
      const spaceId = parseSpaceId(request.params);
      const body = parse(updateSpaceBodySchema, request.body);
      const space = await spaceService.updateSpace(authUserId(request), spaceId, body);
      return { space };
    },
  );

  app.delete(
    '/spaces/:spaceId',
    {
      preHandler: auth,
      schema: {
        tags: ['spaces'],
        summary: '删除空间（仅 owner）',
        security: bearerSecurity,
        params: spaceParams,
        response: {
          401: errorResponses[401],
          403: errorResponses[403],
          404: errorResponses[404],
          500: errorResponses[500],
        },
      },
    },
    async (request: FastifyRequest, reply) => {
      const spaceId = parseSpaceId(request.params);
      await spaceService.deleteSpace(authUserId(request), spaceId);
      return reply.status(204).send();
    },
  );

  app.post(
    '/spaces/:spaceId/members',
    {
      preHandler: auth,
      schema: {
        tags: ['spaces'],
        summary: '将当前用户的设备加入空间（幂等）',
        security: bearerSecurity,
        params: spaceParams,
        body: {
          type: 'object',
          required: ['deviceId'],
          additionalProperties: false,
          properties: { deviceId: { type: 'string' } },
        },
        response: {
          200: memberObjectSchema,
          201: memberObjectSchema,
          400: errorResponses[400],
          401: errorResponses[401],
          403: errorResponses[403],
          404: errorResponses[404],
          500: errorResponses[500],
        },
      },
    },
    async (request: FastifyRequest, reply) => {
      const spaceId = parseSpaceId(request.params);
      const body = parse(memberBodySchema, request.body);
      const result = await spaceService.addSpaceMember(
        authUserId(request),
        spaceId,
        body.deviceId,
      );
      return reply.status(result.created ? 201 : 200).send({ member: result.member });
    },
  );

  app.delete(
    '/spaces/:spaceId/members/:deviceId',
    {
      preHandler: auth,
      schema: {
        tags: ['spaces'],
        summary: '将设备移出空间',
        security: bearerSecurity,
        params: memberParams,
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
      const { spaceId, deviceId } = parseMemberParams(request.params);
      await spaceService.removeSpaceMember(authUserId(request), spaceId, deviceId);
      return reply.status(204).send();
    },
  );

  app.post(
    '/spaces/:spaceId/mappings',
    {
      preHandler: auth,
      schema: {
        tags: ['mappings'],
        summary: '创建目录映射',
        security: bearerSecurity,
        params: spaceParams,
        body: {
          type: 'object',
          required: ['deviceId', 'localPathLabel', 'remotePrefix'],
          additionalProperties: false,
          properties: {
            deviceId: { type: 'string' },
            localPathLabel: { type: 'string', minLength: 1, maxLength: 256 },
            remotePrefix: { type: 'string' },
            includeSubdirs: { type: 'boolean', default: true },
            onlyMedia: { type: 'boolean', default: false },
          },
        },
        response: {
          201: mappingObjectSchema,
          400: errorResponses[400],
          401: errorResponses[401],
          403: errorResponses[403],
          404: errorResponses[404],
          409: errorResponses[409],
          500: errorResponses[500],
        },
      },
    },
    async (request: FastifyRequest, reply) => {
      const spaceId = parseSpaceId(request.params);
      const body = parse(createMappingBodySchema, request.body);
      const mapping = await spaceService.createMapping(authUserId(request), spaceId, body);
      return reply.status(201).send({ mapping });
    },
  );

  app.get(
    '/spaces/:spaceId/mappings',
    {
      preHandler: auth,
      schema: {
        tags: ['mappings'],
        summary: '列出空间目录映射',
        security: bearerSecurity,
        params: spaceParams,
        querystring: {
          type: 'object',
          additionalProperties: false,
          properties: { deviceId: { type: 'string' } },
        },
        response: {
          200: mappingListSchema,
          400: errorResponses[400],
          401: errorResponses[401],
          403: errorResponses[403],
          404: errorResponses[404],
          500: errorResponses[500],
        },
      },
    },
    async (request: FastifyRequest) => {
      const spaceId = parseSpaceId(request.params);
      const query = parse(mappingQuerySchema, request.query);
      const mappings = await spaceService.listMappings(
        authUserId(request),
        spaceId,
        query.deviceId,
      );
      return { mappings };
    },
  );

  app.post(
    '/spaces/:spaceId/scan-manifest',
    {
      preHandler: auth,
      schema: {
        tags: ['spaces'],
        summary: '扫描清单差异预览',
        security: bearerSecurity,
        params: spaceParams,
        body: {
          type: 'object',
          required: ['deviceId', 'mappingId', 'scanComplete', 'files'],
          additionalProperties: false,
          properties: {
            deviceId: { type: 'string' },
            mappingId: { type: 'string' },
            scanComplete: { type: 'boolean' },
            files: {
              type: 'array',
              maxItems: 10000,
              items: {
                type: 'object',
                required: ['relativePath', 'size'],
                additionalProperties: false,
                properties: {
                  relativePath: { type: 'string', minLength: 1 },
                  size: { type: 'number', minimum: 0 },
                  sha256: { type: 'string', pattern: '^[0-9a-fA-F]{64}$' },
                  modifiedAt: { type: 'string' },
                },
              },
            },
          },
        },
        response: {
          200: scanResultSchema,
          400: errorResponses[400],
          401: errorResponses[401],
          403: errorResponses[403],
          404: errorResponses[404],
          500: errorResponses[500],
        },
      },
    },
    async (request: FastifyRequest) => {
      const spaceId = parseSpaceId(request.params);
      const body = parse(scanManifestBodySchema, request.body);
      if (!uuidSchema.safeParse(body.mappingId).success) {
        throw mappingNotFound();
      }
      return spaceService.scanManifest(authUserId(request), spaceId, body);
    },
  );
};

export default spacesRoutes;
