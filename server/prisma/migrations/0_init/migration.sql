-- 基线迁移：由 prisma/schema.prisma 一次性生成。
-- 历史增量迁移已合并到此文件；后续结构变化请新增独立迁移。

-- CreateSchema
CREATE SCHEMA IF NOT EXISTS "public";

-- CreateEnum
CREATE TYPE "UserStatus" AS ENUM ('active', 'disabled');

-- CreateEnum
CREATE TYPE "DevicePlatform" AS ENUM ('android', 'ios', 'windows', 'macos', 'linux', 'unknown');

-- CreateEnum
CREATE TYPE "CredentialStatus" AS ENUM ('active', 'revoked');

-- CreateEnum
CREATE TYPE "MemberRole" AS ENUM ('owner', 'admin', 'member', 'viewer');

-- CreateEnum
CREATE TYPE "SyncEventType" AS ENUM ('file_created', 'file_updated', 'file_deleted', 'file_renamed', 'file_restored', 'metadata_updated', 'conflict_detected', 'conflict_resolved');

-- CreateEnum
CREATE TYPE "UploadSessionStatus" AS ENUM ('pending', 'uploading', 'completed', 'aborted');

-- CreateEnum
CREATE TYPE "ConflictStatus" AS ENUM ('pending', 'resolved');

-- CreateTable
CREATE TABLE "users" (
    "id" UUID NOT NULL,
    "email" TEXT NOT NULL,
    "passwordHash" TEXT NOT NULL,
    "displayName" TEXT NOT NULL,
    "status" "UserStatus" NOT NULL DEFAULT 'active',
    "primaryDeviceId" UUID,
    "createdAt" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "updatedAt" TIMESTAMP(3) NOT NULL,

    CONSTRAINT "users_pkey" PRIMARY KEY ("id")
);

-- CreateTable
CREATE TABLE "devices" (
    "id" UUID NOT NULL,
    "userId" UUID NOT NULL,
    "deviceName" TEXT NOT NULL,
    "platform" "DevicePlatform" NOT NULL DEFAULT 'android',
    "credentialStatus" "CredentialStatus" NOT NULL DEFAULT 'active',
    "lastSeenAt" TIMESTAMP(3),
    "rejoinRequestedAt" TIMESTAMP(3),
    "loggedOutAt" TIMESTAMP(3),
    "createdAt" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT "devices_pkey" PRIMARY KEY ("id")
);

-- CreateTable
CREATE TABLE "refresh_tokens" (
    "id" UUID NOT NULL,
    "userId" UUID NOT NULL,
    "deviceId" UUID,
    "tokenHash" TEXT NOT NULL,
    "expiresAt" TIMESTAMP(3) NOT NULL,
    "revokedAt" TIMESTAMP(3),
    "createdAt" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT "refresh_tokens_pkey" PRIMARY KEY ("id")
);

-- CreateTable
CREATE TABLE "sync_spaces" (
    "id" UUID NOT NULL,
    "ownerId" UUID NOT NULL,
    "name" TEXT NOT NULL,
    "description" TEXT,
    "revision" BIGINT NOT NULL DEFAULT 0,
    "createdAt" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "updatedAt" TIMESTAMP(3) NOT NULL,

    CONSTRAINT "sync_spaces_pkey" PRIMARY KEY ("id")
);

-- CreateTable
CREATE TABLE "space_members" (
    "id" UUID NOT NULL,
    "spaceId" UUID NOT NULL,
    "deviceId" UUID NOT NULL,
    "role" "MemberRole" NOT NULL DEFAULT 'member',
    "joinedAt" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT "space_members_pkey" PRIMARY KEY ("id")
);

-- CreateTable
CREATE TABLE "space_join_requests" (
    "id" UUID NOT NULL,
    "spaceId" UUID NOT NULL,
    "deviceId" UUID NOT NULL,
    "status" TEXT NOT NULL DEFAULT 'pending',
    "createdAt" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "resolvedAt" TIMESTAMP(3),

    CONSTRAINT "space_join_requests_pkey" PRIMARY KEY ("id")
);

