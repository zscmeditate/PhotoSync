/**
 * 仓储层接口（ports）。
 * 生产实现见 prisma.repositories.ts（全部走 Prisma）；
 * 单元测试注入内存假实现作为测试替身。
 */

export type UserStatusValue = 'active' | 'disabled';
export type DevicePlatformValue = 'android' | 'ios' | 'windows' | 'macos' | 'linux' | 'unknown';
export type CredentialStatusValue = 'active' | 'revoked';

export interface UserRecord {
  id: string;
  email: string;
  passwordHash: string;
  displayName: string;
  status: UserStatusValue;
  createdAt: Date;
  updatedAt: Date;
}

export interface DeviceRecord {
  id: string;
  userId: string;
  deviceName: string;
  platform: DevicePlatformValue;
  credentialStatus: CredentialStatusValue;
  lastSeenAt: Date | null;
  /** 被移除设备发起重新加入申请的时间；null 表示没有待处理申请 */
  rejoinRequestedAt: Date | null;
  /** 主动退出登录时间；非空表示已退出登录，不出现在设备列表 */
  loggedOutAt: Date | null;
  createdAt: Date;
}

export interface RefreshTokenRecord {
  id: string;
  userId: string;
  deviceId: string | null;
  tokenHash: string;
  expiresAt: Date;
  revokedAt: Date | null;
  createdAt: Date;
}

export interface UserRepository {
  findByEmail(email: string): Promise<UserRecord | null>;
  findById(id: string): Promise<UserRecord | null>;
  create(data: {
    email: string;
    passwordHash: string;
    displayName: string;
  }): Promise<UserRecord>;
}

export interface DeviceRepository {
  findById(id: string): Promise<DeviceRecord | null>;
  listByUser(userId: string): Promise<DeviceRecord[]>;
  create(data: {
    userId: string;
    deviceName: string;
    platform: DevicePlatformValue;
    credentialStatus: CredentialStatusValue;
    lastSeenAt: Date;
  }): Promise<DeviceRecord>;
  update(
    id: string,
    data: {
      deviceName?: string;
      credentialStatus?: CredentialStatusValue;
      lastSeenAt?: Date;
      rejoinRequestedAt?: Date | null;
      loggedOutAt?: Date | null;
    },
  ): Promise<DeviceRecord>;

  /** 当前账号的主设备 ID；没有主设备时返回 null */
  findPrimaryDeviceId(userId: string): Promise<string | null>;
  /** 抢占主设备；已有其它主设备时返回 false（幂等：自己已是主设备返回 true） */
  claimPrimaryDevice(userId: string, deviceId: string): Promise<boolean>;
  /** 放弃主设备身份；仅当该设备当前是主设备时返回 true */
  clearPrimaryDevice(userId: string, deviceId: string): Promise<boolean>;
}

export interface RefreshTokenRepository {
  findByHash(tokenHash: string): Promise<RefreshTokenRecord | null>;
  create(data: {
    userId: string;
    deviceId: string | null;
    tokenHash: string;
    expiresAt: Date;
  }): Promise<RefreshTokenRecord>;
  revoke(id: string, revokedAt: Date): Promise<void>;
  /** 吊销某设备下的全部未吊销刷新令牌 */
  revokeByDevice(deviceId: string, revokedAt: Date): Promise<void>;
}

export type MemberRoleValue = 'owner' | 'admin' | 'member' | 'viewer';

export interface SyncSpaceRecord {
  id: string;
  ownerId: string;
  name: string;
  description: string | null;
  revision: bigint;
  createdAt: Date;
  updatedAt: Date;
}

export interface SpaceWithCountsRecord extends SyncSpaceRecord {
  memberCount: number;
  mappingCount: number;
}

export interface SpaceMemberRecord {
  id: string;
  spaceId: string;
  deviceId: string;
  role: MemberRoleValue;
  joinedAt: Date;
}

export interface SpaceMemberWithDeviceRecord extends SpaceMemberRecord {
  device: DeviceRecord;
}

export type JoinRequestStatusValue = 'pending' | 'approved' | 'rejected';

export interface SpaceJoinRequestRecord {
  id: string;
  spaceId: string;
  deviceId: string;
  status: JoinRequestStatusValue;
  createdAt: Date;
  resolvedAt: Date | null;
}

export interface SpaceJoinRequestWithDeviceRecord extends SpaceJoinRequestRecord {
  device: DeviceRecord;
}

