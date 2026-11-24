import {
  AppError,
  ErrorCodes,
  bulkDeleteConfirmationRequired,
  conflictNotFound,
  fileNotFound,
  invalidDevice,
  notMember,
  objectNotFound,
  operationUnsupported,
  spaceNotFound,
  trashNotFound,
  uploadNotCompleted,
  uploadNotFound,
  validationError,
} from '../lib/errors';
import { StorageObjectMissingError, type ObjectContent, type ObjectStore } from '../lib/object-store';
import type {
  CommittedFileRecord,
  ConflictRecord,
  ConflictResolution,
  DeviceRepository,
  DownloadObjectRecord,
  FileVersionRecord,
  SpaceRepository,
  SyncEventPayload,
  SyncEventRecord,
  SyncRepository,
  TransferRepository,
  TrashItemRecord,
} from '../repositories/ports';

/** 大量删除保护：同一设备在窗口期内连续删除达到该数量后需显式确认。 */
export const BULK_DELETE_THRESHOLD = 50;
export const BULK_DELETE_WINDOW_MS = 10 * 60 * 1000;

export interface FileDto {
  fileId: string;
  spaceId: string;
  relativePath: string;
  fileName: string;
  objectId: string;
  size: string;
  sha256: string;
  version: number;
  lastModifiedByDeviceId: string;
  createdAt: string;
  updatedAt: string;
  isDeleted: boolean;
}

export interface EventDto {
  revision: string;
  type: string;
  deviceId: string;
  createdAt: string;
  payload: SyncEventPayload;
}

/** 当前支持的同步操作类型；未知类型由 service 返回 OPERATION_UNSUPPORTED。 */
export const SUPPORTED_OPERATION_TYPES = [
  'file_created',
  'file_updated',
  'file_deleted',
  'file_renamed',
] as const;

export interface CommitOperationInput {
  deviceId: string;
  operationId: string;
  type: string;
  uploadId?: string;
  fileId?: string;
  baseVersion: number;
  newRelativePath?: string;
  /** 大量删除保护：超过阈值时需显式确认。 */
  confirmBulkDelete?: boolean;
}

export interface TrashItemDto {
  trashId: string;
  spaceId: string;
  fileId: string;
  relativePath: string;
  fileName: string;
  size: string;
  sha256: string;
  version: number;
  deletedByDeviceId: string;
  deletedAt: string;
  purgeAfter: string;
}

export interface ConflictDto {
  conflictId: string;
  spaceId: string;
  fileId: string;
  relativePath: string;
  fileName: string;
  serverVersion: number;
  submitted: {
    objectId: string;
    size: string;
    sha256: string;
    relativePath: string;
    fileName: string;
    uploadId: string | null;
    deviceId: string;
  };
  status: string;
  resolvedAt: string | null;
  createdAt: string;
}

export interface SyncServiceDeps {
  sync: SyncRepository;
  transfers: TransferRepository;
  spaces: SpaceRepository;
  devices: DeviceRepository;
  objectStore: ObjectStore;
}

export function toFileDto(file: CommittedFileRecord): FileDto {
  return {
    fileId: file.id,
    spaceId: file.spaceId,
    relativePath: file.relativePath,
    fileName: file.fileName,
    objectId: file.objectId,
    size: file.size.toString(),
    sha256: file.sha256,
    version: file.version,
    lastModifiedByDeviceId: file.lastModifiedByDeviceId,
    createdAt: file.createdAt.toISOString(),
    updatedAt: file.updatedAt.toISOString(),
    isDeleted: file.isDeleted,
  };
}

export function toEventDto(event: SyncEventRecord): EventDto {
  return {
    revision: event.revision.toString(),
    type: event.type,
    deviceId: event.deviceId,
    createdAt: event.createdAt.toISOString(),
    payload: event.payload,
  };
}

export class SyncService {
  constructor(private readonly deps: SyncServiceDeps) {}

