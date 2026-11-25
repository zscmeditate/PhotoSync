import { randomUUID } from 'node:crypto';
import {
  Prisma,
  type CredentialStatus,
  type Device as PrismaDevice,
  type DevicePlatform,
  type FileVersion as PrismaFileVersion,
  type PrismaClient,
  type SyncConflict as PrismaSyncConflict,
  type SyncEvent as PrismaSyncEvent,
  type SyncEventType,
  type SpaceJoinRequest as PrismaSpaceJoinRequest,
  type SyncFile as PrismaSyncFile,
  type TrashItem as PrismaTrashItem,
  type UploadSession as PrismaUploadSession,
} from '@prisma/client';
import type {
  CredentialStatusValue,
  DevicePlatformValue,
  DeviceRecord,
  DeviceRepository,
  FolderMappingRecord,
  MappingCreateData,
  MappingUpdateData,
  RefreshTokenRecord,
  RefreshTokenRepository,
  JoinRequestStatusValue,
  SpaceJoinRequestWithDeviceRecord,
  SpaceMemberRecord,
  SpaceMemberWithDeviceRecord,
  SpaceRepository,
  SpaceStorageStatsRecord,
  SpaceUpdateData,
  SpaceWithCountsRecord,
  SyncFileRecord,
  SyncSpaceRecord,
  UserRecord,
  UserRepository,
  CommitFileCreatedData,
  CommitFileCreatedResult,
  CommitFileDeletedData,
  CommitFileRenamedData,
  CommitFileUpdatedData,
  CommitMutationResult,
  CommittedFileRecord,
  DownloadObjectRecord,
  FileCreatedPayload,
  FileDeletedPayload,
  FileRenamedPayload,
  FileRestoredPayload,
  FileUpdatedPayload,
  FileVersionRecord,
  SyncEventPayload,
  SyncEventRecord,
  SyncRepository,
  SyncStatusRecord,
  TransferRepository,
  UploadedPartRecord,
  UploadSessionCreateData,
  UploadSessionRecord,
  UploadSessionStatusValue,
  ConflictRecord,
  ConflictSubmittedVersion,
  RecordConflictData,
  ResolveConflictData,
  ResolveConflictResult,
  RestoreTrashResult,
  TrashItemRecord,
} from './ports';
import { TRASH_RETENTION_DAYS } from './ports';
import {
  fileAlreadyExists,
  fileIsDeleted,
  fileNotFound,
  conflictAlreadyResolved,
  conflictNotFound,
  idempotencyConflict,
  partConflict,
  pathConflict,
  spaceNotFound,
  syncConflict,
  trashNotFound,
  uploadNotFound,
  validationError,
  versionConflict,
} from '../lib/errors';

/**
 * Prisma 生产实现。所有生产数据库访问统一走这里，
 * 仓储接口（ports.ts）供服务层依赖注入与测试替身使用。
 */

/** 同步事件 payload 为普通对象，显式转为 Prisma Json 输入类型。 */
function asEventPayload(payload: SyncEventPayload): Prisma.InputJsonValue {
  return payload as unknown as Prisma.InputJsonValue;
}

export class PrismaUserRepository implements UserRepository {
  constructor(private readonly prisma: PrismaClient) {}

  async findByEmail(email: string): Promise<UserRecord | null> {
    return this.prisma.user.findUnique({ where: { email } });
  }

  async findById(id: string): Promise<UserRecord | null> {
    return this.prisma.user.findUnique({ where: { id } });
  }

  async create(data: {
    email: string;
    passwordHash: string;
    displayName: string;
  }): Promise<UserRecord> {
    return this.prisma.user.create({ data });
  }
}

export class PrismaDeviceRepository implements DeviceRepository {
  constructor(private readonly prisma: PrismaClient) {}

  async findById(id: string): Promise<DeviceRecord | null> {
    return this.prisma.device.findUnique({ where: { id } });
  }

  async listByUser(userId: string): Promise<DeviceRecord[]> {
    // 已主动退出登录的设备不再出现在列表中
    return this.prisma.device.findMany({
      where: { userId, loggedOutAt: null },
      orderBy: { createdAt: 'asc' },
    });
  }

  async create(data: {
    id?: string;
    userId: string;
    deviceName: string;
    platform: DevicePlatformValue;
    credentialStatus: CredentialStatusValue;
    lastSeenAt: Date;
  }): Promise<DeviceRecord> {
    return this.prisma.device.create({
      data: {
        ...(data.id ? { id: data.id } : {}),
        userId: data.userId,
        deviceName: data.deviceName,
        platform: data.platform as DevicePlatform,
        credentialStatus: data.credentialStatus as CredentialStatus,
        lastSeenAt: data.lastSeenAt,
      },
    });
  }

  async update(
    id: string,
    data: {
      deviceName?: string;
      credentialStatus?: CredentialStatusValue;
      lastSeenAt?: Date;
      rejoinRequestedAt?: Date | null;
      loggedOutAt?: Date | null;
    },
  ): Promise<DeviceRecord> {
    return this.prisma.device.update({
      where: { id },
      data: {
        ...(data.deviceName !== undefined ? { deviceName: data.deviceName } : {}),
        ...(data.credentialStatus !== undefined
          ? { credentialStatus: data.credentialStatus as CredentialStatus }
          : {}),
        ...(data.lastSeenAt !== undefined ? { lastSeenAt: data.lastSeenAt } : {}),
        ...(data.rejoinRequestedAt !== undefined
          ? { rejoinRequestedAt: data.rejoinRequestedAt }
          : {}),
        ...(data.loggedOutAt !== undefined ? { loggedOutAt: data.loggedOutAt } : {}),
      },
    });
  }

  async findPrimaryDeviceId(userId: string): Promise<string | null> {
    const user = await this.prisma.user.findUnique({
      where: { id: userId },
      select: { primaryDeviceId: true },
    });
    return user?.primaryDeviceId ?? null;
  }

  async claimPrimaryDevice(userId: string, deviceId: string): Promise<boolean> {
    // 条件更新保证并发下最多只有一台设备成为主设备。
    const result = await this.prisma.user.updateMany({
      where: { id: userId, OR: [{ primaryDeviceId: null }, { primaryDeviceId: deviceId }] },
      data: { primaryDeviceId: deviceId },
    });
    return result.count > 0;
  }

  async clearPrimaryDevice(userId: string, deviceId: string): Promise<boolean> {
    const result = await this.prisma.user.updateMany({
      where: { id: userId, primaryDeviceId: deviceId },
      data: { primaryDeviceId: null },
    });
    return result.count > 0;
  }
}

export class PrismaRefreshTokenRepository implements RefreshTokenRepository {
  constructor(private readonly prisma: PrismaClient) {}

  async findByHash(tokenHash: string): Promise<RefreshTokenRecord | null> {
    return this.prisma.refreshToken.findUnique({ where: { tokenHash } });
  }

  async create(data: {
    userId: string;
    deviceId: string | null;
    tokenHash: string;
    expiresAt: Date;
  }): Promise<RefreshTokenRecord> {
    return this.prisma.refreshToken.create({ data });
  }