-- CreateTable
CREATE TABLE "folder_mappings" (
    "id" UUID NOT NULL,
    "deviceId" UUID NOT NULL,
    "spaceId" UUID NOT NULL,
    "localPathLabel" TEXT NOT NULL,
    "treeUri" TEXT,
    "filters" JSONB,
    "createdAt" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT "folder_mappings_pkey" PRIMARY KEY ("id")
);

-- CreateTable
CREATE TABLE "sync_files" (
    "id" UUID NOT NULL,
    "spaceId" UUID NOT NULL,
    "relativePath" TEXT NOT NULL,
    "fileName" TEXT NOT NULL,
    "objectId" TEXT NOT NULL,
    "size" BIGINT NOT NULL,
    "sha256" TEXT NOT NULL,
    "version" INTEGER NOT NULL DEFAULT 1,
    "isDeleted" BOOLEAN NOT NULL DEFAULT false,
    "createdAt" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "updatedAt" TIMESTAMP(3) NOT NULL,
    "lastModifiedByDeviceId" UUID NOT NULL,

    CONSTRAINT "sync_files_pkey" PRIMARY KEY ("id")
);

-- CreateTable
CREATE TABLE "file_versions" (
    "id" UUID NOT NULL,
    "fileId" UUID NOT NULL,
    "version" INTEGER NOT NULL,
    "objectId" TEXT NOT NULL,
    "size" BIGINT NOT NULL,
    "sha256" TEXT NOT NULL,
    "createdAt" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "createdByDeviceId" UUID NOT NULL,

    CONSTRAINT "file_versions_pkey" PRIMARY KEY ("id")
);

-- CreateTable
CREATE TABLE "sync_events" (
    "id" UUID NOT NULL,
    "spaceId" UUID NOT NULL,
    "revision" BIGINT NOT NULL,
    "type" "SyncEventType" NOT NULL,
    "payload" JSONB NOT NULL,
    "deviceId" UUID NOT NULL,
    "operationId" TEXT,
    "createdAt" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT "sync_events_pkey" PRIMARY KEY ("id")
);

-- CreateTable
CREATE TABLE "sync_cursors" (
    "id" UUID NOT NULL,
    "deviceId" UUID NOT NULL,
    "spaceId" UUID NOT NULL,
    "lastAppliedRevision" BIGINT NOT NULL DEFAULT 0,
    "updatedAt" TIMESTAMP(3) NOT NULL,

    CONSTRAINT "sync_cursors_pkey" PRIMARY KEY ("id")
);

-- CreateTable
CREATE TABLE "sync_conflicts" (
    "id" UUID NOT NULL,
    "spaceId" UUID NOT NULL,
    "fileId" UUID NOT NULL,
    "serverVersion" INTEGER NOT NULL,
    "submittedVersion" JSONB NOT NULL,
    "status" "ConflictStatus" NOT NULL DEFAULT 'pending',
    "resolvedAt" TIMESTAMP(3),
    "createdAt" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT "sync_conflicts_pkey" PRIMARY KEY ("id")
);

-- CreateTable
CREATE TABLE "upload_sessions" (
    "id" UUID NOT NULL,
    "fileId" UUID,
    "deviceId" UUID NOT NULL,
    "spaceId" UUID NOT NULL,
    "relativePath" TEXT NOT NULL,
    "sha256" TEXT NOT NULL,
    "idempotencyKey" TEXT NOT NULL,
    "providerUploadId" TEXT NOT NULL,
    "objectId" UUID NOT NULL,
    "totalSize" BIGINT NOT NULL,
    "partSize" BIGINT NOT NULL,
    "status" "UploadSessionStatus" NOT NULL DEFAULT 'pending',
    "parts" JSONB,
    "createdAt" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "updatedAt" TIMESTAMP(3) NOT NULL,

    CONSTRAINT "upload_sessions_pkey" PRIMARY KEY ("id")
);

