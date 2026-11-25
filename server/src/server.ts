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

  const app = await buildApp({
    config,
    users: new PrismaUserRepository(prisma),
    devices: new PrismaDeviceRepository(prisma),
    refreshTokens: new PrismaRefreshTokenRepository(prisma),
    spaces: new PrismaSpaceRepository(prisma),
    transfers: new PrismaTransferRepository(prisma),
    sync: new PrismaSyncRepository(prisma),
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