  async revoke(id: string, revokedAt: Date): Promise<void> {
    await this.prisma.refreshToken.update({
      where: { id },
      data: { revokedAt },
    });
  }

  async revokeByDevice(deviceId: string, revokedAt: Date): Promise<void> {
    await this.prisma.refreshToken.updateMany({
      where: { deviceId, revokedAt: null },
      data: { revokedAt },
    });
  }
}

export class PrismaSpaceRepository implements SpaceRepository {
  constructor(private readonly prisma: PrismaClient) {}

  private withCounts(
    space: SyncSpaceRecord & { _count: { members: number; folderMappings: number } },
  ): SpaceWithCountsRecord {
    return {
      id: space.id,
      ownerId: space.ownerId,
      name: space.name,
      description: space.description,
      revision: space.revision,
      createdAt: space.createdAt,
      updatedAt: space.updatedAt,
      memberCount: space._count.members,
      mappingCount: space._count.folderMappings,
    };
  }

  async createWithOwner(data: {
    ownerId: string;
    name: string;
    description: string | null;
    deviceId: string;
  }): Promise<SyncSpaceRecord> {
    return this.prisma.syncSpace.create({
      data: {
        ownerId: data.ownerId,
        name: data.name,
        description: data.description,
        members: {
          create: { device: { connect: { id: data.deviceId } }, role: 'member' },
        },
        cursors: {
          create: {
            device: { connect: { id: data.deviceId } },
            lastAppliedRevision: 0n,
          },
        },
      },
    });
  }

  async findById(spaceId: string): Promise<SpaceWithCountsRecord | null> {
    const space = await this.prisma.syncSpace.findUnique({
      where: { id: spaceId },
      include: { _count: { select: { members: true, folderMappings: true } } },
    });
    return space ? this.withCounts(space) : null;
  }

  async listAccessible(userId: string, deviceIds: string[]): Promise<SpaceWithCountsRecord[]> {
    const spaces = await this.prisma.syncSpace.findMany({
      where: {
        OR: [{ ownerId: userId }, { members: { some: { deviceId: { in: deviceIds } } } }],
      },
      include: { _count: { select: { members: true, folderMappings: true } } },
      orderBy: { createdAt: 'desc' },
    });
    return spaces.map(
      (space: SyncSpaceRecord & { _count: { members: number; folderMappings: number } }) =>
        this.withCounts(space),
    );
  }

  async update(spaceId: string, data: SpaceUpdateData): Promise<SpaceWithCountsRecord> {
    const space = await this.prisma.syncSpace.update({
      where: { id: spaceId },
      data: {
        ...(data.name !== undefined ? { name: data.name } : {}),
        ...(data.description !== undefined ? { description: data.description } : {}),
      },
      include: { _count: { select: { members: true, folderMappings: true } } },
    });
    return this.withCounts(space);
  }

  async delete(spaceId: string): Promise<void> {
    await this.prisma.syncSpace.delete({ where: { id: spaceId } });
  }

  async listMembershipsForDevices(
    spaceId: string,
    deviceIds: string[],
  ): Promise<SpaceMemberRecord[]> {
    return this.prisma.spaceMember.findMany({
      where: { spaceId, deviceId: { in: deviceIds } },
    });
  }

  async listMembers(spaceId: string): Promise<SpaceMemberWithDeviceRecord[]> {
    return this.prisma.spaceMember.findMany({
      where: { spaceId },
      include: { device: true },
      orderBy: { joinedAt: 'asc' },
    });
  }

  async findMember(
    spaceId: string,
    deviceId: string,
  ): Promise<SpaceMemberWithDeviceRecord | null> {
    return this.prisma.spaceMember.findUnique({
      where: { spaceId_deviceId: { spaceId, deviceId } },
      include: { device: true },
    });
  }

  async addMember(spaceId: string, deviceId: string): Promise<SpaceMemberWithDeviceRecord> {
    const [member] = await this.prisma.$transaction([
      this.prisma.spaceMember.upsert({
        where: { spaceId_deviceId: { spaceId, deviceId } },
        create: { spaceId, deviceId, role: 'member' },
        update: {},
        include: { device: true },
      }),
      this.prisma.syncCursor.upsert({
        where: { deviceId_spaceId: { deviceId, spaceId } },
        create: { deviceId, spaceId, lastAppliedRevision: 0n },
        update: {},
      }),
    ]);
    return member;
  }

  async removeMember(spaceId: string, deviceId: string): Promise<void> {
    // 退出空间即解除该设备在此空间的目录绑定与同步游标，重新加入时需重新绑定目录。
    await this.prisma.$transaction([
      this.prisma.spaceMember.deleteMany({ where: { spaceId, deviceId } }),
      this.prisma.folderMapping.deleteMany({ where: { spaceId, deviceId } }),
      this.prisma.syncCursor.deleteMany({ where: { spaceId, deviceId } }),
    ]);
  }

  async clearDeviceBindings(deviceId: string): Promise<void> {
    await this.prisma.$transaction([
      this.prisma.spaceMember.deleteMany({ where: { deviceId } }),
      this.prisma.folderMapping.deleteMany({ where: { deviceId } }),
      this.prisma.syncCursor.deleteMany({ where: { deviceId } }),
      this.prisma.spaceJoinRequest.deleteMany({ where: { deviceId } }),
    ]);
  }

  private toJoinRequest(
    row: PrismaSpaceJoinRequest & { device: PrismaDevice },
  ): SpaceJoinRequestWithDeviceRecord {
    return {
      id: row.id,
      spaceId: row.spaceId,
      deviceId: row.deviceId,
      status: row.status as JoinRequestStatusValue,
      createdAt: row.createdAt,
      resolvedAt: row.resolvedAt,
      device: row.device,
    };
  }

  async findPendingJoinRequest(
    spaceId: string,
    deviceId: string,
  ): Promise<SpaceJoinRequestWithDeviceRecord | null> {
    const row = await this.prisma.spaceJoinRequest.findFirst({
      where: { spaceId, deviceId, status: 'pending' },
      orderBy: { createdAt: 'desc' },
      include: { device: true },
    });
    return row ? this.toJoinRequest(row) : null;
  }

  async createJoinRequest(data: {
    spaceId: string;
    deviceId: string;
    status?: JoinRequestStatusValue;
    resolvedAt?: Date | null;
  }): Promise<SpaceJoinRequestWithDeviceRecord> {
    const row = await this.prisma.spaceJoinRequest.create({
      data: {
        spaceId: data.spaceId,
        deviceId: data.deviceId,
        status: data.status ?? 'pending',
        ...(data.resolvedAt !== undefined ? { resolvedAt: data.resolvedAt } : {}),
      },
      include: { device: true },
    });
    return this.toJoinRequest(row);
  }

  async listPendingJoinRequests(spaceId: string): Promise<SpaceJoinRequestWithDeviceRecord[]> {
    const rows = await this.prisma.spaceJoinRequest.findMany({
      where: { spaceId, status: 'pending' },
      include: { device: true },
      orderBy: { createdAt: 'asc' },
    });
    return rows.map((row) => this.toJoinRequest(row));
  }