-- CreateTable
CREATE TABLE "trash_items" (
    "id" UUID NOT NULL,
    "spaceId" UUID NOT NULL,
    "fileId" UUID NOT NULL,
    "relativePath" TEXT NOT NULL,
    "deletedByDeviceId" UUID NOT NULL,
    "deletedAt" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "purgeAfter" TIMESTAMP(3) NOT NULL,
    "objectId" TEXT NOT NULL,
    "restoredAt" TIMESTAMP(3),

    CONSTRAINT "trash_items_pkey" PRIMARY KEY ("id")
);

-- CreateIndex
CREATE UNIQUE INDEX "users_email_key" ON "users"("email");

-- CreateIndex
CREATE INDEX "devices_userId_idx" ON "devices"("userId");

-- CreateIndex
CREATE UNIQUE INDEX "refresh_tokens_tokenHash_key" ON "refresh_tokens"("tokenHash");

-- CreateIndex
CREATE INDEX "refresh_tokens_userId_idx" ON "refresh_tokens"("userId");

-- CreateIndex
CREATE INDEX "refresh_tokens_deviceId_idx" ON "refresh_tokens"("deviceId");

-- CreateIndex
CREATE INDEX "sync_spaces_ownerId_idx" ON "sync_spaces"("ownerId");

-- CreateIndex
CREATE INDEX "space_members_deviceId_idx" ON "space_members"("deviceId");

-- CreateIndex
CREATE UNIQUE INDEX "space_members_spaceId_deviceId_key" ON "space_members"("spaceId", "deviceId");

-- CreateIndex
CREATE INDEX "space_join_requests_spaceId_status_idx" ON "space_join_requests"("spaceId", "status");

-- CreateIndex
CREATE INDEX "space_join_requests_deviceId_idx" ON "space_join_requests"("deviceId");

-- CreateIndex
CREATE INDEX "folder_mappings_deviceId_idx" ON "folder_mappings"("deviceId");

-- CreateIndex
CREATE UNIQUE INDEX "folder_mappings_spaceId_deviceId_key" ON "folder_mappings"("spaceId", "deviceId");

-- CreateIndex
CREATE INDEX "sync_files_spaceId_relativePath_idx" ON "sync_files"("spaceId", "relativePath");

-- CreateIndex
CREATE INDEX "sync_files_spaceId_isDeleted_idx" ON "sync_files"("spaceId", "isDeleted");

-- CreateIndex
CREATE UNIQUE INDEX "file_versions_fileId_version_key" ON "file_versions"("fileId", "version");

-- CreateIndex
CREATE INDEX "sync_events_spaceId_createdAt_idx" ON "sync_events"("spaceId", "createdAt");

-- CreateIndex
CREATE UNIQUE INDEX "sync_events_spaceId_revision_key" ON "sync_events"("spaceId", "revision");

-- CreateIndex
CREATE UNIQUE INDEX "sync_events_spaceId_deviceId_operationId_key" ON "sync_events"("spaceId", "deviceId", "operationId");

-- CreateIndex
CREATE UNIQUE INDEX "sync_cursors_deviceId_spaceId_key" ON "sync_cursors"("deviceId", "spaceId");

-- CreateIndex
CREATE INDEX "sync_conflicts_spaceId_fileId_idx" ON "sync_conflicts"("spaceId", "fileId");

-- CreateIndex
CREATE INDEX "sync_conflicts_spaceId_status_idx" ON "sync_conflicts"("spaceId", "status");

-- CreateIndex
CREATE INDEX "sync_conflicts_status_idx" ON "sync_conflicts"("status");

-- CreateIndex
CREATE INDEX "upload_sessions_deviceId_idx" ON "upload_sessions"("deviceId");

-- CreateIndex
CREATE INDEX "upload_sessions_spaceId_idx" ON "upload_sessions"("spaceId");

-- CreateIndex
CREATE INDEX "upload_sessions_fileId_idx" ON "upload_sessions"("fileId");

