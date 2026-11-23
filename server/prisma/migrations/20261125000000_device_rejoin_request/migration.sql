-- 被移除设备的重新加入申请时间（null 表示无待处理申请）
ALTER TABLE "devices" ADD COLUMN "rejoinRequestedAt" TIMESTAMP(3);
