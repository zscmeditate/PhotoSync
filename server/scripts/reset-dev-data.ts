/**
 * 开发环境一键重置：清空数据库（含全部表与迁移记录）并清空对象存储。
 * 用途：从零开始整链路测试，避免历史数据干扰。
 *
 * 用法（在 server 目录执行）：
 *   npm run db:reset            # 交互确认后重置数据库 + 对象存储
 *   npm run db:reset -- --yes   # 跳过确认
 *   npm run db:reset -- --skip-storage   # 只重置数据库
 *   npm run db:reset -- --skip-db        # 只清空对象存储
 *
 * 数据来源：先读 server/.env（存在时），再用进程环境变量覆盖；
 * 未配置的环境变量使用与 src/config.ts 一致的本地开发默认值。
 *
 * 注意：重置后所有账号、设备、空间、文件索引都会消失，
 * 每台设备需要重新登录，并重新绑定一次同步目录。
 */
import { spawnSync } from 'node:child_process';
import { existsSync, readFileSync } from 'node:fs';
import { createInterface } from 'node:readline/promises';
import { stdin, stdout } from 'node:process';
import path from 'node:path';
import {
  AbortMultipartUploadCommand,
  DeleteObjectsCommand,
  ListMultipartUploadsCommand,
  ListObjectsV2Command,
  S3Client,
} from '@aws-sdk/client-s3';

const serverRoot = path.resolve(__dirname, '..');

/** 读取 .env（不覆盖已有的进程环境变量），仅支持 KEY=VALUE 与 # 注释。 */
function loadDotEnv(): void {
  const envPath = path.join(serverRoot, '.env');
  if (!existsSync(envPath)) return;
  for (const rawLine of readFileSync(envPath, 'utf8').split(/\r?\n/)) {
    const line = rawLine.trim();
    if (!line || line.startsWith('#')) continue;
    const eq = line.indexOf('=');
    if (eq <= 0) continue;
    const key = line.slice(0, eq).trim();
    let value = line.slice(eq + 1).trim();
    if (
      (value.startsWith('"') && value.endsWith('"')) ||
      (value.startsWith("'") && value.endsWith("'"))
    ) {
      value = value.slice(1, -1);
    }
    if (process.env[key] === undefined) {
      process.env[key] = value;
    }
  }
}

type Options = {
  yes: boolean;
  skipDb: boolean;
  skipStorage: boolean;
};

function parseArgs(argv: string[]): Options {
  const has = (name: string): boolean => argv.includes(name);
  return {
    yes: has('--yes') || has('-y'),
    skipDb: has('--skip-db'),
    skipStorage: has('--skip-storage'),
  };
}

async function confirm(message: string): Promise<boolean> {
  const rl = createInterface({ input: stdin, output: stdout });
  try {
    const answer = (await rl.question(`${message} (输入 yes 继续): `)).trim().toLowerCase();
    return answer === 'yes' || answer === 'y';
  } finally {
    rl.close();
  }
}

/** 调用 Prisma CLI：优先直接用本地 node_modules，避免 Windows 下 npx.cmd 的路径问题。 */
function runPrismaMigrateReset(databaseUrl: string): void {
  const cliPath = path.join(serverRoot, 'node_modules', 'prisma', 'build', 'index.js');
  const args = ['migrate', 'reset', '--force', '--skip-generate', '--skip-seed'];
  const env = { ...process.env, DATABASE_URL: databaseUrl };
  const result = existsSync(cliPath)
    ? spawnSync(process.execPath, [cliPath, ...args], { cwd: serverRoot, env, stdio: 'inherit' })
    : spawnSync('npx', args, { cwd: serverRoot, env, stdio: 'inherit', shell: true });
  if (result.status !== 0) {
    throw new Error(`prisma migrate reset 失败（退出码 ${result.status ?? 'null'}）`);
  }
}

type StorageConfig = {
  endpoint: string;
  region: string;
  bucket: string;
  forcePathStyle: boolean;
  accessKeyId: string;
  secretAccessKey: string;
};

function readStorageConfig(): StorageConfig {
  return {
    endpoint: process.env.S3_ENDPOINT ?? 'http://localhost:9000',
    region: process.env.S3_REGION ?? 'us-east-1',
    bucket: process.env.S3_BUCKET ?? 'photosync',
    forcePathStyle: (process.env.S3_FORCE_PATH_STYLE ?? 'true') !== 'false',
    accessKeyId: process.env.S3_ACCESS_KEY ?? 'minioadmin',
    secretAccessKey: process.env.S3_SECRET_KEY ?? 'minioadmin',
  };
}

