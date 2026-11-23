-- 设备主动退出登录时间（非空表示已退出登录，从设备列表隐藏）
ALTER TABLE "devices" ADD COLUMN "loggedOutAt" TIMESTAMP(3);