  async findJoinRequestById(requestId: string): Promise<SpaceJoinRequestWithDeviceRecord | null> {
    const row = await this.prisma.spaceJoinRequest.findUnique({
      where: { id: requestId },
      include: { device: true },
    });
    return row ? this.toJoinRequest(row) : null;
  }

  async updateJoinRequest(
    requestId: string,
    data: { status: JoinRequestStatusValue; resolvedAt: Date | null },
  ): Promise<SpaceJoinRequestWithDeviceRecord> {
    const row = await this.prisma.spaceJoinRequest.update({
      where: { id: requestId },
      data: { status: data.status, resolvedAt: data.resolvedAt },
      include: { device: true },
    });
    return this.toJoinRequest(row);
  }

  async listMappings(spaceId: string, deviceId?: string): Promise<FolderMappingRecord[]> {
    return this.prisma.folderMapping.findMany({
      where: { spaceId, ...(deviceId !== undefined ? { deviceId } : {}) },
      orderBy: { createdAt: 'asc' },
    });
  }

  async findMappingById(mappingId: string): Promise<FolderMappingRecord | null> {
    return this.prisma.folderMapping.findUnique({ where: { id: mappingId } });
  }

  async createMapping(data: MappingCreateData): Promise<FolderMappingRecord> {
    return this.prisma.folderMapping.create({ data });
  }

  async updateMapping(
    mappingId: string,
    data: MappingUpdateData,
  ): Promise<FolderMappingRecord> {
    return this.prisma.folderMapping.update({
      where: { id: mappingId },
      data: {
        ...(data.localPathLabel !== undefined ? { localPathLabel: data.localPathLabel } : {}),
        ...(data.filters !== undefined ? { filters: data.filters } : {}),
      },
    });
  }

  async deleteMapping(mappingId: string): Promise<void> {
    await this.prisma.folderMapping.delete({ where: { id: mappingId } });
  }

  async listActiveFiles(spaceId: string): Promise<SyncFileRecord[]> {
    return this.prisma.syncFile.findMany({
      where: { spaceId, isDeleted: false },
      select: { id: true, relativePath: true, objectId: true, size: true, sha256: true, version: true },
      orderBy: { relativePath: 'asc' },
    });
  }

  async storageStats(spaceId: string): Promise<SpaceStorageStatsRecord> {
    // 回收站表本身不存大小，需要通过关联文件聚合
    const [active, trashItems] = await Promise.all([
      this.prisma.syncFile.aggregate({
        where: { spaceId, isDeleted: false },
        _count: { _all: true },
        _sum: { size: true },
      }),
      this.prisma.trashItem.findMany({
        where: { spaceId },
        select: { file: { select: { size: true } } },
      }),
    ]);
    let trashSize = 0n;
    for (const item of trashItems) {
      trashSize += item.file.size;
    }
    return {
      activeFileCount: active._count._all,
      activeSize: active._sum.size ?? 0n,
      trashCount: trashItems.length,
      trashSize,
    };
  }
}

function toUploadSession(row: PrismaUploadSession): UploadSessionRecord {
  const rawParts = Array.isArray(row.parts) ? row.parts : [];
  const parts: UploadedPartRecord[] = rawParts.map((value: unknown) => {
    const part = value as { partNumber: number; etag: string; size: string | number; sha256: string };
    return {
      partNumber: Number(part.partNumber),
      etag: part.etag,
      size: BigInt(part.size),
      sha256: part.sha256,
    };
  });
  return {
    id: row.id,
    fileId: row.fileId,
    deviceId: row.deviceId,
    spaceId: row.spaceId,
    relativePath: row.relativePath,
    sha256: row.sha256,
    idempotencyKey: row.idempotencyKey,
    providerUploadId: row.providerUploadId,
    objectId: row.objectId,
    totalSize: row.totalSize,
    partSize: row.partSize,
    status: row.status,
    parts: parts.sort((a, b) => a.partNumber - b.partNumber),
    createdAt: row.createdAt,
    updatedAt: row.updatedAt,
  };
}

function storedParts(parts: UploadedPartRecord[]): Prisma.InputJsonValue {
  return parts.map((part) => ({
    partNumber: part.partNumber,
    etag: part.etag,
    size: part.size.toString(),
    sha256: part.sha256,
  }));
}

export class PrismaTransferRepository implements TransferRepository {
  constructor(private readonly prisma: PrismaClient) {}

  async findUploadById(uploadId: string): Promise<UploadSessionRecord | null> {
    const row = await this.prisma.uploadSession.findUnique({ where: { id: uploadId } });
    return row ? toUploadSession(row) : null;
  }

  async findUploadByIdempotency(
    deviceId: string,
    idempotencyKey: string,
  ): Promise<UploadSessionRecord | null> {
    const row = await this.prisma.uploadSession.findUnique({
      where: { deviceId_idempotencyKey: { deviceId, idempotencyKey } },
    });
    return row ? toUploadSession(row) : null;
  }

  async createUpload(
    data: UploadSessionCreateData,
  ): Promise<{ session: UploadSessionRecord; created: boolean }> {
    try {
      const row = await this.prisma.uploadSession.create({ data });
      return { session: toUploadSession(row), created: true };
    } catch (error) {
      if (!isPrismaError(error, 'P2002')) {
        throw error;
      }
      const existing = await this.findUploadByIdempotency(data.deviceId, data.idempotencyKey);
      if (!existing) {
        throw syncConflict();
      }
      return { session: existing, created: false };
    }
  }

  async saveUploadedPart(
    uploadId: string,
    part: UploadedPartRecord,
  ): Promise<UploadedPartRecord> {
    try {
      return await this.prisma.$transaction(
        async (transaction) => {
          const tx = transaction as unknown as PrismaClient;
          const row = await tx.uploadSession.findUnique({ where: { id: uploadId } });
          if (!row) {
            throw uploadNotFound();
          }
          const session = toUploadSession(row);
          const existing = session.parts.find((item) => item.partNumber === part.partNumber);
          if (existing) {
            if (existing.size === part.size && existing.sha256 === part.sha256) {
              return existing;
            }
            throw partConflict();
          }
          const parts = [...session.parts, part].sort((a, b) => a.partNumber - b.partNumber);
          await tx.uploadSession.update({
            where: { id: uploadId },
            data: { parts: storedParts(parts), status: 'uploading' },
          });
          return part;
        },
        { isolationLevel: Prisma.TransactionIsolationLevel.Serializable },
      );
    } catch (error) {
      if (isPrismaError(error, 'P2034')) {
        throw syncConflict('Concurrent upload part update; retry the request');
      }
      throw error;
    }
  }

  async markUploadStatus(
    uploadId: string,
    status: UploadSessionStatusValue,
  ): Promise<UploadSessionRecord> {
    try {
      const row = await this.prisma.uploadSession.update({ where: { id: uploadId }, data: { status } });
      return toUploadSession(row);
    } catch (error) {
      if (isPrismaError(error, 'P2025')) {
        throw uploadNotFound();
      }
      throw error;
    }
  }
}