-- CreateIndex
CREATE UNIQUE INDEX "upload_sessions_deviceId_idempotencyKey_key" ON "upload_sessions"("deviceId", "idempotencyKey");

-- CreateIndex
CREATE INDEX "trash_items_spaceId_purgeAfter_idx" ON "trash_items"("spaceId", "purgeAfter");

-- CreateIndex
CREATE UNIQUE INDEX "trash_items_fileId_key" ON "trash_items"("fileId");

-- AddForeignKey
ALTER TABLE "devices" ADD CONSTRAINT "devices_userId_fkey" FOREIGN KEY ("userId") REFERENCES "users"("id") ON DELETE CASCADE ON UPDATE CASCADE;

-- AddForeignKey
ALTER TABLE "refresh_tokens" ADD CONSTRAINT "refresh_tokens_userId_fkey" FOREIGN KEY ("userId") REFERENCES "users"("id") ON DELETE CASCADE ON UPDATE CASCADE;

-- AddForeignKey
ALTER TABLE "refresh_tokens" ADD CONSTRAINT "refresh_tokens_deviceId_fkey" FOREIGN KEY ("deviceId") REFERENCES "devices"("id") ON DELETE SET NULL ON UPDATE CASCADE;

-- AddForeignKey
ALTER TABLE "sync_spaces" ADD CONSTRAINT "sync_spaces_ownerId_fkey" FOREIGN KEY ("ownerId") REFERENCES "users"("id") ON DELETE CASCADE ON UPDATE CASCADE;

-- AddForeignKey
ALTER TABLE "space_members" ADD CONSTRAINT "space_members_spaceId_fkey" FOREIGN KEY ("spaceId") REFERENCES "sync_spaces"("id") ON DELETE CASCADE ON UPDATE CASCADE;

-- AddForeignKey
ALTER TABLE "space_members" ADD CONSTRAINT "space_members_deviceId_fkey" FOREIGN KEY ("deviceId") REFERENCES "devices"("id") ON DELETE CASCADE ON UPDATE CASCADE;

-- AddForeignKey
ALTER TABLE "space_join_requests" ADD CONSTRAINT "space_join_requests_spaceId_fkey" FOREIGN KEY ("spaceId") REFERENCES "sync_spaces"("id") ON DELETE CASCADE ON UPDATE CASCADE;

-- AddForeignKey
ALTER TABLE "space_join_requests" ADD CONSTRAINT "space_join_requests_deviceId_fkey" FOREIGN KEY ("deviceId") REFERENCES "devices"("id") ON DELETE CASCADE ON UPDATE CASCADE;

-- AddForeignKey
ALTER TABLE "folder_mappings" ADD CONSTRAINT "folder_mappings_deviceId_fkey" FOREIGN KEY ("deviceId") REFERENCES "devices"("id") ON DELETE CASCADE ON UPDATE CASCADE;

-- AddForeignKey
ALTER TABLE "folder_mappings" ADD CONSTRAINT "folder_mappings_spaceId_fkey" FOREIGN KEY ("spaceId") REFERENCES "sync_spaces"("id") ON DELETE CASCADE ON UPDATE CASCADE;

-- AddForeignKey
ALTER TABLE "sync_files" ADD CONSTRAINT "sync_files_spaceId_fkey" FOREIGN KEY ("spaceId") REFERENCES "sync_spaces"("id") ON DELETE CASCADE ON UPDATE CASCADE;

-- AddForeignKey
ALTER TABLE "sync_files" ADD CONSTRAINT "sync_files_lastModifiedByDeviceId_fkey" FOREIGN KEY ("lastModifiedByDeviceId") REFERENCES "devices"("id") ON DELETE RESTRICT ON UPDATE CASCADE;

-- AddForeignKey
ALTER TABLE "file_versions" ADD CONSTRAINT "file_versions_fileId_fkey" FOREIGN KEY ("fileId") REFERENCES "sync_files"("id") ON DELETE CASCADE ON UPDATE CASCADE;