async function clearBucket(config: StorageConfig): Promise<void> {
  const client = new S3Client({
    endpoint: config.endpoint,
    region: config.region,
    forcePathStyle: config.forcePathStyle,
    credentials: { accessKeyId: config.accessKeyId, secretAccessKey: config.secretAccessKey },
  });

  // 1. 删除所有已完成对象（每批 <= 1000）
  let deleted = 0;
  let continuationToken: string | undefined;
  do {
    const page = await client.send(
      new ListObjectsV2Command({
        Bucket: config.bucket,
        ContinuationToken: continuationToken,
        MaxKeys: 1000,
      }),
    );
    const objects = (page.Contents ?? [])
      .map((item) => item.Key)
      .filter((key): key is string => Boolean(key));
    if (objects.length > 0) {
      await client.send(
        new DeleteObjectsCommand({
          Bucket: config.bucket,
          Delete: { Objects: objects.map((Key) => ({ Key })), Quiet: true },
        }),
      );
      deleted += objects.length;
    }
    continuationToken = page.IsTruncated ? page.NextContinuationToken : undefined;
  } while (continuationToken);
  console.log(`[storage] 已删除 ${deleted} 个对象`);

  // 2. 中止残留的分片上传，避免占用存储
  let aborted = 0;
  let keyMarker: string | undefined;
  let uploadIdMarker: string | undefined;
  do {
    const page = await client.send(
      new ListMultipartUploadsCommand({
        Bucket: config.bucket,
        KeyMarker: keyMarker,
        UploadIdMarker: uploadIdMarker,
      }),
    );
    for (const upload of page.Uploads ?? []) {
      if (!upload.Key || !upload.UploadId) continue;
      await client.send(
        new AbortMultipartUploadCommand({
          Bucket: config.bucket,
          Key: upload.Key,
          UploadId: upload.UploadId,
        }),
      );
      aborted += 1;
    }
    keyMarker = page.IsTruncated ? page.NextKeyMarker : undefined;
    uploadIdMarker = page.IsTruncated ? page.NextUploadIdMarker : undefined;
  } while (keyMarker !== undefined);
  console.log(`[storage] 已中止 ${aborted} 个未完成的分片上传`);
}

async function main(): Promise<void> {
  const options = parseArgs(process.argv.slice(2));
  loadDotEnv();

  const databaseUrl = process.env.DATABASE_URL;
  if (!databaseUrl) {
    throw new Error('缺少 DATABASE_URL：请在 server/.env 配置，或通过环境变量传入');
  }
  if (process.env.NODE_ENV === 'production') {
    throw new Error('拒绝在 NODE_ENV=production 下执行重置；这是开发环境专用脚本');
  }

  const targets: string[] = [];
  if (!options.skipDb) targets.push('数据库（删除全部表与迁移记录，重新应用基线迁移）');
  if (!options.skipStorage) targets.push('对象存储（清空 bucket 内所有文件与未完成分片上传）');
  if (targets.length === 0) {
    console.log('两个目标都被跳过，没有需要执行的清理');
    return;
  }

  console.log('即将清空：');
  for (const target of targets) console.log(`  - ${target}`);
  console.log(`数据库：${redactDatabaseUrl(databaseUrl)}`);

  if (!options.yes) {
    const ok = await confirm('该操作不可恢复，确认继续？');
    if (!ok) {
      console.log('已取消');
      return;
    }
  }

  if (!options.skipDb) {
    console.log('[db] 重置数据库并应用基线迁移…');
    runPrismaMigrateReset(databaseUrl);
    console.log('[db] 数据库已重置');
  }

  if (!options.skipStorage) {
    const storageConfig = readStorageConfig();
    console.log(`[storage] 清理 ${storageConfig.endpoint} / ${storageConfig.bucket}`);
    await clearBucket(storageConfig);
  }

  console.log('完成。下一步：重启服务端，并在每台设备上重新登录、重新绑定同步目录。');
}

/** 隐藏 DATABASE_URL 中的密码，避免日志泄露。 */
function redactDatabaseUrl(value: string): string {
  return value.replace(/(\/\/[^:/@]+:)[^@]*(@)/, '$1***$2');
}

main().catch((error) => {
  console.error(error instanceof Error ? error.message : error);
  process.exit(1);
});