function toCommittedFile(row: PrismaSyncFile): CommittedFileRecord {
  return {
    id: row.id,
    spaceId: row.spaceId,
    relativePath: row.relativePath,
    fileName: row.fileName,
    objectId: row.objectId,
    size: row.size,
    sha256: row.sha256,
    version: row.version,
    isDeleted: row.isDeleted,
    createdAt: row.createdAt,
    updatedAt: row.updatedAt,
    lastModifiedByDeviceId: row.lastModifiedByDeviceId,
  };
}

function toSyncEvent(row: PrismaSyncEvent): SyncEventRecord {
  return {
    id: row.id,
    spaceId: row.spaceId,
    revision: row.revision,
    type: row.type,
    deviceId: row.deviceId,
    operationId: row.operationId,
    payload: row.payload as unknown as SyncEventPayload,
    createdAt: row.createdAt,
  };
}

function toFileVersion(row: PrismaFileVersion): FileVersionRecord {
  return {
    id: row.id,
    fileId: row.fileId,
    version: row.version,
    objectId: row.objectId,
    size: row.size,
    sha256: row.sha256,
    createdAt: row.createdAt,
    createdByDeviceId: row.createdByDeviceId,
  };
}

function toTrashItem(row: PrismaTrashItem, file: PrismaSyncFile): TrashItemRecord {
  return {
    id: row.id,
    spaceId: row.spaceId,
    fileId: row.fileId,
    relativePath: row.relativePath,
    fileName: file.fileName,
    objectId: row.objectId,
    size: file.size,
    sha256: file.sha256,
    version: file.version,
    deletedByDeviceId: row.deletedByDeviceId,
    deletedAt: row.deletedAt,
    purgeAfter: row.purgeAfter,
  };
}

function toConflict(row: PrismaSyncConflict, file: PrismaSyncFile): ConflictRecord {
  return {
    id: row.id,
    spaceId: row.spaceId,
    fileId: row.fileId,
    relativePath: file.relativePath,
    fileName: file.fileName,
    serverVersion: row.serverVersion,
    submittedVersion: row.submittedVersion as unknown as ConflictSubmittedVersion,
    status: row.status as ConflictRecord['status'],
    resolvedAt: row.resolvedAt,
    createdAt: row.createdAt,
  };
}

export class PrismaSyncRepository implements SyncRepository {
  constructor(private readonly prisma: PrismaClient) {}

  async commitFileCreated(data: CommitFileCreatedData): Promise<CommitFileCreatedResult> {
    try {
      return await this.prisma.$transaction(
        async (transaction) => {
          const tx = transaction as unknown as PrismaClient;
          const existingEvent = await tx.syncEvent.findUnique({
            where: {
              spaceId_deviceId_operationId: {
                spaceId: data.spaceId,
                deviceId: data.deviceId,
                operationId: data.operationId,
              },
            },
          });
          if (existingEvent) {
            return this.duplicateResult(tx, existingEvent, data);
          }

          const existingFile = await tx.syncFile.findFirst({
            where: {
              spaceId: data.spaceId,
              relativePath: data.relativePath,
              isDeleted: false,
            },
          });
          if (existingFile) {
            throw fileAlreadyExists();
          }

          const space = await tx.syncSpace.update({
            where: { id: data.spaceId },
            data: { revision: { increment: 1 } },
            select: { revision: true },
          });
          const fileName = data.relativePath.split('/').at(-1);
          if (!fileName) {
            throw new Error('Normalized relative path has no file name');
          }
          const file = await tx.syncFile.create({
            data: {
              spaceId: data.spaceId,
              relativePath: data.relativePath,
              fileName,
              objectId: data.objectId,
              size: data.size,
              sha256: data.sha256,
              version: 1,
              lastModifiedByDeviceId: data.deviceId,
            },
          });
          await tx.fileVersion.create({
            data: {
              fileId: file.id,
              version: 1,
              objectId: data.objectId,
              size: data.size,
              sha256: data.sha256,
              createdByDeviceId: data.deviceId,
            },
          });
          const payload: FileCreatedPayload = {
            fileId: file.id,
            relativePath: file.relativePath,
            fileName: file.fileName,
            objectId: file.objectId,
            size: file.size.toString(),
            sha256: file.sha256,
            version: file.version,
            lastModifiedByDeviceId: file.lastModifiedByDeviceId,
          };
          const event = await tx.syncEvent.create({
            data: {
              spaceId: data.spaceId,
              revision: space.revision,
              type: 'file_created',
              payload: asEventPayload(payload),
              deviceId: data.deviceId,
              operationId: data.operationId,
            },
          });
          await tx.uploadSession.update({
            where: { id: data.uploadId },
            data: { fileId: file.id },
          });
          return { file: toCommittedFile(file), event: toSyncEvent(event), duplicate: false };
        },
        { isolationLevel: Prisma.TransactionIsolationLevel.Serializable },
      );
    } catch (error) {
      if (isPrismaError(error, 'P2002')) {
        const duplicate = await this.findDuplicateAfterConflict(data);
        if (duplicate) {
          return duplicate;
        }
        const pathOwner = await this.prisma.syncFile.findFirst({
          where: {
            spaceId: data.spaceId,
            relativePath: data.relativePath,
            isDeleted: false,
          },
          select: { id: true },
        });
        if (pathOwner) {
          throw fileAlreadyExists();
        }
        throw syncConflict();
      }
      if (isPrismaError(error, 'P2034')) {
        const duplicate = await this.findDuplicateAfterConflict(data);
        if (duplicate) {
          return duplicate;
        }
        throw syncConflict();
      }
      throw error;
    }
  }

