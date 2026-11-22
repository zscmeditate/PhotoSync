import { z } from 'zod';

const configSchema = z.object({
  NODE_ENV: z.enum(['development', 'test', 'production']).default('development'),
  HOST: z.string().min(1).default('0.0.0.0'),
  PORT: z.coerce.number().int().min(1).max(65535).default(3000),
  DATABASE_URL: z.string().min(1, 'DATABASE_URL is required'),
  JWT_SECRET: z.string().min(16, 'JWT_SECRET must be at least 16 characters'),
  /** 访问令牌 TTL，jsonwebtoken expiresIn 语法（如 15m） */
  ACCESS_TOKEN_TTL: z.string().min(1).default('15m'),
  /** 刷新令牌有效期（天） */
  REFRESH_TOKEN_TTL_DAYS: z.coerce.number().int().positive().default(30),
  /** S3 / MinIO 对象存储 */
  S3_ENDPOINT: z.string().min(1).default('http://localhost:9000'),
  S3_ACCESS_KEY: z.string().min(1).default('minioadmin'),
  S3_SECRET_KEY: z.string().min(1).default('minioadmin'),
  S3_BUCKET: z.string().min(1).default('photosync'),
  S3_REGION: z.string().min(1).default('us-east-1'),
  S3_FORCE_PATH_STYLE: z
    .preprocess(
      (value) => (value === 'true' ? true : value === 'false' ? false : value),
      z.boolean(),
    )
    .default(true),
  /** 固定分片大小，默认 8 MiB；路由同时以此限制原始请求体 */
  UPLOAD_PART_SIZE: z.coerce.number().int().positive().max(64 * 1024 * 1024).default(8 * 1024 * 1024),
});

export type Config = z.infer<typeof configSchema>;

/** 从环境变量加载并校验配置，失败抛出带全部问题的错误 */
export function loadConfig(env: NodeJS.ProcessEnv = process.env): Config {
  const result = configSchema.safeParse(env);
  if (!result.success) {
    const details = result.error.issues
      .map((issue) => `${issue.path.join('.') || 'env'}: ${issue.message}`)
      .join('; ');
    throw new Error(`Invalid configuration: ${details}`);
  }
  return result.data;
}
