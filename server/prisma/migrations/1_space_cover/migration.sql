-- 空间自定义头像：图片内容直接存库（客户端上传前已压缩到 512px），全体设备共用。
ALTER TABLE "sync_spaces" ADD COLUMN "coverImage" BYTEA;
ALTER TABLE "sync_spaces" ADD COLUMN "coverContentType" TEXT;
ALTER TABLE "sync_spaces" ADD COLUMN "coverUpdatedAt" TIMESTAMP(3);