  async commitFileUpdated(data: CommitFileUpdatedData): Promise<CommitMutationResult> {
    try {
      return await this.prisma.$transaction(
        async (transaction) => {
          const tx = transaction as unknown as PrismaClient;
          const existingEvent = await tx.syncEvent.findUnique({
            where: {
              spaceId_deviceId_operationId: {
                spaceId: data.spaceId,
                deviceId: data.deviceId,
                operationId: data.operationId,
              },
            },
          });
          if (existingEvent) {
            return this.duplicateMutation(tx, existingEvent, (payload) => {
              if (
                !('objectId' in payload) ||
                payload.fileId !== data.fileId ||
                payload.objectId !== data.objectId ||
                payload.sha256 !== data.sha256 ||
                payload.size !== data.size.toString()
              ) {
                throw idempotencyConflict('Operation ID was already used for a different update');
              }
            });
          }

          const file = await tx.syncFile.findUnique({ where: { id: data.fileId } });
          if (!file || file.spaceId !== data.spaceId) throw fileNotFound();
          if (file.isDeleted) throw fileIsDeleted();
          if (file.version !== data.baseVersion) throw versionConflict(file.version);
          if (file.relativePath !== data.relativePath) {
            throw syncConflict('Upload path does not match the current file path');
          }

          const space = await tx.syncSpace.update({
            where: { id: data.spaceId },
            data: { revision: { increment: 1 } },
            select: { revision: true },
          });
          const newVersion = file.version + 1;
          const updated = await tx.syncFile.update({
            where: { id: file.id },
            data: {
              objectId: data.objectId,
              size: data.size,
              sha256: data.sha256,
              version: newVersion,
              lastModifiedByDeviceId: data.deviceId,
            },
          });
          await tx.fileVersion.create({
            data: {
              fileId: file.id,
              version: newVersion,
              objectId: data.objectId,
              size: data.size,
              sha256: data.sha256,
              createdByDeviceId: data.deviceId,
            },
          });
          const payload: FileUpdatedPayload = {
            fileId: file.id,
            relativePath: file.relativePath,
            fileName: file.fileName,
            objectId: data.objectId,
            size: data.size.toString(),
            sha256: data.sha256,
            version: newVersion,
            baseVersion: data.baseVersion,
            lastModifiedByDeviceId: data.deviceId,
          };
          const event = await tx.syncEvent.create({
            data: {
              spaceId: data.spaceId,
              revision: space.revision,
              type: 'file_updated',
              payload: asEventPayload(payload),
              deviceId: data.deviceId,
              operationId: data.operationId,
            },
          });
          await tx.uploadSession.update({
            where: { id: data.uploadId },
            data: { fileId: file.id },
          });
          return { file: toCommittedFile(updated), event: toSyncEvent(event), duplicate: false };
        },
        { isolationLevel: Prisma.TransactionIsolationLevel.Serializable },
      );
    } catch (error) {
      if (isPrismaError(error, 'P2002') || isPrismaError(error, 'P2034')) {
        const duplicate = await this.findDuplicateMutationAfterConflict(
          data.spaceId,
          data.deviceId,
          data.operationId,
          (payload) => {
            if (
              !('objectId' in payload) ||
              payload.fileId !== data.fileId ||
              payload.objectId !== data.objectId ||
              payload.sha256 !== data.sha256 ||
              payload.size !== data.size.toString()
            ) {
              throw idempotencyConflict('Operation ID was already used for a different update');
            }
          },
        );
        if (duplicate) return duplicate;
        throw syncConflict();
      }
      throw error;
    }
  }

  async commitFileDeleted(data: CommitFileDeletedData): Promise<CommitMutationResult> {
    try {
      return await this.prisma.$transaction(
        async (transaction) => {
          const tx = transaction as unknown as PrismaClient;
          const existingEvent = await tx.syncEvent.findUnique({
            where: {
              spaceId_deviceId_operationId: {
                spaceId: data.spaceId,
                deviceId: data.deviceId,
                operationId: data.operationId,
              },
            },
          });
          if (existingEvent) {
            return this.duplicateMutation(tx, existingEvent, (payload) => {
              if (payload.fileId !== data.fileId) {
                throw idempotencyConflict('Operation ID was already used for a different deletion');
              }
            });
          }

          const file = await tx.syncFile.findUnique({ where: { id: data.fileId } });
          if (!file || file.spaceId !== data.spaceId) throw fileNotFound();
          if (file.isDeleted) throw fileIsDeleted();
          if (file.version !== data.baseVersion) throw versionConflict(file.version);

          const space = await tx.syncSpace.update({
            where: { id: data.spaceId },
            data: { revision: { increment: 1 } },
            select: { revision: true },
          });
          // Phase 4：仅软删除并传播事件，对象保留。
          // Phase 5：同时写入回收站条目（Tombstone），保留对象直至保留期结束。
          const updated = await tx.syncFile.update({
            where: { id: file.id },
            data: { isDeleted: true, lastModifiedByDeviceId: data.deviceId },
          });
          await tx.trashItem.create({
            data: {
              spaceId: data.spaceId,
              fileId: file.id,
              relativePath: file.relativePath,
              deletedByDeviceId: data.deviceId,
              purgeAfter: new Date(Date.now() + TRASH_RETENTION_DAYS * 24 * 60 * 60 * 1000),
              objectId: file.objectId,
            },
          });
          const payload: FileDeletedPayload = {
            fileId: file.id,
            relativePath: file.relativePath,
            fileName: file.fileName,
            version: file.version,
            baseVersion: data.baseVersion,
            lastModifiedByDeviceId: data.deviceId,
          };
          const event = await tx.syncEvent.create({
            data: {
              spaceId: data.spaceId,
              revision: space.revision,
              type: 'file_deleted',
              payload: asEventPayload(payload),
              deviceId: data.deviceId,
              operationId: data.operationId,
            },
          });
          return { file: toCommittedFile(updated), event: toSyncEvent(event), duplicate: false };
        },
        { isolationLevel: Prisma.TransactionIsolationLevel.Serializable },
      );
    } catch (error) {
      if (isPrismaError(error, 'P2002') || isPrismaError(error, 'P2034')) {
        const duplicate = await this.findDuplicateMutationAfterConflict(
          data.spaceId,
          data.deviceId,
          data.operationId,
          (payload) => {
            if (payload.fileId !== data.fileId) {
              throw idempotencyConflict('Operation ID was already used for a different deletion');
            }
          },
        );
        if (duplicate) return duplicate;
        throw syncConflict();
      }
      throw error;
    }
  }

  /**
   * 启动维护：清理历史上误上传的系统回收站文件。
   * Android 端已把 `.trashed-*`（MediaStore 删除改名的文件）与 `.Trash` 目录排除出同步，
   * 这里对库中已存在的这类文件补一次与正常删除一致的软删除：
   * 写回收站条目（30 天保留）并广播 file_deleted 事件，各设备下次拉取时移除本地副本。
   * 仅处理未删除文件，因此重复执行幂等。
   */
  async removeLegacySystemTrashFiles(): Promise<{ removed: number }> {
    const candidates = await this.prisma.syncFile.findMany({
      where: {
        isDeleted: false,
        OR: [
          { relativePath: { startsWith: '.trashed-' } },
          { relativePath: { contains: '/.trashed-' } },
          { relativePath: { startsWith: '.Trash/' } },
          { relativePath: { contains: '/.Trash/' } },
          { relativePath: { startsWith: '.trash/' } },
          { relativePath: { contains: '/.trash/' } },
        ],
      },
      select: {
        id: true,
        spaceId: true,
        relativePath: true,
        fileName: true,
        version: true,
        objectId: true,
        lastModifiedByDeviceId: true,
      },
    });
    let removed = 0;
    for (const candidate of candidates) {
      // 精确按路径段判断，避免误伤恰好包含这些子串的正常文件
      const isTrashEntry = candidate.relativePath.split('/').some((segment) => {
        return segment.startsWith('.trashed-') || segment.toLowerCase() === '.trash';
      });
      if (!isTrashEntry) continue;
      try {
        await this.prisma.$transaction(async (transaction) => {
          const tx = transaction as unknown as PrismaClient;
          const file = await tx.syncFile.findUnique({ where: { id: candidate.id } });
          if (!file || file.isDeleted) return;
          const space = await tx.syncSpace.update({
            where: { id: file.spaceId },
            data: { revision: { increment: 1 } },
            select: { revision: true },
          });
          await tx.syncFile.update({
            where: { id: file.id },
            data: { isDeleted: true },
          });
          await tx.trashItem.create({
            data: {
              spaceId: file.spaceId,
              fileId: file.id,
              relativePath: file.relativePath,
              deletedByDeviceId: file.lastModifiedByDeviceId,
              purgeAfter: new Date(Date.now() + TRASH_RETENTION_DAYS * 24 * 60 * 60 * 1000),
              objectId: file.objectId,
            },
          });
          const payload: FileDeletedPayload = {
            fileId: file.id,
            relativePath: file.relativePath,
            fileName: file.fileName,
            version: file.version,
            baseVersion: file.version,
            lastModifiedByDeviceId: file.lastModifiedByDeviceId,
          };
          await tx.syncEvent.create({
            data: {
              spaceId: file.spaceId,
              revision: space.revision,
              type: 'file_deleted',
              payload: asEventPayload(payload),
              deviceId: file.lastModifiedByDeviceId,
              operationId: randomUUID(),
            },
          });
          removed += 1;
        });
      } catch (error) {
        // 单个文件清理失败不应阻断启动，记录后继续
        console.error(`[maintenance] failed to clean system-trash file ${candidate.relativePath}:`, error);
      }
    }
    return { removed };
  }