  async commitOperation(
    userId: string,
    spaceId: string,
    input: CommitOperationInput,
  ): Promise<{ file: FileDto; event: EventDto; duplicate: boolean }> {
    await this.requireSpaceDevice(userId, spaceId, input.deviceId);

    switch (input.type) {
      case 'file_created': {
        if (input.baseVersion !== 0) {
          throw validationError('baseVersion must be 0 for file_created');
        }
        if (!input.uploadId) {
          throw validationError('uploadId is required for file_created');
        }
        const upload = await this.requireCompletedUpload(
          spaceId,
          input.deviceId,
          input.uploadId,
        );
        const result = await this.deps.sync.commitFileCreated({
          spaceId,
          deviceId: input.deviceId,
          operationId: input.operationId,
          uploadId: input.uploadId,
          relativePath: upload.relativePath,
          objectId: upload.objectId,
          size: upload.totalSize,
          sha256: upload.sha256,
        });
        return {
          file: toFileDto(result.file),
          event: toEventDto(result.event),
          duplicate: result.duplicate,
        };
      }
      case 'file_updated': {
        if (!input.uploadId) {
          throw validationError('uploadId is required for file_updated');
        }
        if (!input.fileId) {
          throw validationError('fileId is required for file_updated');
        }
        if (input.baseVersion < 1) {
          throw validationError('baseVersion must be >= 1 for file_updated');
        }
        const upload = await this.requireCompletedUpload(
          spaceId,
          input.deviceId,
          input.uploadId,
        );
        const result = await this.commitUpdatedWithConflictRecord(
          spaceId,
          input,
          upload.relativePath,
          upload.objectId,
          upload.totalSize,
          upload.sha256,
        );
        return {
          file: toFileDto(result.file),
          event: toEventDto(result.event),
          duplicate: result.duplicate,
        };
      }
      case 'file_deleted': {
        if (!input.fileId) {
          throw validationError('fileId is required for file_deleted');
        }
        if (input.baseVersion < 1) {
          throw validationError('baseVersion must be >= 1 for file_deleted');
        }
        // 大量删除保护：窗口期内同一设备删除过多文件时要求显式确认，避免误删扩散。
        if (!input.confirmBulkDelete) {
          const since = new Date(Date.now() - BULK_DELETE_WINDOW_MS);
          const recent = await this.deps.sync.countRecentDeletions(
            spaceId,
            input.deviceId,
            since,
          );
          if (recent >= BULK_DELETE_THRESHOLD) {
            throw bulkDeleteConfirmationRequired(BULK_DELETE_THRESHOLD);
          }
        }
        const result = await this.deps.sync.commitFileDeleted({
          spaceId,
          deviceId: input.deviceId,
          operationId: input.operationId,
          fileId: input.fileId,
          baseVersion: input.baseVersion,
        });
        return {
          file: toFileDto(result.file),
          event: toEventDto(result.event),
          duplicate: result.duplicate,
        };
      }
      case 'file_renamed': {
        if (!input.fileId) {
          throw validationError('fileId is required for file_renamed');
        }
        if (input.baseVersion < 1) {
          throw validationError('baseVersion must be >= 1 for file_renamed');
        }
        if (!input.newRelativePath) {
          throw validationError('newRelativePath is required for file_renamed');
        }
        const result = await this.deps.sync.commitFileRenamed({
          spaceId,
          deviceId: input.deviceId,
          operationId: input.operationId,
          fileId: input.fileId,
          baseVersion: input.baseVersion,
          newRelativePath: input.newRelativePath,
        });
        return {
          file: toFileDto(result.file),
          event: toEventDto(result.event),
          duplicate: result.duplicate,
        };
      }
      default:
        throw operationUnsupported();
    }
  }

  private async requireCompletedUpload(
    spaceId: string,
    deviceId: string,
    uploadId: string,
  ) {
    const upload = await this.deps.transfers.findUploadById(uploadId);
    if (!upload || upload.spaceId !== spaceId || upload.deviceId !== deviceId) {
      throw uploadNotFound();
    }
    if (upload.status !== 'completed') {
      throw uploadNotCompleted();
    }
    return upload;
  }

  /** 修改提交：baseVersion 冲突时记录冲突记录（保留双方），再原样抛出 VERSION_CONFLICT。 */
  private async commitUpdatedWithConflictRecord(
    spaceId: string,
    input: CommitOperationInput,
    relativePath: string,
    objectId: string,
    size: bigint,
    sha256: string,
  ) {
    try {
      return await this.deps.sync.commitFileUpdated({
        spaceId,
        deviceId: input.deviceId,
        operationId: input.operationId,
        uploadId: input.uploadId as string,
        fileId: input.fileId as string,
        baseVersion: input.baseVersion,
        relativePath,
        objectId,
        size,
        sha256,
      });
    } catch (error) {
      if (error instanceof AppError && error.code === ErrorCodes.VERSION_CONFLICT) {
        const current = await this.deps.sync.findFileById(input.fileId as string);
        if (current) {
          await this.deps.sync.recordConflict({
            spaceId,
            fileId: current.id,
            serverVersion: current.version,
            submitted: {
              objectId,
              size: size.toString(),
              sha256,
              relativePath,
              fileName: relativePath.split('/').at(-1) ?? relativePath,
              uploadId: input.uploadId as string,
              deviceId: input.deviceId,
            },
          });
        }
      }
      throw error;
    }
  }

