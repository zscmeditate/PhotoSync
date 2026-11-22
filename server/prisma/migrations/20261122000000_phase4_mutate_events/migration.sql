-- Phase 4: 支持文件修改、删除、重命名的增量事件。

-- 1. 扩展事件类型枚举，增加重命名事件。
ALTER TYPE "SyncEventType" ADD VALUE IF NOT EXISTS 'file_renamed';

-- 2. 活动文件路径唯一性改为部分唯一索引：
--    软删除（isDeleted = true）后的路径可被重新创建或被重命名复用，
--    仅未删除文件之间保持 (spaceId, relativePath) 唯一。
ALTER TABLE "sync_files" DROP CONSTRAINT IF EXISTS "sync_files_spaceId_relativePath_key";

CREATE UNIQUE INDEX IF NOT EXISTS "sync_files_space_relativepath_active"
  ON "sync_files" ("spaceId", "relativePath")
  WHERE "isDeleted" = false;