  async commitFileRenamed(data: CommitFileRenamedData): Promise<CommitMutationResult> {
    try {
      return await this.prisma.$transaction(
        async (transaction) => {
          const tx = transaction as unknown as PrismaClient;
          const existingEvent = await tx.syncEvent.findUnique({
            where: {
              spaceId_deviceId_operationId: {
                spaceId: data.spaceId,
                deviceId: data.deviceId,
                operationId: data.operationId,
              },
            },
          });
          if (existingEvent) {
            return this.duplicateMutation(tx, existingEvent, (payload) => {
              if (
                !('newPath' in payload) ||
                payload.fileId !== data.fileId ||
                payload.newPath !== data.newRelativePath
              ) {
                throw idempotencyConflict('Operation ID was already used for a different rename');
              }
            });
          }

          const file = await tx.syncFile.findUnique({ where: { id: data.fileId } });
          if (!file || file.spaceId !== data.spaceId) throw fileNotFound();
          if (file.isDeleted) throw fileIsDeleted();
          if (file.version !== data.baseVersion) throw versionConflict(file.version);
          if (file.relativePath === data.newRelativePath) {
            throw validationError('newRelativePath must differ from the current path');
          }
          const newFileName = data.newRelativePath.split('/').at(-1);
          if (!newFileName) {
            throw validationError('newRelativePath has no file name');
          }
          const occupant = await tx.syncFile.findFirst({
            where: {
              spaceId: data.spaceId,
              relativePath: data.newRelativePath,
              isDeleted: false,
              id: { not: file.id },
            },
          });
          if (occupant) throw pathConflict();

          const space = await tx.syncSpace.update({
            where: { id: data.spaceId },
            data: { revision: { increment: 1 } },
            select: { revision: true },
          });
          const updated = await tx.syncFile.update({
            where: { id: file.id },
            data: {
              relativePath: data.newRelativePath,
              fileName: newFileName,
              lastModifiedByDeviceId: data.deviceId,
            },
          });
          const payload: FileRenamedPayload = {
            fileId: file.id,
            oldPath: file.relativePath,
            newPath: data.newRelativePath,
            oldFileName: file.fileName,
            newFileName,
            version: file.version,
            baseVersion: data.baseVersion,
            lastModifiedByDeviceId: data.deviceId,
          };
          const event = await tx.syncEvent.create({
            data: {
              spaceId: data.spaceId,
              revision: space.revision,
              type: 'file_renamed',
              payload: asEventPayload(payload),
              deviceId: data.deviceId,
              operationId: data.operationId,
            },
          });
          return { file: toCommittedFile(updated), event: toSyncEvent(event), duplicate: false };
        },
        { isolationLevel: Prisma.TransactionIsolationLevel.Serializable },
      );
    } catch (error) {
      if (isPrismaError(error, 'P2002') || isPrismaError(error, 'P2034')) {
        const duplicate = await this.findDuplicateMutationAfterConflict(
          data.spaceId,
          data.deviceId,
          data.operationId,
          (payload) => {
            if (
              !('newPath' in payload) ||
              payload.fileId !== data.fileId ||
              payload.newPath !== data.newRelativePath
            ) {
              throw idempotencyConflict('Operation ID was already used for a different rename');
            }
          },
        );
        if (duplicate) return duplicate;
        // P2002 可能来自目标路径的部分唯一索引冲突。
        const occupant = await this.prisma.syncFile.findFirst({
          where: {
            spaceId: data.spaceId,
            relativePath: data.newRelativePath,
            isDeleted: false,
          },
          select: { id: true },
        });
        if (occupant) throw pathConflict();
        throw syncConflict();
      }
      throw error;
    }
  }

  private async duplicateMutation(
    tx: PrismaClient,
    event: PrismaSyncEvent,
    validate: (payload: SyncEventPayload) => void,
  ): Promise<CommitMutationResult> {
    const converted = toSyncEvent(event);
    validate(converted.payload);
    const file = await tx.syncFile.findUnique({ where: { id: converted.payload.fileId } });
    if (!file) {
      throw syncConflict('Operation event exists without its committed file');
    }
    return { file: toCommittedFile(file), event: converted, duplicate: true };
  }

  private async findDuplicateMutationAfterConflict(
    spaceId: string,
    deviceId: string,
    operationId: string,
    validate: (payload: SyncEventPayload) => void,
  ): Promise<CommitMutationResult | null> {
    const event = await this.prisma.syncEvent.findUnique({
      where: { spaceId_deviceId_operationId: { spaceId, deviceId, operationId } },
    });
    if (!event) return null;
    return this.duplicateMutation(this.prisma, event, validate);
  }

  async getSpaceRevision(spaceId: string): Promise<bigint | null> {
    const space = await this.prisma.syncSpace.findUnique({
      where: { id: spaceId },
      select: { revision: true },
    });
    return space?.revision ?? null;
  }

  async listEvents(
    spaceId: string,
    sinceRevision: bigint,
    maxRevision: bigint,
    limit: number,
  ): Promise<SyncEventRecord[]> {
    const rows = await this.prisma.syncEvent.findMany({
      where: { spaceId, revision: { gt: sinceRevision, lte: maxRevision } },
      orderBy: { revision: 'asc' },
      take: limit,
    });
    return rows.map(toSyncEvent);
  }

  async acknowledge(
    spaceId: string,
    deviceId: string,
    lastAppliedRevision: bigint,
  ): Promise<{ serverRevision: bigint; lastAppliedRevision: bigint }> {
    return this.prisma.$transaction(async (transaction) => {
      const tx = transaction as unknown as PrismaClient;
      const space = await tx.syncSpace.findUnique({
        where: { id: spaceId },
        select: { revision: true },
      });
      if (!space) {
        throw spaceNotFound();
      }
      if (lastAppliedRevision > space.revision) {
        throw syncConflict('Acknowledgement exceeds the current server revision');
      }
      const key = { deviceId_spaceId: { deviceId, spaceId } };
      const cursor = await tx.syncCursor.findUnique({ where: key });
      if (cursor && cursor.lastAppliedRevision > lastAppliedRevision) {
        return { serverRevision: space.revision, lastAppliedRevision: cursor.lastAppliedRevision };
      }
      const updated = await tx.syncCursor.upsert({
        where: key,
        create: { spaceId, deviceId, lastAppliedRevision },
        update: { lastAppliedRevision },
      });
      return { serverRevision: space.revision, lastAppliedRevision: updated.lastAppliedRevision };
    });
  }