  async listChanges(
    userId: string,
    spaceId: string,
    deviceId: string,
    sinceRevision: bigint,
    limit: number,
  ): Promise<{
    events: EventDto[];
    nextRevision: string;
    hasMore: boolean;
    serverRevision: string;
  }> {
    await this.requireSpaceDevice(userId, spaceId, deviceId);
    const serverRevision = await this.deps.sync.getSpaceRevision(spaceId);
    if (serverRevision === null) {
      throw spaceNotFound();
    }
    const rows = await this.deps.sync.listEvents(
      spaceId,
      sinceRevision,
      serverRevision,
      limit + 1,
    );
    const hasMore = rows.length > limit;
    const events = rows.slice(0, limit);
    const nextRevision = events.at(-1)?.revision ?? sinceRevision;
    return {
      events: events.map(toEventDto),
      nextRevision: nextRevision.toString(),
      hasMore,
      serverRevision: serverRevision.toString(),
    };
  }

  async acknowledge(
    userId: string,
    spaceId: string,
    deviceId: string,
    lastAppliedRevision: bigint,
  ): Promise<{ spaceId: string; lastAppliedRevision: string; serverRevision: string }> {
    await this.requireSpaceDevice(userId, spaceId, deviceId);
    const currentRevision = await this.deps.sync.getSpaceRevision(spaceId);
    if (currentRevision === null) {
      throw spaceNotFound();
    }
    if (lastAppliedRevision > currentRevision) {
      throw validationError('lastAppliedRevision must not exceed serverRevision');
    }
    const result = await this.deps.sync.acknowledge(spaceId, deviceId, lastAppliedRevision);
    return {
      spaceId,
      lastAppliedRevision: result.lastAppliedRevision.toString(),
      serverRevision: result.serverRevision.toString(),
    };
  }

  async getStatus(
    userId: string,
    spaceId: string,
    deviceId: string,
  ): Promise<{
    spaceId: string;
    serverRevision: string;
    lastAppliedRevision: string;
    pendingEvents: number;
    activeFileCount: number;
  }> {
    await this.requireSpaceDevice(userId, spaceId, deviceId);
    const status = await this.deps.sync.getStatus(spaceId, deviceId);
    if (!status) {
      throw spaceNotFound();
    }
    return {
      spaceId,
      serverRevision: status.serverRevision.toString(),
      lastAppliedRevision: status.lastAppliedRevision.toString(),
      pendingEvents: status.pendingEvents,
      activeFileCount: status.activeFileCount,
    };
  }

  async downloadObject(
    userId: string,
    objectId: string,
    deviceId: string,
    range?: string,
  ): Promise<{ metadata: DownloadObjectRecord; content: ObjectContent }> {
    const device = await this.deps.devices.findById(deviceId);
    if (!device || device.userId !== userId || device.credentialStatus !== 'active') {
      throw invalidDevice();
    }
    const metadata = await this.deps.sync.findObject(objectId);
    if (!metadata) {
      throw objectNotFound();
    }
    await this.requireSpaceDevice(userId, metadata.spaceId, deviceId);
    let content: ObjectContent;
    try {
      content = await this.deps.objectStore.getObject(objectId, range);
    } catch (error) {
      // 元数据在但对象已被清理/丢失：返回可识别的 404，让客户端按永久失败处理而不是整轮同步失败。
      if (error instanceof StorageObjectMissingError) {
        throw objectNotFound();
      }
      throw error;
    }
    return { metadata, content };
  }

  async listVersions(
    userId: string,
    fileId: string,
    deviceId: string,
  ): Promise<{
    versions: Array<{
      fileId: string;
      version: number;
      objectId: string;
      size: string;
      sha256: string;
      createdAt: string;
      createdByDeviceId: string;
    }>;
  }> {
    const file = await this.deps.sync.findFileById(fileId);
    if (!file) {
      throw fileNotFound();
    }
    await this.requireSpaceDevice(userId, file.spaceId, deviceId);
    const versions = await this.deps.sync.listFileVersions(fileId);
    return { versions: versions.map(toVersionDto) };
  }

  async listConflicts(
    userId: string,
    spaceId: string,
    deviceId: string,
  ): Promise<{ conflicts: ConflictDto[] }> {
    await this.requireSpaceDevice(userId, spaceId, deviceId);
    const rows = await this.deps.sync.listConflicts(spaceId);
    return { conflicts: rows.map(toConflictDto) };
  }

