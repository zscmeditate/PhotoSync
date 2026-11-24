import { buildApp } from './app';
import { loadConfig } from './config';
import { prisma } from './lib/prisma';
import { S3ObjectStore } from './lib/object-store';
import {
  PrismaDeviceRepository,
  PrismaRefreshTokenRepository,
  PrismaSpaceRepository,
  PrismaSyncRepository,
  PrismaTransferRepository,
  PrismaUserRepository,
} from './repositories/prisma.repositories';

async function main(): Promise<void> {
  const config = loadConfig();
  const objectStore = new S3ObjectStore(config);
  await objectStore.ensureBucket();

  const sync = new PrismaSyncRepository(prisma);
  // 启动维护：清理历史上误上传的系统回收站文件（.trashed-* / .Trash）。
  // 与正常删除一致地软删除并广播 file_deleted 事件，各设备下次拉取时移除本地副本。
  try {
    const cleaned = await sync.removeLegacySystemTrashFiles();
    if (cleaned.removed > 0) {
      console.log(`[maintenance] cleaned ${cleaned.removed} legacy system-trash file(s)`);
    }
  } catch (err) {
    console.error('[maintenance] system-trash cleanup failed:', err);
  }

  const app = await buildApp({
    config,
    users: new PrismaUserRepository(prisma),
    devices: new PrismaDeviceRepository(prisma),
    refreshTokens: new PrismaRefreshTokenRepository(prisma),
    spaces: new PrismaSpaceRepository(prisma),
    transfers: new PrismaTransferRepository(prisma),
    sync,
    objectStore,
    logger: true,
  });

  const shutdown = async (signal: string): Promise<void> => {
    app.log.info(`received ${signal}, shutting down`);
    try {
      await app.close();
      await prisma.$disconnect();
      process.exit(0);
    } catch (err) {
      app.log.error(err);
      process.exit(1);
    }
  };
  process.on('SIGINT', () => void shutdown('SIGINT'));
  process.on('SIGTERM', () => void shutdown('SIGTERM'));

  await app.listen({ port: config.PORT, host: config.HOST });
  app.log.info(`PhotoSync server listening on ${config.HOST}:${config.PORT}`);
}

main().catch((err) => {
  console.error(err);
  process.exit(1);
});