  async getStatus(spaceId: string, deviceId: string): Promise<SyncStatusRecord | null> {
    return this.prisma.$transaction(async (transaction) => {
      const tx = transaction as unknown as PrismaClient;
      const space = await tx.syncSpace.findUnique({
        where: { id: spaceId },
        select: { revision: true },
      });
      if (!space) {
        return null;
      }
      const cursor = await tx.syncCursor.findUnique({
        where: { deviceId_spaceId: { deviceId, spaceId } },
      });
      const lastAppliedRevision = cursor?.lastAppliedRevision ?? 0n;
      const [pendingEvents, activeFileCount] = await Promise.all([
        tx.syncEvent.count({ where: { spaceId, revision: { gt: lastAppliedRevision } } }),
        tx.syncFile.count({ where: { spaceId, isDeleted: false } }),
      ]);
      return { serverRevision: space.revision, lastAppliedRevision, pendingEvents, activeFileCount };
    });
  }

  async findObject(objectId: string): Promise<DownloadObjectRecord | null> {
    const file = await this.prisma.syncFile.findFirst({
      where: { objectId, isDeleted: false },
    });
    if (file) {
      return {
        objectId: file.objectId,
        spaceId: file.spaceId,
        fileName: file.fileName,
        size: file.size,
        sha256: file.sha256,
      };
    }
    const version = await this.prisma.fileVersion.findFirst({
      where: { objectId, file: { isDeleted: false } },
      include: { file: true },
    });
    if (!version) {
      return null;
    }
    return {
      objectId: version.objectId,
      spaceId: version.file.spaceId,
      fileName: version.file.fileName,
      size: version.size,
      sha256: version.sha256,
    };
  }

  async findFileById(fileId: string): Promise<CommittedFileRecord | null> {
    const file = await this.prisma.syncFile.findFirst({ where: { id: fileId, isDeleted: false } });
    return file ? toCommittedFile(file) : null;
  }

  async findFileByPath(spaceId: string, relativePath: string): Promise<CommittedFileRecord | null> {
    const file = await this.prisma.syncFile.findFirst({
      where: { spaceId, relativePath, isDeleted: false },
    });
    return file ? toCommittedFile(file) : null;
  }

  async listFileVersions(fileId: string): Promise<FileVersionRecord[]> {
    const rows = await this.prisma.fileVersion.findMany({
      where: { fileId },
      orderBy: { version: 'desc' },
    });
    return rows.map(toFileVersion);
  }

  // ===== Phase 5：冲突 =====

  async recordConflict(data: RecordConflictData): Promise<ConflictRecord> {
    const pending = await this.prisma.syncConflict.findFirst({
      where: { spaceId: data.spaceId, fileId: data.fileId, status: 'pending' },
      include: { file: true },
      orderBy: { createdAt: 'desc' },
    });
    if (pending) {
      const submitted = pending.submittedVersion as unknown as ConflictSubmittedVersion;
      if (submitted.sha256 === data.submitted.sha256) {
        return toConflict(pending, pending.file);
      }
    }
    const created = await this.prisma.syncConflict.create({
      data: {
        spaceId: data.spaceId,
        fileId: data.fileId,
        serverVersion: data.serverVersion,
        submittedVersion: data.submitted as unknown as Prisma.InputJsonValue,
        status: 'pending',
      },
      include: { file: true },
    });
    return toConflict(created, created.file);
  }

  async listConflicts(spaceId: string): Promise<ConflictRecord[]> {
    const rows = await this.prisma.syncConflict.findMany({
      where: { spaceId },
      include: { file: true },
      orderBy: { createdAt: 'desc' },
    });
    return rows.map((row: Prisma.SyncConflictGetPayload<{ include: { file: true } }>) =>
      toConflict(row, row.file),
    );
  }

  async findConflictById(conflictId: string): Promise<ConflictRecord | null> {
    const row = await this.prisma.syncConflict.findUnique({
      where: { id: conflictId },
      include: { file: true },
    });
    return row ? toConflict(row, row.file) : null;
  }

  async resolveConflict(data: ResolveConflictData): Promise<ResolveConflictResult> {
    return this.prisma.$transaction(
      async (transaction) => {
        const tx = transaction as unknown as PrismaClient;
        const conflict = await tx.syncConflict.findUnique({
          where: { id: data.conflictId },
          include: { file: true },
        });
        if (!conflict) throw conflictNotFound();
        if (conflict.status === 'resolved') throw conflictAlreadyResolved();
        const file = conflict.file;
        if (file.isDeleted) throw fileIsDeleted();
        const submitted = conflict.submittedVersion as unknown as ConflictSubmittedVersion;

        const space = await tx.syncSpace.update({
          where: { id: conflict.spaceId },
          data: { revision: { increment: 1 } },
          select: { revision: true },
        });

        let updatedFile: PrismaSyncFile | null = null;
        let eventType: SyncEventType;
        let payload: SyncEventPayload;
        if (data.resolution === 'server') {
          // 服务器版本保持不变，仅记录冲突已解决。
          eventType = 'conflict_resolved';
          payload = {
            conflictId: conflict.id,
            fileId: file.id,
            resolution: 'server',
            version: file.version,
          };
        } else if (data.resolution === 'client') {
          // 采纳设备提交版本：生成新的线性版本（内容取提交对象）。
          const newVersion = file.version + 1;
          const size = BigInt(submitted.size);
          updatedFile = await tx.syncFile.update({
            where: { id: file.id },
            data: {
              objectId: submitted.objectId,
              size,
              sha256: submitted.sha256,
              version: newVersion,
              lastModifiedByDeviceId: data.deviceId,
            },
          });
          await tx.fileVersion.create({
            data: {
              fileId: file.id,
              version: newVersion,
              objectId: submitted.objectId,
              size,
              sha256: submitted.sha256,
              createdByDeviceId: data.deviceId,
            },
          });
          eventType = 'file_updated';
          payload = {
            fileId: file.id,
            relativePath: file.relativePath,
            fileName: file.fileName,
            objectId: submitted.objectId,
            size: submitted.size,
            sha256: submitted.sha256,
            version: newVersion,
            baseVersion: file.version,
            lastModifiedByDeviceId: data.deviceId,
          };
        } else {
          // both：服务器版本保留原路径，设备提交版本另存为冲突副本新文件。
          const copyPath = await this.uniqueConflictPath(tx, conflict.spaceId, file.relativePath, data.deviceId);
          const copyName = copyPath.split('/').at(-1) ?? copyPath;
          const size = BigInt(submitted.size);
          const created = await tx.syncFile.create({
            data: {
              spaceId: conflict.spaceId,
              relativePath: copyPath,
              fileName: copyName,
              objectId: submitted.objectId,
              size,
              sha256: submitted.sha256,
              version: 1,
              lastModifiedByDeviceId: data.deviceId,
            },
          });
          await tx.fileVersion.create({
            data: {
              fileId: created.id,
              version: 1,
              objectId: submitted.objectId,
              size,
              sha256: submitted.sha256,
              createdByDeviceId: data.deviceId,
            },
          });
          updatedFile = created;
          eventType = 'file_created';
          payload = {
            fileId: created.id,
            relativePath: copyPath,
            fileName: copyName,
            objectId: submitted.objectId,
            size: submitted.size,
            sha256: submitted.sha256,
            version: 1,
            lastModifiedByDeviceId: data.deviceId,
          };
        }

        const resolved = await tx.syncConflict.update({
          where: { id: conflict.id },
          data: { status: 'resolved', resolvedAt: new Date() },
        });
        const event = await tx.syncEvent.create({
          data: {
            spaceId: conflict.spaceId,
            revision: space.revision,
            type: eventType,
            payload: asEventPayload(payload),
            deviceId: data.deviceId,
            operationId: data.operationId,
          },
        });
        return {
          conflict: toConflict(resolved, file),
          file: updatedFile ? toCommittedFile(updatedFile) : null,
          event: toSyncEvent(event),
        };
      },
      { isolationLevel: Prisma.TransactionIsolationLevel.Serializable },
    );
  }

