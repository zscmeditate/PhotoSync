import { PrismaClient } from '@prisma/client';

/** 全局 PrismaClient 单例（生产代码统一走 Prisma 访问数据库） */
export const prisma = new PrismaClient();
