import type { FastifyPluginAsync, FastifyRequest } from 'fastify';
import { z } from 'zod';
import { authUserId, requireAuth } from '../lib/auth';
import { mappingNotFound } from '../lib/errors';
import type { JwtService } from '../lib/jwt';
import { parse } from '../lib/validation';
import type { SpaceService } from '../services/space.service';
import { bearerSecurity, errorResponses, mappingSchema } from './schemas';

export interface MappingsRoutesOptions {
  spaceService: SpaceService;
  jwt: JwtService;
}

const mappingParamsSchema = z.object({ mappingId: z.string().uuid() });
const deviceMappingsParamsSchema = z.object({ deviceId: z.string().uuid() });
const updateMappingBodySchema = z.object({
  localPathLabel: z.string().trim().min(1).max(256).optional(),
  treeUri: z.string().trim().max(2048).optional(),
  onlyMedia: z.boolean().optional(),
});

const mappingParams = {
  type: 'object',
  required: ['mappingId'],
  properties: { mappingId: { type: 'string', description: '映射 UUID' } },
} as const;

const mappingObjectSchema = {
  type: 'object',
  required: ['mapping'],
  additionalProperties: true,
  properties: { mapping: mappingSchema },
} as const;

function parseMappingId(params: unknown): string {
  const result = mappingParamsSchema.safeParse(params);
  if (!result.success) {
    throw mappingNotFound();
  }
  return result.data.mappingId;
}

const mappingsRoutes: FastifyPluginAsync<MappingsRoutesOptions> = async (app, opts) => {
  const { spaceService, jwt } = opts;
  const auth = requireAuth(jwt);

  app.patch(
    '/mappings/:mappingId',
    {
      preHandler: auth,
      schema: {
        tags: ['mappings'],
        summary: '修改当前用户设备的目录映射',
        security: bearerSecurity,
        params: mappingParams,
        body: {
          type: 'object',
          additionalProperties: false,
          properties: {
            localPathLabel: { type: 'string', minLength: 1, maxLength: 256 },
            treeUri: { type: 'string', maxLength: 2048 },
            onlyMedia: { type: 'boolean' },
          },
        },
        response: {
          200: mappingObjectSchema,
          400: errorResponses[400],
          401: errorResponses[401],
          404: errorResponses[404],
          409: errorResponses[409],
          500: errorResponses[500],
        },
      },
    },
    async (request: FastifyRequest) => {
      const mappingId = parseMappingId(request.params);
      const body = parse(updateMappingBodySchema, request.body);
      const mapping = await spaceService.updateMapping(authUserId(request), mappingId, body);
      return { mapping };
    },
  );

  app.delete(
    '/mappings/:mappingId',
    {
      preHandler: auth,
      schema: {
        tags: ['mappings'],
        summary: '删除当前用户设备的目录映射',
        security: bearerSecurity,
        params: mappingParams,
        response: {
          401: errorResponses[401],
          404: errorResponses[404],
          500: errorResponses[500],
        },
      },
    },
    async (request: FastifyRequest, reply) => {
      const mappingId = parseMappingId(request.params);
      await spaceService.deleteMapping(authUserId(request), mappingId);
      return reply.status(204).send();
    },
  );

  app.get(
    '/devices/:deviceId/mappings',
    {
      preHandler: auth,
      schema: {
        tags: ['mappings'],
        summary: '列出本机在全部空间上的目录映射（用于重装后恢复绑定）',
        security: bearerSecurity,
        params: {
          type: 'object',
          required: ['deviceId'],
          properties: { deviceId: { type: 'string', description: '设备 UUID' } },
        },
        response: {
          200: {
            type: 'object',
            required: ['mappings'],
            additionalProperties: true,
            properties: { mappings: { type: 'array', items: mappingSchema } },
          },
          400: errorResponses[400],
          401: errorResponses[401],
          404: errorResponses[404],
          500: errorResponses[500],
        },
      },
    },
    async (request: FastifyRequest) => {
      const result = deviceMappingsParamsSchema.safeParse(request.params);
      if (!result.success) {
        throw mappingNotFound();
      }
      const mappings = await spaceService.listDeviceMappings(
        authUserId(request),
        result.data.deviceId,
      );
      return { mappings };
    },
  );
};

export default mappingsRoutes;
