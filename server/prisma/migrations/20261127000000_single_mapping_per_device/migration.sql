-- 一个空间在每台设备上只保留一条目录映射：历史重复项仅保留最新一条
DELETE FROM "folder_mappings"
WHERE "id" NOT IN (
  SELECT DISTINCT ON ("spaceId", "deviceId") "id"
  FROM "folder_mappings"
  ORDER BY "spaceId", "deviceId", "createdAt" DESC, "id" DESC
);

-- 远端树直接由本地目录结构决定，不再支持自定义前缀和“是否包含子目录”
ALTER TABLE "folder_mappings" DROP COLUMN "remotePrefix", DROP COLUMN "includeSubdirs";

DROP INDEX IF EXISTS "folder_mappings_spaceId_idx";

CREATE UNIQUE INDEX "folder_mappings_spaceId_deviceId_key" ON "folder_mappings"("spaceId", "deviceId");
