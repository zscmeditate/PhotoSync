import type { FastifyPluginAsync } from 'fastify';
import { errorResponses } from './schemas';

export const APP_VERSION = '0.1.0';

const healthRoutes: FastifyPluginAsync = async (app) => {
  app.get(
    '/health',
    {
      schema: {
        tags: ['health'],
        summary: '健康检查',
        description: '无需鉴权，返回服务状态与服务器时间。',
        response: {
          200: {
            type: 'object',
            required: ['status', 'version', 'apiVersion', 'serverTime'],
            additionalProperties: true,
            properties: {
              status: { type: 'string' },
              version: { type: 'string' },
              apiVersion: { type: 'string' },
              serverTime: { type: 'string', description: 'ISO8601' },
            },
          },
          ...errorResponses,
        },
      },
    },
    async () => {
      return {
        status: 'ok',
        version: APP_VERSION,
        apiVersion: 'v1',
        serverTime: new Date().toISOString(),
      };
    },
  );
};

export default healthRoutes;
