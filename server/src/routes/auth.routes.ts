import type { FastifyPluginAsync, FastifyRequest } from 'fastify';
import { z } from 'zod';
import { authUserId, requireAuth } from '../lib/auth';
import type { JwtService } from '../lib/jwt';
import { parse } from '../lib/validation';
import type { AuthService } from '../services/auth.service';
import {
  authResultSchema,
  bearerSecurity,
  errorResponses,
  tokenPairSchema,
} from './schemas';

export interface AuthRoutesOptions {
  authService: AuthService;
  jwt: JwtService;
}

const registerBodySchema = z.object({
  email: z.string().email('invalid email'),
  password: z.string().min(8, 'password must be at least 8 characters'),
  displayName: z.string().min(1).max(64),
});

const loginBodySchema = z.object({
  email: z.string().email('invalid email'),
  password: z.string().min(1),
});

const refreshBodySchema = z.object({
  refreshToken: z.string().min(1),
});

const logoutBodySchema = z.object({
  refreshToken: z.string().min(1),
});

/** 登出时可选携带本机设备 ID（X-Device-Id），用于把设备从列表移除 */
const deviceIdHeaderSchema = z.string().uuid();

const authRoutes: FastifyPluginAsync<AuthRoutesOptions> = async (app, opts) => {
  const { authService, jwt } = opts;

  app.post(
    '/auth/register',
    {
      schema: {
        tags: ['auth'],
        summary: '注册',
        body: {
          type: 'object',
          required: ['email', 'password', 'displayName'],
          additionalProperties: false,
          properties: {
            email: { type: 'string', description: '邮箱' },
            password: { type: 'string', minLength: 8, description: '密码，至少 8 位' },
            displayName: { type: 'string', minLength: 1, maxLength: 64 },
          },
        },
        response: {
          201: authResultSchema,
          400: errorResponses[400],
          409: errorResponses[409],
          500: errorResponses[500],
        },
      },
    },
    async (request: FastifyRequest, reply) => {
      const body = parse(registerBodySchema, request.body);
      const result = await authService.register(body);
      return reply.status(201).send(result);
    },
  );

  app.post(
    '/auth/login',
    {
      schema: {
        tags: ['auth'],
        summary: '登录',
        body: {
          type: 'object',
          required: ['email', 'password'],
          additionalProperties: false,
          properties: {
            email: { type: 'string' },
            password: { type: 'string' },
          },
        },
        response: {
          200: authResultSchema,
          400: errorResponses[400],
          401: errorResponses[401],
          500: errorResponses[500],
        },
      },
    },
    async (request: FastifyRequest) => {
      const body = parse(loginBodySchema, request.body);
      return authService.login(body);
    },
  );

  app.post(
    '/auth/refresh',
    {
      schema: {
        tags: ['auth'],
        summary: '刷新访问令牌',
        description: '轮换式刷新：旧 refresh token 吊销并签发新的一对令牌。',
        body: {
          type: 'object',
          required: ['refreshToken'],
          additionalProperties: false,
          properties: {
            refreshToken: { type: 'string' },
          },
        },
        response: {
          200: tokenPairSchema,
          400: errorResponses[400],
          401: errorResponses[401],
          500: errorResponses[500],
        },
      },
    },
    async (request: FastifyRequest) => {
      const body = parse(refreshBodySchema, request.body);
      return authService.refresh(body.refreshToken);
    },
  );

  app.post(
    '/auth/logout',
    {
      preHandler: requireAuth(jwt),
      schema: {
        tags: ['auth'],
        summary: '登出',
        description: '吊销请求体中的 refresh token（幂等）。',
        security: bearerSecurity,
        body: {
          type: 'object',
          required: ['refreshToken'],
          additionalProperties: false,
          properties: {
            refreshToken: { type: 'string' },
          },
        },
        response: {
          400: errorResponses[400],
          401: errorResponses[401],
          500: errorResponses[500],
        },
      },
    },
    async (request: FastifyRequest, reply) => {
      const body = parse(logoutBodySchema, request.body);
      const deviceId = deviceIdHeaderSchema.safeParse(request.headers['x-device-id']);
      await authService.logout(
        authUserId(request),
        body.refreshToken,
        deviceId.success ? deviceId.data : undefined,
      );
      return reply.status(204).send();
    },
  );
};

export default authRoutes;