  /** 生成不与活动文件冲突的副本路径：`name (conflict from <device>) .ext`，必要时追加序号。 */
  private async uniqueConflictPath(
    tx: PrismaClient,
    spaceId: string,
    relativePath: string,
    deviceId: string,
  ): Promise<string> {
    const slash = relativePath.lastIndexOf('/');
    const dir = slash >= 0 ? relativePath.slice(0, slash + 1) : '';
    const name = slash >= 0 ? relativePath.slice(slash + 1) : relativePath;
    const dot = name.lastIndexOf('.');
    const base = dot > 0 ? name.slice(0, dot) : name;
    const ext = dot > 0 ? name.slice(dot) : '';
    const tag = `conflict from ${deviceId.slice(0, 8)}`;
    let candidate = `${dir}${base} (${tag})${ext}`;
    let suffix = 2;
    // 冲突副本名称必须避免覆盖已有文件。
    while (
      await tx.syncFile.findFirst({
        where: { spaceId, relativePath: candidate, isDeleted: false },
        select: { id: true },
      })
    ) {
      candidate = `${dir}${base} (${tag} ${suffix})${ext}`;
      suffix += 1;
    }
    return candidate;
  }

  // ===== Phase 5：回收站 =====

  async listTrash(spaceId: string): Promise<TrashItemRecord[]> {
    const rows = await this.prisma.trashItem.findMany({
      where: { spaceId },
      include: { file: true },
      orderBy: { deletedAt: 'desc' },
    });
    return rows.map((row: Prisma.TrashItemGetPayload<{ include: { file: true } }>) =>
      toTrashItem(row, row.file),
    );
  }

  async findTrashItemById(trashId: string): Promise<TrashItemRecord | null> {
    const row = await this.prisma.trashItem.findUnique({
      where: { id: trashId },
      include: { file: true },
    });
    return row ? toTrashItem(row, row.file) : null;
  }

  async restoreTrashItem(
    trashId: string,
    deviceId: string,
    operationId: string,
  ): Promise<RestoreTrashResult> {
    return this.prisma.$transaction(
      async (transaction) => {
        const tx = transaction as unknown as PrismaClient;
        const item = await tx.trashItem.findUnique({
          where: { id: trashId },
          include: { file: true },
        });
        if (!item || !item.file.isDeleted) throw trashNotFound();
        const file = item.file;
        // 原路径若已被其他活动文件占用则无法恢复，交由用户处理。
        const occupant = await tx.syncFile.findFirst({
          where: {
            spaceId: item.spaceId,
            relativePath: item.relativePath,
            isDeleted: false,
            id: { not: file.id },
          },
          select: { id: true },
        });
        if (occupant) throw pathConflict();

        const space = await tx.syncSpace.update({
          where: { id: item.spaceId },
          data: { revision: { increment: 1 } },
          select: { revision: true },
        });
        const restored = await tx.syncFile.update({
          where: { id: file.id },
          data: { isDeleted: false, lastModifiedByDeviceId: deviceId },
        });
        await tx.trashItem.delete({ where: { id: item.id } });
        const payload: FileRestoredPayload = {
          fileId: file.id,
          relativePath: file.relativePath,
          fileName: file.fileName,
          objectId: restored.objectId,
          size: restored.size.toString(),
          sha256: restored.sha256,
          version: restored.version,
          lastModifiedByDeviceId: deviceId,
        };
        const event = await tx.syncEvent.create({
          data: {
            spaceId: item.spaceId,
            revision: space.revision,
            type: 'file_restored',
            payload: asEventPayload(payload),
            deviceId,
            operationId,
          },
        });
        return { file: toCommittedFile(restored), event: toSyncEvent(event) };
      },
      { isolationLevel: Prisma.TransactionIsolationLevel.Serializable },
    );
  }

  async purgeTrashItem(trashId: string): Promise<TrashItemRecord | null> {
    return this.prisma.$transaction(async (transaction) => {
      const tx = transaction as unknown as PrismaClient;
      const item = await tx.trashItem.findUnique({
        where: { id: trashId },
        include: { file: true },
      });
      if (!item) return null;
      await tx.trashItem.delete({ where: { id: trashId } });
      return toTrashItem(item, item.file);
    });
  }

  async countRecentDeletions(spaceId: string, deviceId: string, since: Date): Promise<number> {
    return this.prisma.syncEvent.count({
      where: { spaceId, deviceId, type: 'file_deleted', createdAt: { gte: since } },
    });
  }

  private async duplicateResult(
    tx: PrismaClient,
    event: PrismaSyncEvent,
    data: CommitFileCreatedData,
  ): Promise<CommitFileCreatedResult> {
    const converted = toSyncEvent(event);
    const payload = converted.payload;
    if (
      !('objectId' in payload) ||
      payload.objectId !== data.objectId ||
      payload.relativePath !== data.relativePath ||
      payload.sha256 !== data.sha256 ||
      payload.size !== data.size.toString()
    ) {
      throw idempotencyConflict('Operation ID was already used for a different file');
    }
    const file = await tx.syncFile.findUnique({ where: { id: converted.payload.fileId } });
    if (!file) {
      throw syncConflict('Operation event exists without its committed file');
    }
    return { file: toCommittedFile(file), event: converted, duplicate: true };
  }

  private async findDuplicateAfterConflict(
    data: CommitFileCreatedData,
  ): Promise<CommitFileCreatedResult | null> {
    const event = await this.prisma.syncEvent.findUnique({
      where: {
        spaceId_deviceId_operationId: {
          spaceId: data.spaceId,
          deviceId: data.deviceId,
          operationId: data.operationId,
        },
      },
    });
    if (!event) {
      return null;
    }
    return this.duplicateResult(this.prisma, event, data);
  }
}

function isPrismaError(error: unknown, code: string): boolean {
  return (
    error instanceof Prisma.PrismaClientKnownRequestError &&
    (error as { code?: string }).code === code
  );
}
