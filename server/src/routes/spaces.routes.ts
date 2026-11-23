import type { FastifyPluginAsync, FastifyRequest } from 'fastify';
import { z } from 'zod';
import { authUserId, requireAuth } from '../lib/auth';
import {
  joinRequestNotFound,
  mappingNotFound,
  notMember,
  spaceNotFound,
} from '../lib/errors';
import type { JwtService } from '../lib/jwt';
import { parse } from '../lib/validation';
import type { SpaceService } from '../services/space.service';
import {
  bearerSecurity,
  errorResponses,
  joinRequestSchema,
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
const deviceQuerySchema = z.object({ deviceId: uuidSchema.optional() });
const deviceBodySchema = z.object({ deviceId: uuidSchema });

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

const joinRequestQuerySchema = z.object({ deviceId: uuidSchema });
const joinRequestParamsSchema = z.object({ requestId: uuidSchema });

const createMappingBodySchema = z.object({
  deviceId: uuidSchema,
  localPathLabel: z.string().trim().min(1).max(256),
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

const joinRequestParams = {
  type: 'object',
  required: ['requestId'],
  properties: { requestId: { type: 'string', description: '加入申请 UUID' } },
} as const;

const joinRequestObjectSchema = {
  type: 'object',
  required: ['request'],
  additionalProperties: true,
  properties: { request: joinRequestSchema },
} as const;

const joinRequestSubmitSchema = {
  type: 'object',
  required: ['request', 'joined'],
  additionalProperties: true,
  properties: { request: joinRequestSchema, joined: { type: 'boolean' } },
} as const;

const joinRequestListSchema = {
  type: 'object',
  required: ['requests'],
  additionalProperties: true,
  properties: { requests: { type: 'array', items: joinRequestSchema } },
} as const;

function parseJoinRequestId(params: unknown): string {
  const result = joinRequestParamsSchema.safeParse(params);
  if (!result.success) {
    throw joinRequestNotFound();
  }
  return result.data.requestId;
}

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
        querystring: {
          type: 'object',
          additionalProperties: false,
          properties: { deviceId: { type: 'string' } },
        },
        response: {
          200: spaceListSchema,
          401: errorResponses[401],
          500: errorResponses[500],
        },
      },
    },
    async (request: FastifyRequest) => {
      const query = parse(deviceQuerySchema, request.query);
      return {
        spaces: await spaceService.listSpaces(authUserId(request), query.deviceId),
      };
    },
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
        querystring: {
          type: 'object',
          additionalProperties: false,
          properties: { deviceId: { type: 'string' } },
        },
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
      const query = parse(deviceQuerySchema, request.query);
      return spaceService.getSpace(authUserId(request), spaceId, query.deviceId);
    },
  );

  app.patch(
    '/spaces/:spaceId',
    {
      preHandler: auth,
      schema: {
        tags: ['spaces'],
        summary: '修改空间信息（主设备操作）',
        security: bearerSecurity,
        params: spaceParams,
        body: {
          type: 'object',
          required: ['deviceId'],
          additionalProperties: false,
          properties: {
            deviceId: { type: 'string' },
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
      const body = z.intersection(deviceBodySchema, updateSpaceBodySchema).parse(request.body);
      const space = await spaceService.updateSpace(
        authUserId(request),
        body.deviceId,
        spaceId,
        body,
      );
      return { space };
    },
  );

  app.delete(
    '/spaces/:spaceId',
    {
      preHandler: auth,
      schema: {
        tags: ['spaces'],
        summary: '删除空间（主设备操作）',
        security: bearerSecurity,
        params: spaceParams,
        querystring: {
          type: 'object',
          required: ['deviceId'],
          additionalProperties: false,
          properties: { deviceId: { type: 'string' } },
        },
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
      const query = parse(z.object({ deviceId: uuidSchema }), request.query);
      await spaceService.deleteSpace(authUserId(request), query.deviceId, spaceId);
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
        summary: '将设备移出空间（主设备可踢人，从设备只能退出自己）',
        security: bearerSecurity,
        params: memberParams,
        querystring: {
          type: 'object',
          required: ['operatorDeviceId'],
          additionalProperties: false,
          properties: { operatorDeviceId: { type: 'string' } },
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
      const { spaceId, deviceId } = parseMemberParams(request.params);
      const query = parse(z.object({ operatorDeviceId: uuidSchema }), request.query);
      await spaceService.removeSpaceMember(
        authUserId(request),
        query.operatorDeviceId,
        spaceId,
        deviceId,
      );
      return reply.status(204).send();
    },
  );

  app.post(
    '/spaces/:spaceId/join-requests',
    {
      preHandler: auth,
      schema: {
        tags: ['spaces'],
        summary: '申请加入空间（主设备无需审批）',
        security: bearerSecurity,
        params: spaceParams,
        body: {
          type: 'object',
          required: ['deviceId'],
          additionalProperties: false,
          properties: { deviceId: { type: 'string' } },
        },
        response: {
          201: joinRequestSubmitSchema,
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
      const body = parse(memberBodySchema, request.body);
      const result = await spaceService.requestToJoinSpace(
        authUserId(request),
        spaceId,
        body.deviceId,
      );
      return reply.status(201).send(result);
    },
  );

  app.get(
    '/spaces/:spaceId/join-requests',
    {
      preHandler: auth,
      schema: {
        tags: ['spaces'],
        summary: '主设备查看待审批的加入申请',
        security: bearerSecurity,
        params: spaceParams,
        querystring: {
          type: 'object',
          required: ['deviceId'],
          additionalProperties: false,
          properties: { deviceId: { type: 'string' } },
        },
        response: {
          200: joinRequestListSchema,
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
      const query = parse(joinRequestQuerySchema, request.query);
      return {
        requests: await spaceService.listJoinRequests(
          authUserId(request),
          spaceId,
          query.deviceId,
        ),
      };
    },
  );

  app.post(
    '/join-requests/:requestId/approve',
    {
      preHandler: auth,
      schema: {
        tags: ['spaces'],
        summary: '主设备同意加入申请',
        security: bearerSecurity,
        params: joinRequestParams,
        body: {
          type: 'object',
          required: ['deviceId'],
          additionalProperties: false,
          properties: { deviceId: { type: 'string' } },
        },
        response: {
          200: joinRequestObjectSchema,
          400: errorResponses[400],
          401: errorResponses[401],
          403: errorResponses[403],
          404: errorResponses[404],
          409: errorResponses[409],
          500: errorResponses[500],
        },
      },
    },
    async (request: FastifyRequest) => {
      const requestId = parseJoinRequestId(request.params);
      const body = parse(memberBodySchema, request.body);
      const joinRequest = await spaceService.resolveJoinRequest(
        authUserId(request),
        requestId,
        body.deviceId,
        true,
      );
      return { request: joinRequest };
    },
  );

  app.post(
    '/join-requests/:requestId/reject',
    {
      preHandler: auth,
      schema: {
        tags: ['spaces'],
        summary: '主设备拒绝加入申请',
        security: bearerSecurity,
        params: joinRequestParams,
        body: {
          type: 'object',
          required: ['deviceId'],
          additionalProperties: false,
          properties: { deviceId: { type: 'string' } },
        },
        response: {
          200: joinRequestObjectSchema,
          400: errorResponses[400],
          401: errorResponses[401],
          403: errorResponses[403],
          404: errorResponses[404],
          409: errorResponses[409],
          500: errorResponses[500],
        },
      },
    },
    async (request: FastifyRequest) => {
      const requestId = parseJoinRequestId(request.params);
      const body = parse(memberBodySchema, request.body);
      const joinRequest = await spaceService.resolveJoinRequest(
        authUserId(request),
        requestId,
        body.deviceId,
        false,
      );
      return { request: joinRequest };
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
          required: ['deviceId', 'localPathLabel'],
          additionalProperties: false,
          properties: {
            deviceId: { type: 'string' },
            localPathLabel: { type: 'string', minLength: 1, maxLength: 256 },
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
