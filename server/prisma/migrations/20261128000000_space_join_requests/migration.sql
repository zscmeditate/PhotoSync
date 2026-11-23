-- 设备申请加入空间的待审批记录
CREATE TABLE "space_join_requests" (
    "id" UUID NOT NULL,
    "spaceId" UUID NOT NULL,
    "deviceId" UUID NOT NULL,
    "status" TEXT NOT NULL DEFAULT 'pending',
    "createdAt" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "resolvedAt" TIMESTAMP(3),

    CONSTRAINT "space_join_requests_pkey" PRIMARY KEY ("id")
);

CREATE INDEX "space_join_requests_spaceId_status_idx" ON "space_join_requests"("spaceId", "status");
CREATE INDEX "space_join_requests_deviceId_idx" ON "space_join_requests"("deviceId");

ALTER TABLE "space_join_requests"
    ADD CONSTRAINT "space_join_requests_spaceId_fkey"
    FOREIGN KEY ("spaceId") REFERENCES "sync_spaces"("id") ON DELETE CASCADE ON UPDATE CASCADE;

ALTER TABLE "space_join_requests"
    ADD CONSTRAINT "space_join_requests_deviceId_fkey"
    FOREIGN KEY ("deviceId") REFERENCES "devices"("id") ON DELETE CASCADE ON UPDATE CASCADE;
