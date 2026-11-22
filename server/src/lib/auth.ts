import type { FastifyRequest, preHandlerHookHandler } from 'fastify';
import { unauthorized } from './errors';
import type { JwtService } from './jwt';

declare module 'fastify' {
  interface FastifyRequest {
    auth?: { userId: string };
  }
}

/** Bearer 认证 preHandler：校验 Authorization: Bearer <accessToken> 并注入 req.auth */
export function requireAuth(jwt: JwtService): preHandlerHookHandler {
  return async (request: FastifyRequest): Promise<void> => {
    const header = request.headers.authorization;
    if (typeof header !== 'string' || !header.startsWith('Bearer ')) {
      throw unauthorized('Missing bearer token');
    }
    const token = header.slice('Bearer '.length).trim();
    if (token.length === 0) {
      throw unauthorized('Missing bearer token');
    }
    request.auth = jwt.verifyAccessToken(token);
  };
}

/** 获取已认证用户 ID（供 handler 使用） */
export function authUserId(request: FastifyRequest): string {
  const userId = request.auth?.userId;
  if (!userId) {
    throw unauthorized();
  }
  return userId;
}
