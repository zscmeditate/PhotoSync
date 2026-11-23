-- 同一账号的主设备身份（最多一台；为空表示还没有主设备）
ALTER TABLE "users" ADD COLUMN "primaryDeviceId" UUID;
