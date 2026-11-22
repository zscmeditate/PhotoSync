import { randomUUID } from 'node:crypto';
import fastifyWebsocket from '@fastify/websocket';
import swagger from '@fastify/swagger';
import swaggerUi from '@fastify/swagger-ui';
import Fastify, { type FastifyError, type FastifyInstance } from 'fastify';
import { type Config } from './config';
import { AppError, ErrorCodes } from './lib/errors';
import { JwtService } from './lib/jwt';
import type { ObjectStore } from './lib/object-store';
import { RealtimeHub } from './lib/realtime';
import type {
  DeviceRepository,
  RefreshTokenRepository,
  SpaceRepository,
  SyncRepository,
  TransferRepository,
  UserRepository,
} from './repositories/ports';
import authRoutes from './routes/auth.routes';
import conflictsRoutes from './routes/conflicts.routes';
import devicesRoutes from './routes/devices.routes';
import healthRoutes from './routes/health.routes';
import mappingsRoutes from './routes/mappings.routes';
import spacesRoutes from './routes/spaces.routes';
import syncRoutes from './routes/sync.routes';
import trashRoutes from './routes/trash.routes';
import uploadsRoutes from './routes/uploads.routes';
import wsRoutes from './routes/ws.routes';
import { AuthService } from './services/auth.service';
import { DeviceService } from './services/device.service';
import { SpaceService } from './services/space.service';
import { SyncService } from './services/sync.service';
import { TransferService } from './services/transfer.service';

export interface AppDependencies {
  config: Config;
  /** 测试可注入假 repository；生产传入 Prisma 实现 */
  users: UserRepository;
  devices: DeviceRepository;
  refreshTokens: RefreshTokenRepository;
  spaces: SpaceRepository;
  transfers: TransferRepository;
  sync: SyncRepository;
  objectStore: ObjectStore;
  logger?: boolean;
}

/** 装配 Fastify：swagger、错误处理、请求 ID、路由（便于测试注入 mock 依赖） */
export async function buildApp(deps: AppDependencies): Promise<FastifyInstance> {
  const app = Fastify({
    genReqId: () => randomUUID(),
    logger: deps.logger ?? false,
  });

  const jwt = new JwtService(deps.config.JWT_SECRET, deps.config.ACCESS_TOKEN_TTL);
  const authService = new AuthService({
    users: deps.users,
    refreshTokens: deps.refreshTokens,
    jwt,
    refreshTtlMs: deps.config.REFRESH_TOKEN_TTL_DAYS * 24 * 60 * 60 * 1000,
  });
  const deviceService = new DeviceService({
    devices: deps.devices,
    refreshTokens: deps.refreshTokens,
  });
  const spaceService = new SpaceService({ spaces: deps.spaces, devices: deps.devices });
  const transferService = new TransferService({
    transfers: deps.transfers,
    spaces: deps.spaces,
    devices: deps.devices,
    objectStore: deps.objectStore,
    uploadPartSize: deps.config.UPLOAD_PART_SIZE,
  });
  const syncService = new SyncService({
    sync: deps.sync,
    transfers: deps.transfers,
    spaces: deps.spaces,
    devices: deps.devices,
    objectStore: deps.objectStore,
  });
  // 实时提示：commit 成功后向订阅该空间的连接推送 revision 提示。
  const hub = new RealtimeHub();

  await app.register(fastifyWebsocket);

  app.addContentTypeParser(
    'application/octet-stream',
    { parseAs: 'buffer', bodyLimit: deps.config.UPLOAD_PART_SIZE },
    (_request, body, done) => done(null, body),
  );

  await app.register(swagger, {
    openapi: {
      openapi: '3.1.0',
      info: {
        title: 'PhotoSync API',
        description: '多设备文件夹同步服务端 API（Phase 2：空间与映射；Phase 3：新增文件同步）',
        version: '0.1.0',
      },
      components: {
        securitySchemes: {
          bearerAuth: {
            type: 'http',
            scheme: 'bearer',
            bearerFormat: 'JWT',
          },
        },
      },
    },
  });
  await app.register(swaggerUi, {
    routePrefix: '/docs',
    staticCSP: true,
  });

  // 统一错误封装：{"error":{"code":"STRING","message":"string"}}
  app.setErrorHandler((error: FastifyError | AppError, _request, reply) => {
    if (error instanceof AppError) {
      return reply.status(error.statusCode).send(error.toJSON());
    }
    if (error.validation) {
      return reply.status(400).send({
        error: { code: ErrorCodes.VALIDATION_ERROR, message: error.message },
      });
    }
    const statusCode =
      typeof error.statusCode === 'number' && error.statusCode >= 400 && error.statusCode < 500
        ? error.statusCode
        : 500;
    if (statusCode === 500) {
      app.log?.error(error);
      return reply.status(500).send({
        error: { code: ErrorCodes.INTERNAL_ERROR, message: 'Internal server error' },
      });
    }
    const code =
      statusCode === 404
        ? ErrorCodes.NOT_FOUND
        : statusCode === 413
          ? ErrorCodes.PAYLOAD_TOO_LARGE
          : statusCode === 400
            ? ErrorCodes.VALIDATION_ERROR
            : ErrorCodes.INTERNAL_ERROR;
    return reply.status(statusCode).send({
      error: { code, message: error.message },
    });
  });

  app.setNotFoundHandler((_request, reply) => {
    return reply.status(404).send({
      error: { code: ErrorCodes.NOT_FOUND, message: 'Route not found' },
    });
  });

  await app.register(healthRoutes, { prefix: '/api/v1' });
  await app.register(authRoutes, {
    prefix: '/api/v1',
    authService,
    jwt,
  });
  await app.register(devicesRoutes, {
    prefix: '/api/v1',
    deviceService,
    jwt,
  });
  await app.register(spacesRoutes, {
    prefix: '/api/v1',
    spaceService,
    jwt,
  });
  await app.register(mappingsRoutes, {
    prefix: '/api/v1',
    spaceService,
    jwt,
  });
  await app.register(uploadsRoutes, {
    prefix: '/api/v1',
    transferService,
    jwt,
    uploadPartSize: deps.config.UPLOAD_PART_SIZE,
  });
  await app.register(syncRoutes, {
    prefix: '/api/v1',
    syncService,
    jwt,
    hub,
  });
  await app.register(conflictsRoutes, {
    prefix: '/api/v1',
    syncService,
    jwt,
    hub,
  });
  await app.register(trashRoutes, {
    prefix: '/api/v1',
    syncService,
    jwt,
    hub,
  });
  await app.register(wsRoutes, {
    prefix: '/api/v1',
    hub,
    jwt,
    spaces: deps.spaces,
    devices: deps.devices,
  });

  return app;
}
