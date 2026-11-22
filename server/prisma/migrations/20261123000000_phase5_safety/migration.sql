-- Phase 5：安全机制
-- 1) 回收站条目：每个文件至多一条记录（软删除后不可重复删除，恢复后清除）
CREATE UNIQUE INDEX IF NOT EXISTS "trash_items_fileId_key" ON "trash_items" ("fileId");

-- 2) 支持按空间与状态筛选待处理冲突
CREATE INDEX IF NOT EXISTS "sync_conflicts_spaceId_status_idx" ON "sync_conflicts" ("spaceId", "status");