-- AddForeignKey
ALTER TABLE "file_versions" ADD CONSTRAINT "file_versions_createdByDeviceId_fkey" FOREIGN KEY ("createdByDeviceId") REFERENCES "devices"("id") ON DELETE RESTRICT ON UPDATE CASCADE;

-- AddForeignKey
ALTER TABLE "sync_events" ADD CONSTRAINT "sync_events_spaceId_fkey" FOREIGN KEY ("spaceId") REFERENCES "sync_spaces"("id") ON DELETE CASCADE ON UPDATE CASCADE;

-- AddForeignKey
ALTER TABLE "sync_events" ADD CONSTRAINT "sync_events_deviceId_fkey" FOREIGN KEY ("deviceId") REFERENCES "devices"("id") ON DELETE RESTRICT ON UPDATE CASCADE;

-- AddForeignKey
ALTER TABLE "sync_cursors" ADD CONSTRAINT "sync_cursors_deviceId_fkey" FOREIGN KEY ("deviceId") REFERENCES "devices"("id") ON DELETE CASCADE ON UPDATE CASCADE;

-- AddForeignKey
ALTER TABLE "sync_cursors" ADD CONSTRAINT "sync_cursors_spaceId_fkey" FOREIGN KEY ("spaceId") REFERENCES "sync_spaces"("id") ON DELETE CASCADE ON UPDATE CASCADE;

-- AddForeignKey
ALTER TABLE "sync_conflicts" ADD CONSTRAINT "sync_conflicts_spaceId_fkey" FOREIGN KEY ("spaceId") REFERENCES "sync_spaces"("id") ON DELETE CASCADE ON UPDATE CASCADE;

-- AddForeignKey
ALTER TABLE "sync_conflicts" ADD CONSTRAINT "sync_conflicts_fileId_fkey" FOREIGN KEY ("fileId") REFERENCES "sync_files"("id") ON DELETE CASCADE ON UPDATE CASCADE;

-- AddForeignKey
ALTER TABLE "upload_sessions" ADD CONSTRAINT "upload_sessions_fileId_fkey" FOREIGN KEY ("fileId") REFERENCES "sync_files"("id") ON DELETE SET NULL ON UPDATE CASCADE;

-- AddForeignKey
ALTER TABLE "upload_sessions" ADD CONSTRAINT "upload_sessions_deviceId_fkey" FOREIGN KEY ("deviceId") REFERENCES "devices"("id") ON DELETE CASCADE ON UPDATE CASCADE;

-- AddForeignKey
ALTER TABLE "upload_sessions" ADD CONSTRAINT "upload_sessions_spaceId_fkey" FOREIGN KEY ("spaceId") REFERENCES "sync_spaces"("id") ON DELETE CASCADE ON UPDATE CASCADE;

-- AddForeignKey
ALTER TABLE "trash_items" ADD CONSTRAINT "trash_items_spaceId_fkey" FOREIGN KEY ("spaceId") REFERENCES "sync_spaces"("id") ON DELETE CASCADE ON UPDATE CASCADE;

-- AddForeignKey
ALTER TABLE "trash_items" ADD CONSTRAINT "trash_items_fileId_fkey" FOREIGN KEY ("fileId") REFERENCES "sync_files"("id") ON DELETE CASCADE ON UPDATE CASCADE;

-- AddForeignKey
ALTER TABLE "trash_items" ADD CONSTRAINT "trash_items_deletedByDeviceId_fkey" FOREIGN KEY ("deletedByDeviceId") REFERENCES "devices"("id") ON DELETE RESTRICT ON UPDATE CASCADE;

-- 活动文件路径唯一性：Prisma schema 不支持部分唯一索引，在此补充。
-- 软删除后的路径可被重新创建或被重命名复用，仅未删除文件之间保持 (spaceId, relativePath) 唯一。
CREATE UNIQUE INDEX "sync_files_space_relativepath_active"
  ON "sync_files" ("spaceId", "relativePath")
  WHERE "isDeleted" = false;