export interface FolderMappingRecord {
  id: string;
  spaceId: string;
  deviceId: string;
  localPathLabel: string;
  filters: unknown;
  createdAt: Date;
}

export interface SyncFileRecord {
  id: string;
  relativePath: string;
  size: bigint;
  sha256: string;
  version: number;
}

export interface SpaceUpdateData {
  name?: string;
  description?: string | null;
}

export interface MappingCreateData {
  spaceId: string;
  deviceId: string;
  localPathLabel: string;
  filters: { onlyMedia: boolean };
}

export interface MappingUpdateData {
  localPathLabel?: string;
  filters?: { onlyMedia: boolean };
}

export interface SpaceRepository {
  /** 同一事务创建空间、owner 成员和初始同步游标。 */
  createWithOwner(data: {
    ownerId: string;
    name: string;
    description: string | null;
    deviceId: string;
  }): Promise<SyncSpaceRecord>;
  findById(spaceId: string): Promise<SpaceWithCountsRecord | null>;
  listAccessible(userId: string, deviceIds: string[]): Promise<SpaceWithCountsRecord[]>;
  update(spaceId: string, data: SpaceUpdateData): Promise<SpaceWithCountsRecord>;
  delete(spaceId: string): Promise<void>;

  listMembershipsForDevices(spaceId: string, deviceIds: string[]): Promise<SpaceMemberRecord[]>;
  listMembers(spaceId: string): Promise<SpaceMemberWithDeviceRecord[]>;
  findMember(spaceId: string, deviceId: string): Promise<SpaceMemberWithDeviceRecord | null>;
  /** 幂等加入空间，并确保对应 SyncCursor 存在。 */
  addMember(spaceId: string, deviceId: string): Promise<SpaceMemberWithDeviceRecord>;
  removeMember(spaceId: string, deviceId: string): Promise<void>;
  /** 登出/移除时清除设备的全部空间绑定：成员关系、目录映射、同步游标。 */
  clearDeviceBindings(deviceId: string): Promise<void>;

  // ===== 加入空间申请 =====
  findPendingJoinRequest(
    spaceId: string,
    deviceId: string,
  ): Promise<SpaceJoinRequestWithDeviceRecord | null>;
  createJoinRequest(data: {
    spaceId: string;
    deviceId: string;
    status?: JoinRequestStatusValue;
    resolvedAt?: Date | null;
  }): Promise<SpaceJoinRequestWithDeviceRecord>;
  listPendingJoinRequests(spaceId: string): Promise<SpaceJoinRequestWithDeviceRecord[]>;
  findJoinRequestById(requestId: string): Promise<SpaceJoinRequestWithDeviceRecord | null>;
  updateJoinRequest(
    requestId: string,
    data: { status: JoinRequestStatusValue; resolvedAt: Date | null },
  ): Promise<SpaceJoinRequestWithDeviceRecord>;

  listMappings(spaceId: string, deviceId?: string): Promise<FolderMappingRecord[]>;
  findMappingById(mappingId: string): Promise<FolderMappingRecord | null>;
  createMapping(data: MappingCreateData): Promise<FolderMappingRecord>;
  updateMapping(mappingId: string, data: MappingUpdateData): Promise<FolderMappingRecord>;
  deleteMapping(mappingId: string): Promise<void>;

  listActiveFiles(spaceId: string): Promise<SyncFileRecord[]>;
}

export type UploadSessionStatusValue = 'pending' | 'uploading' | 'completed' | 'aborted';

export interface UploadedPartRecord {
  partNumber: number;
  etag: string;
  size: bigint;
  sha256: string;
}

export interface UploadSessionRecord {
  id: string;
  fileId: string | null;
  deviceId: string;
  spaceId: string;
  relativePath: string;
  sha256: string;
  idempotencyKey: string;
  providerUploadId: string;
  objectId: string;
  totalSize: bigint;
  partSize: bigint;
  status: UploadSessionStatusValue;
  parts: UploadedPartRecord[];
  createdAt: Date;
  updatedAt: Date;
}

export interface UploadSessionCreateData {
  deviceId: string;
  spaceId: string;
  relativePath: string;
  sha256: string;
  idempotencyKey: string;
  providerUploadId: string;
  objectId: string;
  totalSize: bigint;
  partSize: bigint;
}