  async resolveConflict(
    userId: string,
    conflictId: string,
    deviceId: string,
    resolution: ConflictResolution,
    operationId: string,
  ): Promise<{ conflict: ConflictDto; file: FileDto | null; event: EventDto }> {
    const conflict = await this.deps.sync.findConflictById(conflictId);
    if (!conflict) {
      throw conflictNotFound();
    }
    await this.requireSpaceDevice(userId, conflict.spaceId, deviceId);
    const result = await this.deps.sync.resolveConflict({
      conflictId,
      resolution,
      deviceId,
      operationId,
    });
    return {
      conflict: toConflictDto(result.conflict),
      file: result.file ? toFileDto(result.file) : null,
      event: toEventDto(result.event),
    };
  }

  async listTrash(
    userId: string,
    spaceId: string,
    deviceId: string,
  ): Promise<{ items: TrashItemDto[] }> {
    await this.requireSpaceDevice(userId, spaceId, deviceId);
    const rows = await this.deps.sync.listTrash(spaceId);
    return { items: rows.map(toTrashDto) };
  }

  async restoreTrash(
    userId: string,
    trashId: string,
    deviceId: string,
    operationId: string,
  ): Promise<{ file: FileDto; event: EventDto }> {
    const item = await this.deps.sync.findTrashItemById(trashId);
    if (!item) {
      throw trashNotFound();
    }
    await this.requireSpaceDevice(userId, item.spaceId, deviceId);
    const result = await this.deps.sync.restoreTrashItem(trashId, deviceId, operationId);
    return { file: toFileDto(result.file), event: toEventDto(result.event) };
  }

  async purgeTrash(
    userId: string,
    trashId: string,
    deviceId: string,
  ): Promise<{ purged: boolean; trashId: string }> {
    const item = await this.deps.sync.findTrashItemById(trashId);
    if (!item) {
      throw trashNotFound();
    }
    await this.requireSpaceDevice(userId, item.spaceId, deviceId);
    // 先清理对象存储，再删除回收站记录；对象已不存在时视为成功。
    try {
      await this.deps.objectStore.deleteObject(item.objectId);
    } catch {
      // 对象可能已被清理，继续删除记录。
    }
    await this.deps.sync.purgeTrashItem(trashId);
    return { purged: true, trashId };
  }

  private async requireSpaceDevice(
    userId: string,
    spaceId: string,
    deviceId: string,
  ): Promise<void> {
    const space = await this.deps.spaces.findById(spaceId);
    if (!space) {
      throw spaceNotFound();
    }
    const device = await this.deps.devices.findById(deviceId);
    if (!device || device.userId !== userId || device.credentialStatus !== 'active') {
      throw invalidDevice();
    }
    if (!(await this.deps.spaces.findMember(spaceId, deviceId))) {
      throw notMember();
    }
  }
}

function toVersionDto(version: FileVersionRecord): {
  fileId: string;
  version: number;
  objectId: string;
  size: string;
  sha256: string;
  createdAt: string;
  createdByDeviceId: string;
} {
  return {
    fileId: version.fileId,
    version: version.version,
    objectId: version.objectId,
    size: version.size.toString(),
    sha256: version.sha256,
    createdAt: version.createdAt.toISOString(),
    createdByDeviceId: version.createdByDeviceId,
  };
}

function toTrashDto(item: TrashItemRecord): TrashItemDto {
  return {
    trashId: item.id,
    spaceId: item.spaceId,
    fileId: item.fileId,
    relativePath: item.relativePath,
    fileName: item.fileName,
    size: item.size.toString(),
    sha256: item.sha256,
    version: item.version,
    deletedByDeviceId: item.deletedByDeviceId,
    deletedAt: item.deletedAt.toISOString(),
    purgeAfter: item.purgeAfter.toISOString(),
  };
}

function toConflictDto(conflict: ConflictRecord): ConflictDto {
  return {
    conflictId: conflict.id,
    spaceId: conflict.spaceId,
    fileId: conflict.fileId,
    relativePath: conflict.relativePath,
    fileName: conflict.fileName,
    serverVersion: conflict.serverVersion,
    submitted: {
      objectId: conflict.submittedVersion.objectId,
      size: conflict.submittedVersion.size,
      sha256: conflict.submittedVersion.sha256,
      relativePath: conflict.submittedVersion.relativePath,
      fileName: conflict.submittedVersion.fileName,
      uploadId: conflict.submittedVersion.uploadId,
      deviceId: conflict.submittedVersion.deviceId,
    },
    status: conflict.status,
    resolvedAt: conflict.resolvedAt ? conflict.resolvedAt.toISOString() : null,
    createdAt: conflict.createdAt.toISOString(),
  };
}