export interface TransferRepository {
  findUploadById(uploadId: string): Promise<UploadSessionRecord | null>;
  findUploadByIdempotency(deviceId: string, idempotencyKey: string): Promise<UploadSessionRecord | null>;
  createUpload(data: UploadSessionCreateData): Promise<{ session: UploadSessionRecord; created: boolean }>;
  saveUploadedPart(uploadId: string, part: UploadedPartRecord): Promise<UploadedPartRecord>;
  markUploadStatus(
    uploadId: string,
    status: UploadSessionStatusValue,
  ): Promise<UploadSessionRecord>;
}

export interface FileCreatedPayload {
  fileId: string;
  relativePath: string;
  fileName: string;
  objectId: string;
  size: string;
  sha256: string;
  version: number;
  lastModifiedByDeviceId: string;
}

export interface FileUpdatedPayload {
  fileId: string;
  relativePath: string;
  fileName: string;
  objectId: string;
  size: string;
  sha256: string;
  version: number;
  baseVersion: number;
  lastModifiedByDeviceId: string;
}

export interface FileDeletedPayload {
  fileId: string;
  relativePath: string;
  fileName: string;
  version: number;
  baseVersion: number;
  lastModifiedByDeviceId: string;
}

export interface FileRenamedPayload {
  fileId: string;
  oldPath: string;
  newPath: string;
  oldFileName: string;
  newFileName: string;
  version: number;
  baseVersion: number;
  lastModifiedByDeviceId: string;
}

export interface FileRestoredPayload {
  fileId: string;
  relativePath: string;
  fileName: string;
  objectId: string;
  size: string;
  sha256: string;
  version: number;
  lastModifiedByDeviceId: string;
}

export interface ConflictDetectedPayload {
  conflictId: string;
  fileId: string;
  relativePath: string;
  serverVersion: number;
  baseVersion: number;
  submittedSha256: string;
  submittedSize: string;
  submittedByDeviceId: string;
}

export type ConflictResolution = 'server' | 'client' | 'both';

export interface ConflictResolvedPayload {
  conflictId: string;
  fileId: string;
  resolution: ConflictResolution;
  version: number;
  /** resolution 为 both 时，客户端提交版本被保留到的冲突副本路径。 */
  conflictCopyPath?: string;
}

export type SyncEventPayload =
  | FileCreatedPayload
  | FileUpdatedPayload
  | FileDeletedPayload
  | FileRenamedPayload
  | FileRestoredPayload
  | ConflictDetectedPayload
  | ConflictResolvedPayload;

export interface CommittedFileRecord extends SyncFileRecord {
  spaceId: string;
  fileName: string;
  objectId: string;
  isDeleted: boolean;
  createdAt: Date;
  updatedAt: Date;
  lastModifiedByDeviceId: string;
}

export interface SyncEventRecord {
  id: string;
  spaceId: string;
  revision: bigint;
  type: string;
  deviceId: string;
  operationId: string | null;
  payload: SyncEventPayload;
  createdAt: Date;
}

export interface FileVersionRecord {
  id: string;
  fileId: string;
  version: number;
  objectId: string;
  size: bigint;
  sha256: string;
  createdAt: Date;
  createdByDeviceId: string;
}

/** 回收站条目（Tombstone）：保留删除文件的对象引用与保留期。 */
export interface TrashItemRecord {
  id: string;
  spaceId: string;
  fileId: string;
  relativePath: string;
  fileName: string;
  objectId: string;
  size: bigint;
  sha256: string;
  version: number;
  deletedByDeviceId: string;
  deletedAt: Date;
  purgeAfter: Date;
}

/** 冲突中被拒绝的设备提交快照。 */
export interface ConflictSubmittedVersion {
  objectId: string;
  size: string;
  sha256: string;
  relativePath: string;
  fileName: string;
  uploadId: string | null;
  deviceId: string;
}

export type ConflictStatusValue = 'pending' | 'resolved';

/** 回收站保留天数：Tombstone 保留期，不得短于设备离线同步保护窗口。 */
export const TRASH_RETENTION_DAYS = 30;

export interface ConflictRecord {
  id: string;
  spaceId: string;
  fileId: string;
  relativePath: string;
  fileName: string;
  serverVersion: number;
  submittedVersion: ConflictSubmittedVersion;
  status: ConflictStatusValue;
  resolvedAt: Date | null;
  createdAt: Date;
}

export interface RecordConflictData {
  spaceId: string;
  fileId: string;
  serverVersion: number;
  submitted: ConflictSubmittedVersion;
}

export interface ResolveConflictData {
  conflictId: string;
  resolution: ConflictResolution;
  deviceId: string;
  operationId: string;
}

export interface ResolveConflictResult {
  conflict: ConflictRecord;
  /** resolution 为 server 时为 null（服务器文件未变化）。 */
  file: CommittedFileRecord | null;
  event: SyncEventRecord;
}

export interface RestoreTrashResult {
  file: CommittedFileRecord;
  event: SyncEventRecord;
}

export interface CommitFileCreatedData {
  spaceId: string;
  deviceId: string;
  operationId: string;
  uploadId: string;
  relativePath: string;
  objectId: string;
  size: bigint;
  sha256: string;
}

export interface CommitFileCreatedResult {
  file: CommittedFileRecord;
  event: SyncEventRecord;
  duplicate: boolean;
}

export interface CommitFileUpdatedData {
  spaceId: string;
  deviceId: string;
  operationId: string;
  uploadId: string;
  fileId: string;
  baseVersion: number;
  relativePath: string;
  objectId: string;
  size: bigint;
  sha256: string;
}

export interface CommitFileDeletedData {
  spaceId: string;
  deviceId: string;
  operationId: string;
  fileId: string;
  baseVersion: number;
}

export interface CommitFileRenamedData {
  spaceId: string;
  deviceId: string;
  operationId: string;
  fileId: string;
  baseVersion: number;
  newRelativePath: string;
}

/** 修改/删除/重命名提交的统一返回结构。 */
export interface CommitMutationResult {
  file: CommittedFileRecord;
  event: SyncEventRecord;
  duplicate: boolean;
}

export interface DownloadObjectRecord {
  objectId: string;
  spaceId: string;
  fileName: string;
  size: bigint;
  sha256: string;
}

export interface SyncStatusRecord {
  serverRevision: bigint;
  lastAppliedRevision: bigint;
  pendingEvents: number;
  activeFileCount: number;
}

export interface SyncRepository {
  commitFileCreated(data: CommitFileCreatedData): Promise<CommitFileCreatedResult>;
  commitFileUpdated(data: CommitFileUpdatedData): Promise<CommitMutationResult>;
  commitFileDeleted(data: CommitFileDeletedData): Promise<CommitMutationResult>;
  commitFileRenamed(data: CommitFileRenamedData): Promise<CommitMutationResult>;
  getSpaceRevision(spaceId: string): Promise<bigint | null>;
  listEvents(
    spaceId: string,
    sinceRevision: bigint,
    maxRevision: bigint,
    limit: number,
  ): Promise<SyncEventRecord[]>;
  acknowledge(
    spaceId: string,
    deviceId: string,
    lastAppliedRevision: bigint,
  ): Promise<{ serverRevision: bigint; lastAppliedRevision: bigint }>;
  getStatus(spaceId: string, deviceId: string): Promise<SyncStatusRecord | null>;
  findObject(objectId: string): Promise<DownloadObjectRecord | null>;
  findFileById(fileId: string): Promise<CommittedFileRecord | null>;
  /** 查找空间内指定路径的活动（未删除）文件。 */
  findFileByPath(spaceId: string, relativePath: string): Promise<CommittedFileRecord | null>;
  listFileVersions(fileId: string): Promise<FileVersionRecord[]>;

  // ===== Phase 5：冲突与回收站 =====
  /** 记录一次 baseVersion 冲突；同一文件、同一提交哈希的待处理冲突复用已有记录。 */
  recordConflict(data: RecordConflictData): Promise<ConflictRecord>;
  listConflicts(spaceId: string): Promise<ConflictRecord[]>;
  findConflictById(conflictId: string): Promise<ConflictRecord | null>;
  /** 解决冲突：server 仅标记；client 用提交版本生成新版本；both 另建冲突副本文件。 */
  resolveConflict(data: ResolveConflictData): Promise<ResolveConflictResult>;

  listTrash(spaceId: string): Promise<TrashItemRecord[]>;
  findTrashItemById(trashId: string): Promise<TrashItemRecord | null>;
  /** 恢复回收站文件：清除软删除标记、删除回收站记录并生成 file_restored 事件。 */
  restoreTrashItem(trashId: string, deviceId: string, operationId: string): Promise<RestoreTrashResult>;
  /** 删除回收站记录（对象清理由 service 负责）。返回被删除记录以便清理对象。 */
  purgeTrashItem(trashId: string): Promise<TrashItemRecord | null>;

  /** 统计某设备在指定时间点之后提交的文件删除事件数（大量删除保护）。 */
  countRecentDeletions(spaceId: string, deviceId: string, since: Date): Promise<number>;
}
