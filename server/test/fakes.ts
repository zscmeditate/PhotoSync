import { createHash, randomUUID } from 'node:crypto';
import { Readable } from 'node:stream';
import {
  conflictAlreadyResolved,
  conflictNotFound,
  fileAlreadyExists,
  fileIsDeleted,
  fileNotFound,
  idempotencyConflict,
  pathConflict,
  partConflict,
  trashNotFound,
  validationError,
  versionConflict,
} from '../src/lib/errors';
import {
  RangeNotSatisfiableError,
  StorageObjectMissingError,
  type ObjectContent,
  type ObjectStore,
} from '../src/lib/object-store';
import type {
  DeviceRecord,
  DeviceRepository,
  FolderMappingRecord,
  MappingCreateData,
  MappingUpdateData,
  RefreshTokenRecord,
  RefreshTokenRepository,
  JoinRequestStatusValue,
  SpaceJoinRequestRecord,
  SpaceJoinRequestWithDeviceRecord,
  SpaceMemberRecord,
  SpaceMemberWithDeviceRecord,
  SpaceRepository,
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
} from '../src/repositories/ports';
import { TRASH_RETENTION_DAYS } from '../src/repositories/ports';

/**
 * 测试替身：内存实现的仓储（仅供单元测试使用，生产代码全部走 Prisma）。
 */

export class FakeUserRepository implements UserRepository {
  readonly items = new Map<string, UserRecord>();
  private readonly byEmail = new Map<string, string>();

  async findByEmail(email: string): Promise<UserRecord | null> {
    const id = this.byEmail.get(email);
    return id ? (this.items.get(id) ?? null) : null;
  }

  async findById(id: string): Promise<UserRecord | null> {
    return this.items.get(id) ?? null;
  }

  async create(data: {
    email: string;
    passwordHash: string;
    displayName: string;
  }): Promise<UserRecord> {
    const record: UserRecord = {
      id: randomUUID(),
      email: data.email,
      passwordHash: data.passwordHash,
      displayName: data.displayName,
      status: 'active',
      createdAt: new Date(),
      updatedAt: new Date(),
    };
    this.items.set(record.id, record);
    this.byEmail.set(record.email, record.id);
    return record;
  }
}

export class FakeDeviceRepository implements DeviceRepository {
  readonly items = new Map<string, DeviceRecord>();
  private readonly primaryByUser = new Map<string, string>();

  async findById(id: string): Promise<DeviceRecord | null> {
    return this.items.get(id) ?? null;
  }

  async findPrimaryDeviceId(userId: string): Promise<string | null> {
    return this.primaryByUser.get(userId) ?? null;
  }

  async claimPrimaryDevice(userId: string, deviceId: string): Promise<boolean> {
    const current = this.primaryByUser.get(userId);
    if (current !== undefined && current !== deviceId) {
      return false;
    }
    this.primaryByUser.set(userId, deviceId);
    return true;
  }

  async replacePrimaryDevice(
    userId: string,
    fromDeviceId: string,
    toDeviceId: string,
  ): Promise<boolean> {
    if (this.primaryByUser.get(userId) !== fromDeviceId) {
      return false;
    }
    this.primaryByUser.set(userId, toDeviceId);
    return true;
  }

  async clearPrimaryDevice(userId: string, deviceId: string): Promise<boolean> {
    if (this.primaryByUser.get(userId) !== deviceId) {
      return false;
    }
    this.primaryByUser.delete(userId);
    return true;
  }

  async listByUser(userId: string): Promise<DeviceRecord[]> {
    return [...this.items.values()]
      // 已退出登录的设备不出现在列表中
      .filter((device) => device.userId === userId && device.loggedOutAt === null)
      .sort((a, b) => a.createdAt.getTime() - b.createdAt.getTime());
  }

  async create(data: {
    userId: string;
    deviceName: string;
    platform: DeviceRecord['platform'];
    credentialStatus: DeviceRecord['credentialStatus'];
    lastSeenAt: Date;
  }): Promise<DeviceRecord> {
    const record: DeviceRecord = {
      id: randomUUID(),
      userId: data.userId,
      deviceName: data.deviceName,
      platform: data.platform,
      credentialStatus: data.credentialStatus,
      lastSeenAt: data.lastSeenAt,
      rejoinRequestedAt: null,
      loggedOutAt: null,
      createdAt: new Date(),
    };
    this.items.set(record.id, record);
    return record;
  }

  async update(
    id: string,
    data: {
      deviceName?: string;
      credentialStatus?: DeviceRecord['credentialStatus'];
      lastSeenAt?: Date;
      rejoinRequestedAt?: Date | null;
      loggedOutAt?: Date | null;
    },
  ): Promise<DeviceRecord> {
    const existing = this.items.get(id);
    if (!existing) {
      throw new Error(`device ${id} not found`);
    }
    const updated: DeviceRecord = { ...existing, ...data };
    this.items.set(id, updated);
    return updated;
  }
}

export class FakeRefreshTokenRepository implements RefreshTokenRepository {
  readonly items = new Map<string, RefreshTokenRecord>();
  private readonly byHash = new Map<string, string>();

  async findByHash(tokenHash: string): Promise<RefreshTokenRecord | null> {
    const id = this.byHash.get(tokenHash);
    return id ? (this.items.get(id) ?? null) : null;
  }

  async create(data: {
    userId: string;
    deviceId: string | null;
    tokenHash: string;
    expiresAt: Date;
  }): Promise<RefreshTokenRecord> {
    const record: RefreshTokenRecord = {
      id: randomUUID(),
      userId: data.userId,
      deviceId: data.deviceId,
      tokenHash: data.tokenHash,
      expiresAt: data.expiresAt,
      revokedAt: null,
      createdAt: new Date(),
    };
    this.items.set(record.id, record);
    this.byHash.set(record.tokenHash, record.id);
    return record;
  }

  async revoke(id: string, revokedAt: Date): Promise<void> {
    const record = this.items.get(id);
    if (record) {
      this.items.set(id, { ...record, revokedAt });
    }
  }

  async revokeByDevice(deviceId: string, revokedAt: Date): Promise<void> {
    for (const [id, record] of this.items) {
      if (record.deviceId === deviceId && record.revokedAt === null) {
        this.items.set(id, { ...record, revokedAt });
      }
    }
  }
}

export type FakeSyncFileRecord = CommittedFileRecord;

export class FakeSpaceRepository implements SpaceRepository {
  readonly spaces = new Map<string, SyncSpaceRecord>();
  readonly members = new Map<string, SpaceMemberRecord>();
  readonly cursors = new Map<
    string,
    { spaceId: string; deviceId: string; lastAppliedRevision: bigint }
  >();
  readonly mappings = new Map<string, FolderMappingRecord>();
  readonly files = new Map<string, FakeSyncFileRecord>();
  readonly joinRequests = new Map<string, SpaceJoinRequestRecord>();

  constructor(private readonly devices: FakeDeviceRepository) {}

  private key(spaceId: string, deviceId: string): string {
    return `${spaceId}:${deviceId}`;
  }

  private withCounts(space: SyncSpaceRecord): SpaceWithCountsRecord {
    return {
      ...space,
      memberCount: [...this.members.values()].filter((member) => member.spaceId === space.id)
        .length,
      mappingCount: [...this.mappings.values()].filter((mapping) => mapping.spaceId === space.id)
        .length,
    };
  }

  private withDevice(member: SpaceMemberRecord): SpaceMemberWithDeviceRecord {
    const device = this.devices.items.get(member.deviceId);
    if (!device) {
      throw new Error(`device ${member.deviceId} not found`);
    }
    return { ...member, device };
  }

  async createWithOwner(data: {
    ownerId: string;
    name: string;
    description: string | null;
    deviceId: string;
  }): Promise<SyncSpaceRecord> {
    const now = new Date();
    const space: SyncSpaceRecord = {
      id: randomUUID(),
      ownerId: data.ownerId,
      name: data.name,
      description: data.description,
      coverUpdatedAt: null,
      revision: 0n,
      createdAt: now,
      updatedAt: now,
    };
    this.spaces.set(space.id, space);
    this.members.set(this.key(space.id, data.deviceId), {
      id: randomUUID(),
      spaceId: space.id,
      deviceId: data.deviceId,
      role: 'member',
      joinedAt: now,
    });
    this.cursors.set(this.key(space.id, data.deviceId), {
      spaceId: space.id,
      deviceId: data.deviceId,
      lastAppliedRevision: 0n,
    });
    return space;
  }

  async findById(spaceId: string): Promise<SpaceWithCountsRecord | null> {
    const space = this.spaces.get(spaceId);
    return space ? this.withCounts(space) : null;
  }

  async listAccessible(userId: string, deviceIds: string[]): Promise<SpaceWithCountsRecord[]> {
    const ids = new Set(deviceIds);
    return [...this.spaces.values()]
      .filter(
        (space) =>
          space.ownerId === userId ||
          [...this.members.values()].some(
            (member) => member.spaceId === space.id && ids.has(member.deviceId),
          ),
      )
      .sort((a, b) => b.createdAt.getTime() - a.createdAt.getTime())
      .map((space) => this.withCounts(space));
  }

  private readonly covers = new Map<
    string,
    { image: Buffer; contentType: string; updatedAt: Date }
  >();

  async update(spaceId: string, data: SpaceUpdateData): Promise<SpaceWithCountsRecord> {
    const space = this.spaces.get(spaceId);
    if (!space) {
      throw new Error(`space ${spaceId} not found`);
    }
    const updated = { ...space, ...data, updatedAt: new Date() };
    this.spaces.set(spaceId, updated);
    return this.withCounts(updated);
  }

  async setCover(
    spaceId: string,
    data: { image: Buffer; contentType: string },
  ): Promise<SpaceWithCountsRecord> {
    const space = this.spaces.get(spaceId);
    if (!space) {
      throw new Error(`space ${spaceId} not found`);
    }
    const updatedAt = new Date();
    this.covers.set(spaceId, { ...data, updatedAt });
    const updated = { ...space, coverUpdatedAt: updatedAt };
    this.spaces.set(spaceId, updated);
    return this.withCounts(updated);
  }

  async clearCover(spaceId: string): Promise<SpaceWithCountsRecord> {
    const space = this.spaces.get(spaceId);
    if (!space) {
      throw new Error(`space ${spaceId} not found`);
    }
    this.covers.delete(spaceId);
    const updated = { ...space, coverUpdatedAt: null };
    this.spaces.set(spaceId, updated);
    return this.withCounts(updated);
  }

  async findCover(
    spaceId: string,
  ): Promise<{ image: Buffer; contentType: string; updatedAt: Date } | null> {
    return this.covers.get(spaceId) ?? null;
  }

  async delete(spaceId: string): Promise<void> {
    this.spaces.delete(spaceId);
    for (const [key, member] of this.members) {
      if (member.spaceId === spaceId) this.members.delete(key);
    }
    for (const [key, mapping] of this.mappings) {
      if (mapping.spaceId === spaceId) this.mappings.delete(key);
    }
    for (const [key, request] of this.joinRequests) {
      if (request.spaceId === spaceId) this.joinRequests.delete(key);
    }
  }

  async listObjectIds(spaceId: string): Promise<string[]> {
    return [
      ...new Set(
        [...this.files.values()]
          .filter((file) => file.spaceId === spaceId)
          .map((file) => file.objectId),
      ),
    ];
  }

  async listMembershipsForDevices(
    spaceId: string,
    deviceIds: string[],
  ): Promise<SpaceMemberRecord[]> {
    const ids = new Set(deviceIds);
    return [...this.members.values()].filter(
      (member) => member.spaceId === spaceId && ids.has(member.deviceId),
    );
  }

  async listMembers(spaceId: string): Promise<SpaceMemberWithDeviceRecord[]> {
    return [...this.members.values()]
      .filter((member) => member.spaceId === spaceId)
      .sort((a, b) => a.joinedAt.getTime() - b.joinedAt.getTime())
      .map((member) => this.withDevice(member));
  }

  async findMember(
    spaceId: string,
    deviceId: string,
  ): Promise<SpaceMemberWithDeviceRecord | null> {
    const member = this.members.get(this.key(spaceId, deviceId));
    return member ? this.withDevice(member) : null;
  }

  async addMember(spaceId: string, deviceId: string): Promise<SpaceMemberWithDeviceRecord> {
    const key = this.key(spaceId, deviceId);
    let member = this.members.get(key);
    if (!member) {
      member = {
        id: randomUUID(),
        spaceId,
        deviceId,
        role: 'member',
        joinedAt: new Date(),
      };
      this.members.set(key, member);
    }
    if (!this.cursors.has(key)) {
      this.cursors.set(key, { spaceId, deviceId, lastAppliedRevision: 0n });
    }
    return this.withDevice(member);
  }

  async removeMember(spaceId: string, deviceId: string): Promise<void> {
    const key = this.key(spaceId, deviceId);
    this.members.delete(key);
    this.cursors.delete(key);
    for (const [id, mapping] of this.mappings) {
      if (mapping.spaceId === spaceId && mapping.deviceId === deviceId) {
        this.mappings.delete(id);
      }
    }
  }

  async clearDeviceBindings(deviceId: string): Promise<void> {
    for (const [key, member] of this.members) {
      if (member.deviceId === deviceId) {
        this.members.delete(key);
      }
    }
    for (const [key, cursor] of this.cursors) {
      if (cursor.deviceId === deviceId) {
        this.cursors.delete(key);
      }
    }
    for (const [key, mapping] of this.mappings) {
      if (mapping.deviceId === deviceId) {
        this.mappings.delete(key);
      }
    }
    for (const [key, request] of this.joinRequests) {
      if (request.deviceId === deviceId) {
        this.joinRequests.delete(key);
      }
    }
  }

  private withJoinDevice(request: SpaceJoinRequestRecord): SpaceJoinRequestWithDeviceRecord {
    const device = this.devices.items.get(request.deviceId);
    if (!device) {
      throw new Error(`device ${request.deviceId} not found`);
    }
    return { ...request, device };
  }

  async findPendingJoinRequest(
    spaceId: string,
    deviceId: string,
  ): Promise<SpaceJoinRequestWithDeviceRecord | null> {
    const request = [...this.joinRequests.values()].find(
      (item) =>
        item.spaceId === spaceId && item.deviceId === deviceId && item.status === 'pending',
    );
    return request ? this.withJoinDevice(request) : null;
  }

  async findLatestJoinRequest(
    spaceId: string,
    deviceId: string,
  ): Promise<SpaceJoinRequestWithDeviceRecord | null> {
    const request = [...this.joinRequests.values()]
      .filter((item) => item.spaceId === spaceId && item.deviceId === deviceId)
      .sort((a, b) => a.createdAt.getTime() - b.createdAt.getTime())
      .at(-1);
    return request ? this.withJoinDevice(request) : null;
  }

  async createJoinRequest(data: {
    spaceId: string;
    deviceId: string;
    status?: JoinRequestStatusValue;
    resolvedAt?: Date | null;
  }): Promise<SpaceJoinRequestWithDeviceRecord> {
    const request: SpaceJoinRequestRecord = {
      id: randomUUID(),
      spaceId: data.spaceId,
      deviceId: data.deviceId,
      status: data.status ?? 'pending',
      createdAt: new Date(),
      resolvedAt: data.resolvedAt ?? null,
    };
    this.joinRequests.set(request.id, request);
    return this.withJoinDevice(request);
  }

  async listPendingJoinRequests(spaceId: string): Promise<SpaceJoinRequestWithDeviceRecord[]> {
    return [...this.joinRequests.values()]
      .filter((request) => request.spaceId === spaceId && request.status === 'pending')
      .sort((a, b) => a.createdAt.getTime() - b.createdAt.getTime())
      .map((request) => this.withJoinDevice(request));
  }

  async findJoinRequestById(
    requestId: string,
  ): Promise<SpaceJoinRequestWithDeviceRecord | null> {
    const request = this.joinRequests.get(requestId);
    return request ? this.withJoinDevice(request) : null;
  }

  async updateJoinRequest(
    requestId: string,
    data: { status: JoinRequestStatusValue; resolvedAt: Date | null },
  ): Promise<SpaceJoinRequestWithDeviceRecord> {
    const request = this.joinRequests.get(requestId);
    if (!request) {
      throw new Error(`join request ${requestId} not found`);
    }
    const updated: SpaceJoinRequestRecord = { ...request, ...data };
    this.joinRequests.set(requestId, updated);
    return this.withJoinDevice(updated);
  }

  async listMappings(spaceId: string, deviceId?: string): Promise<FolderMappingRecord[]> {
    return [...this.mappings.values()]
      .filter(
        (mapping) =>
          mapping.spaceId === spaceId && (deviceId === undefined || mapping.deviceId === deviceId),
      )
      .sort((a, b) => a.createdAt.getTime() - b.createdAt.getTime());
  }

  async findMappingById(mappingId: string): Promise<FolderMappingRecord | null> {
    return this.mappings.get(mappingId) ?? null;
  }

  async createMapping(data: MappingCreateData): Promise<FolderMappingRecord> {
    const mapping: FolderMappingRecord = {
      id: randomUUID(),
      ...data,
      treeUri: data.treeUri ?? null,
      createdAt: new Date(),
    };
    this.mappings.set(mapping.id, mapping);
    return mapping;
  }

  async listMappingsByDevice(deviceId: string): Promise<FolderMappingRecord[]> {
    return [...this.mappings.values()]
      .filter((mapping) => mapping.deviceId === deviceId)
      .sort((a, b) => a.createdAt.getTime() - b.createdAt.getTime());
  }

  async updateMapping(
    mappingId: string,
    data: MappingUpdateData,
  ): Promise<FolderMappingRecord> {
    const mapping = this.mappings.get(mappingId);
    if (!mapping) {
      throw new Error(`mapping ${mappingId} not found`);
    }
    const updated = { ...mapping, ...data };
    this.mappings.set(mappingId, updated);
    return updated;
  }

  async deleteMapping(mappingId: string): Promise<void> {
    this.mappings.delete(mappingId);
  }

  async listActiveFiles(spaceId: string): Promise<SyncFileRecord[]> {
    return [...this.files.values()]
      .filter((file) => file.spaceId === spaceId && !file.isDeleted)
      .sort((a, b) => a.relativePath.localeCompare(b.relativePath));
  }
}

export class FakeTransferRepository implements TransferRepository {
  readonly sessions = new Map<string, UploadSessionRecord>();

  async findUploadById(uploadId: string): Promise<UploadSessionRecord | null> {
    return this.sessions.get(uploadId) ?? null;
  }

  async findUploadByIdempotency(
    deviceId: string,
    idempotencyKey: string,
  ): Promise<UploadSessionRecord | null> {
    return [...this.sessions.values()].find(
      (session) => session.deviceId === deviceId && session.idempotencyKey === idempotencyKey,
    ) ?? null;
  }

  async createUpload(
    data: UploadSessionCreateData,
  ): Promise<{ session: UploadSessionRecord; created: boolean }> {
    const existing = await this.findUploadByIdempotency(data.deviceId, data.idempotencyKey);
    if (existing) {
      return { session: existing, created: false };
    }
    const now = new Date();
    const session: UploadSessionRecord = {
      id: randomUUID(),
      fileId: null,
      ...data,
      status: 'pending',
      parts: [],
      createdAt: now,
      updatedAt: now,
    };
    this.sessions.set(session.id, session);
    return { session, created: true };
  }

  async saveUploadedPart(
    uploadId: string,
    part: UploadedPartRecord,
  ): Promise<UploadedPartRecord> {
    const session = this.sessions.get(uploadId);
    if (!session) throw new Error('upload not found');
    const existing = session.parts.find((item) => item.partNumber === part.partNumber);
    if (existing) {
      if (existing.size === part.size && existing.sha256 === part.sha256) return existing;
      throw partConflict();
    }
    session.parts = [...session.parts, part].sort((a, b) => a.partNumber - b.partNumber);
    session.status = 'uploading';
    session.updatedAt = new Date();
    return part;
  }

  async markUploadStatus(
    uploadId: string,
    status: UploadSessionStatusValue,
  ): Promise<UploadSessionRecord> {
    const session = this.sessions.get(uploadId);
    if (!session) throw new Error('upload not found');
    session.status = status;
    session.updatedAt = new Date();
    return session;
  }
}

interface FakeMultipart {
  objectId: string;
  parts: Map<number, Buffer>;
}

export class FakeObjectStore implements ObjectStore {
  readonly objects = new Map<string, Buffer>();
  readonly multiparts = new Map<string, FakeMultipart>();
  readonly completeCalls: string[] = [];
  readonly uploadPartCalls: Array<{ objectId: string; partNumber: number }> = [];
  onAfterComplete?: (objectId: string) => void;

  async ensureBucket(): Promise<void> {}

  async createMultipartUpload(objectId: string): Promise<string> {
    const providerUploadId = randomUUID();
    this.multiparts.set(providerUploadId, { objectId, parts: new Map() });
    return providerUploadId;
  }

  async abortMultipartUpload(objectId: string, providerUploadId: string): Promise<void> {
    this.multiparts.delete(providerUploadId);
  }

  async uploadPart(input: {
    objectId: string;
    providerUploadId: string;
    partNumber: number;
    body: Buffer;
  }): Promise<string> {
    const multipart = this.multiparts.get(input.providerUploadId);
    if (!multipart || multipart.objectId !== input.objectId) {
      const error = new Error('NoSuchUpload');
      error.name = 'NoSuchUpload';
      throw error;
    }
    multipart.parts.set(input.partNumber, Buffer.from(input.body));
    this.uploadPartCalls.push({ objectId: input.objectId, partNumber: input.partNumber });
    return `"${createHash('md5').update(input.body).digest('hex')}"`;
  }

  async completeMultipartUpload(
    objectId: string,
    providerUploadId: string,
    parts: Array<{ partNumber: number; etag: string }>,
  ): Promise<void> {
    this.completeCalls.push(providerUploadId);
    const multipart = this.multiparts.get(providerUploadId);
    if (!multipart || multipart.objectId !== objectId) {
      const error = new Error('NoSuchUpload');
      error.name = 'NoSuchUpload';
      throw error;
    }
    this.objects.set(
      objectId,
      Buffer.concat(parts.map((part) => multipart.parts.get(part.partNumber) ?? Buffer.alloc(0))),
    );
    this.multiparts.delete(providerUploadId);
    this.onAfterComplete?.(objectId);
  }

  async putEmptyObject(objectId: string): Promise<void> {
    this.objects.set(objectId, Buffer.alloc(0));
  }

  async getObject(objectId: string, range?: string): Promise<ObjectContent> {
    const object = this.objects.get(objectId);
    if (!object) {
      throw new StorageObjectMissingError(objectId);
    }
    if (range) {
      const match = /^bytes=(\d+)-(\d*)$/.exec(range);
      if (!match) {
        throw new RangeNotSatisfiableError(objectId);
      }
      const start = Number(match[1]);
      const end = match[2] ? Number(match[2]) : object.byteLength - 1;
      if (object.byteLength > 0 && start >= object.byteLength) {
        throw new RangeNotSatisfiableError(objectId);
      }
      const slice = object.subarray(start, Math.min(end + 1, object.byteLength));
      return {
        body: Readable.from([slice]),
        contentLength: slice.byteLength,
        contentRange: `bytes ${start}-${Math.min(end, object.byteLength - 1)}/${object.byteLength}`,
      };
    }
    return { body: Readable.from([object]), contentLength: object.byteLength };
  }

  async deleteObject(objectId: string): Promise<void> {
    this.objects.delete(objectId);
  }
}

export class FakeSyncRepository implements SyncRepository {
  readonly events = new Map<string, SyncEventRecord>();
  readonly versions = new Map<string, FileVersionRecord>();
  readonly conflicts = new Map<string, ConflictRecord>();
  readonly trash = new Map<string, TrashItemRecord>();

  constructor(
    private readonly spaces: FakeSpaceRepository,
    private readonly transfers: FakeTransferRepository,
  ) {}

  async commitFileCreated(data: CommitFileCreatedData): Promise<CommitFileCreatedResult> {
    const existingEvent = [...this.events.values()].find(
      (event) =>
        event.spaceId === data.spaceId &&
        event.deviceId === data.deviceId &&
        event.operationId === data.operationId,
    );
    if (existingEvent) {
      const payload = existingEvent.payload;
      if (
        !('objectId' in payload) ||
        payload.objectId !== data.objectId ||
        payload.relativePath !== data.relativePath ||
        payload.sha256 !== data.sha256 ||
        payload.size !== data.size.toString()
      ) {
        throw idempotencyConflict();
      }
      const file = this.spaces.files.get(existingEvent.payload.fileId);
      if (!file) throw new Error('event file not found');
      return { file, event: existingEvent, duplicate: true };
    }
    if (
      [...this.spaces.files.values()].some(
        (file) =>
          file.spaceId === data.spaceId &&
          file.relativePath === data.relativePath &&
          !file.isDeleted,
      )
    ) {
      throw fileAlreadyExists();
    }
    const space = this.spaces.spaces.get(data.spaceId);
    if (!space) throw new Error('space not found');
    const now = new Date();
    const revision = space.revision + 1n;
    this.spaces.spaces.set(space.id, { ...space, revision, updatedAt: now });
    const file: CommittedFileRecord = {
      id: randomUUID(),
      spaceId: data.spaceId,
      relativePath: data.relativePath,
      fileName: data.relativePath.split('/').at(-1)!,
      objectId: data.objectId,
      size: data.size,
      sha256: data.sha256,
      version: 1,
      isDeleted: false,
      createdAt: now,
      updatedAt: now,
      lastModifiedByDeviceId: data.deviceId,
    };
    this.spaces.files.set(file.id, file);
    const version: FileVersionRecord = {
      id: randomUUID(),
      fileId: file.id,
      version: 1,
      objectId: file.objectId,
      size: file.size,
      sha256: file.sha256,
      createdAt: now,
      createdByDeviceId: data.deviceId,
    };
    this.versions.set(version.id, version);
    const payload: FileCreatedPayload = {
      fileId: file.id,
      relativePath: file.relativePath,
      fileName: file.fileName,
      objectId: file.objectId,
      size: file.size.toString(),
      sha256: file.sha256,
      version: 1,
      lastModifiedByDeviceId: data.deviceId,
    };
    const event: SyncEventRecord = {
      id: randomUUID(),
      spaceId: data.spaceId,
      revision,
      type: 'file_created',
      deviceId: data.deviceId,
      operationId: data.operationId,
      payload,
      createdAt: now,
    };
    this.events.set(event.id, event);
    const upload = this.transfers.sessions.get(data.uploadId);
    if (upload) upload.fileId = file.id;
    return { file, event, duplicate: false };
  }

  async commitFileUpdated(data: CommitFileUpdatedData): Promise<CommitMutationResult> {
    const existingEvent = [...this.events.values()].find(
      (event) =>
        event.spaceId === data.spaceId &&
        event.deviceId === data.deviceId &&
        event.operationId === data.operationId,
    );
    if (existingEvent) {
      const payload = existingEvent.payload;
      if (
        !('objectId' in payload) ||
        payload.fileId !== data.fileId ||
        payload.objectId !== data.objectId ||
        payload.sha256 !== data.sha256 ||
        payload.size !== data.size.toString()
      ) {
        throw idempotencyConflict();
      }
      const file = this.spaces.files.get(payload.fileId);
      if (!file) throw new Error('event file not found');
      return { file, event: existingEvent, duplicate: true };
    }
    const file = this.spaces.files.get(data.fileId);
    if (!file || file.spaceId !== data.spaceId) throw fileNotFound();
    if (file.isDeleted) throw fileIsDeleted();
    if (file.version !== data.baseVersion) throw versionConflict(file.version);
    if (file.relativePath !== data.relativePath) {
      throw validationError('Upload path does not match the current file path');
    }
    const space = this.spaces.spaces.get(data.spaceId);
    if (!space) throw fileNotFound();
    const now = new Date();
    const revision = space.revision + 1n;
    this.spaces.spaces.set(space.id, { ...space, revision, updatedAt: now });
    const newVersion = file.version + 1;
    const updated: CommittedFileRecord = {
      ...file,
      objectId: data.objectId,
      size: data.size,
      sha256: data.sha256,
      version: newVersion,
      lastModifiedByDeviceId: data.deviceId,
      updatedAt: now,
    };
    this.spaces.files.set(file.id, updated);
    const versionId = randomUUID();
    this.versions.set(versionId, {
      id: versionId,
      fileId: file.id,
      version: newVersion,
      objectId: data.objectId,
      size: data.size,
      sha256: data.sha256,
      createdAt: now,
      createdByDeviceId: data.deviceId,
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
    const event: SyncEventRecord = {
      id: randomUUID(),
      spaceId: data.spaceId,
      revision,
      type: 'file_updated',
      deviceId: data.deviceId,
      operationId: data.operationId,
      payload,
      createdAt: now,
    };
    this.events.set(event.id, event);
    const upload = this.transfers.sessions.get(data.uploadId);
    if (upload) upload.fileId = file.id;
    return { file: updated, event, duplicate: false };
  }

  async commitFileDeleted(data: CommitFileDeletedData): Promise<CommitMutationResult> {
    const existingEvent = [...this.events.values()].find(
      (event) =>
        event.spaceId === data.spaceId &&
        event.deviceId === data.deviceId &&
        event.operationId === data.operationId,
    );
    if (existingEvent) {
      if (existingEvent.payload.fileId !== data.fileId) {
        throw idempotencyConflict();
      }
      const file = this.spaces.files.get(existingEvent.payload.fileId);
      if (!file) throw new Error('event file not found');
      return { file, event: existingEvent, duplicate: true };
    }
    const file = this.spaces.files.get(data.fileId);
    if (!file || file.spaceId !== data.spaceId) throw fileNotFound();
    if (file.isDeleted) throw fileIsDeleted();
    if (file.version !== data.baseVersion) throw versionConflict(file.version);
    const space = this.spaces.spaces.get(data.spaceId);
    if (!space) throw fileNotFound();
    const now = new Date();
    const revision = space.revision + 1n;
    this.spaces.spaces.set(space.id, { ...space, revision, updatedAt: now });
    const updated: CommittedFileRecord = {
      ...file,
      isDeleted: true,
      lastModifiedByDeviceId: data.deviceId,
      updatedAt: now,
    };
    this.spaces.files.set(file.id, updated);
    this.trash.set(file.id, {
      id: file.id,
      spaceId: file.spaceId,
      fileId: file.id,
      relativePath: file.relativePath,
      fileName: file.fileName,
      objectId: file.objectId,
      size: file.size,
      sha256: file.sha256,
      version: file.version,
      deletedByDeviceId: data.deviceId,
      deletedAt: now,
      purgeAfter: new Date(now.getTime() + TRASH_RETENTION_DAYS * 24 * 60 * 60 * 1000),
    });
    const payload: FileDeletedPayload = {
      fileId: file.id,
      relativePath: file.relativePath,
      fileName: file.fileName,
      version: file.version,
      baseVersion: data.baseVersion,
      lastModifiedByDeviceId: data.deviceId,
    };
    const event: SyncEventRecord = {
      id: randomUUID(),
      spaceId: data.spaceId,
      revision,
      type: 'file_deleted',
      deviceId: data.deviceId,
      operationId: data.operationId,
      payload,
      createdAt: now,
    };
    this.events.set(event.id, event);
    return { file: updated, event, duplicate: false };
  }

  async commitFileRenamed(data: CommitFileRenamedData): Promise<CommitMutationResult> {
    const existingEvent = [...this.events.values()].find(
      (event) =>
        event.spaceId === data.spaceId &&
        event.deviceId === data.deviceId &&
        event.operationId === data.operationId,
    );
    if (existingEvent) {
      const payload = existingEvent.payload;
      if (
        !('newPath' in payload) ||
        payload.fileId !== data.fileId ||
        payload.newPath !== data.newRelativePath
      ) {
        throw idempotencyConflict();
      }
      const file = this.spaces.files.get(payload.fileId);
      if (!file) throw new Error('event file not found');
      return { file, event: existingEvent, duplicate: true };
    }
    const file = this.spaces.files.get(data.fileId);
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
    const occupant = [...this.spaces.files.values()].find(
      (candidate) =>
        candidate.spaceId === data.spaceId &&
        candidate.relativePath === data.newRelativePath &&
        !candidate.isDeleted &&
        candidate.id !== file.id,
    );
    if (occupant) throw pathConflict();
    const space = this.spaces.spaces.get(data.spaceId);
    if (!space) throw fileNotFound();
    const now = new Date();
    const revision = space.revision + 1n;
    this.spaces.spaces.set(space.id, { ...space, revision, updatedAt: now });
    const updated: CommittedFileRecord = {
      ...file,
      relativePath: data.newRelativePath,
      fileName: newFileName,
      lastModifiedByDeviceId: data.deviceId,
      updatedAt: now,
    };
    this.spaces.files.set(file.id, updated);
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
    const event: SyncEventRecord = {
      id: randomUUID(),
      spaceId: data.spaceId,
      revision,
      type: 'file_renamed',
      deviceId: data.deviceId,
      operationId: data.operationId,
      payload,
      createdAt: now,
    };
    this.events.set(event.id, event);
    return { file: updated, event, duplicate: false };
  }

  async getSpaceRevision(spaceId: string): Promise<bigint | null> {
    return this.spaces.spaces.get(spaceId)?.revision ?? null;
  }

  async listEvents(
    spaceId: string,
    sinceRevision: bigint,
    maxRevision: bigint,
    limit: number,
  ): Promise<SyncEventRecord[]> {
    return [...this.events.values()]
      .filter(
        (event) =>
          event.spaceId === spaceId &&
          event.revision > sinceRevision &&
          event.revision <= maxRevision,
      )
      .sort((a, b) => (a.revision < b.revision ? -1 : a.revision > b.revision ? 1 : 0))
      .slice(0, limit);
  }

  async acknowledge(
    spaceId: string,
    deviceId: string,
    lastAppliedRevision: bigint,
  ): Promise<{ serverRevision: bigint; lastAppliedRevision: bigint }> {
    const space = this.spaces.spaces.get(spaceId);
    if (!space) throw new Error('space not found');
    const key = `${spaceId}:${deviceId}`;
    const cursor = this.spaces.cursors.get(key) ?? { spaceId, deviceId, lastAppliedRevision: 0n };
    if (lastAppliedRevision > cursor.lastAppliedRevision) {
      cursor.lastAppliedRevision = lastAppliedRevision;
      this.spaces.cursors.set(key, cursor);
    }
    return { serverRevision: space.revision, lastAppliedRevision: cursor.lastAppliedRevision };
  }

  async getStatus(spaceId: string, deviceId: string): Promise<SyncStatusRecord | null> {
    const space = this.spaces.spaces.get(spaceId);
    if (!space) return null;
    const lastAppliedRevision =
      this.spaces.cursors.get(`${spaceId}:${deviceId}`)?.lastAppliedRevision ?? 0n;
    return {
      serverRevision: space.revision,
      lastAppliedRevision,
      pendingEvents: [...this.events.values()].filter(
        (event) => event.spaceId === spaceId && event.revision > lastAppliedRevision,
      ).length,
      activeFileCount: [...this.spaces.files.values()].filter(
        (file) => file.spaceId === spaceId && !file.isDeleted,
      ).length,
    };
  }

  async findObject(objectId: string): Promise<DownloadObjectRecord | null> {
    const file = [...this.spaces.files.values()].find(
      (candidate) => candidate.objectId === objectId && !candidate.isDeleted,
    );
    if (file) {
      return {
        objectId,
        spaceId: file.spaceId,
        fileName: file.fileName,
        size: file.size,
        sha256: file.sha256,
      };
    }
    const version = [...this.versions.values()].find((candidate) => candidate.objectId === objectId);
    const versionFile = version ? this.spaces.files.get(version.fileId) : undefined;
    if (!version || !versionFile || versionFile.isDeleted) return null;
    return {
      objectId,
      spaceId: versionFile.spaceId,
      fileName: versionFile.fileName,
      size: version.size,
      sha256: version.sha256,
    };
  }

  async findFileById(fileId: string): Promise<CommittedFileRecord | null> {
    const file = this.spaces.files.get(fileId);
    return file && !file.isDeleted ? file : null;
  }

  async findFileByPath(spaceId: string, relativePath: string): Promise<CommittedFileRecord | null> {
    return (
      [...this.spaces.files.values()].find(
        (file) =>
          file.spaceId === spaceId && file.relativePath === relativePath && !file.isDeleted,
      ) ?? null
    );
  }

  async listFileVersions(fileId: string): Promise<FileVersionRecord[]> {
    return [...this.versions.values()]
      .filter((version) => version.fileId === fileId)
      .sort((a, b) => b.version - a.version);
  }

  async recordConflict(data: RecordConflictData): Promise<ConflictRecord> {
    const pending = [...this.conflicts.values()].find(
      (conflict) =>
        conflict.spaceId === data.spaceId &&
        conflict.fileId === data.fileId &&
        conflict.status === 'pending',
    );
    if (pending && pending.submittedVersion.sha256 === data.submitted.sha256) {
      return { ...pending };
    }
    const file = this.spaces.files.get(data.fileId);
    if (!file) throw fileNotFound();
    const conflict: ConflictRecord = {
      id: randomUUID(),
      spaceId: data.spaceId,
      fileId: data.fileId,
      relativePath: file.relativePath,
      fileName: file.fileName,
      serverVersion: data.serverVersion,
      submittedVersion: data.submitted,
      status: 'pending',
      resolvedAt: null,
      createdAt: new Date(),
    };
    this.conflicts.set(conflict.id, conflict);
    return { ...conflict };
  }

  async listConflicts(spaceId: string): Promise<ConflictRecord[]> {
    return [...this.conflicts.values()]
      .filter((conflict) => conflict.spaceId === spaceId)
      .sort((a, b) => b.createdAt.getTime() - a.createdAt.getTime())
      .map((conflict) => ({ ...conflict }));
  }

  async findConflictById(conflictId: string): Promise<ConflictRecord | null> {
    const conflict = this.conflicts.get(conflictId);
    return conflict ? { ...conflict } : null;
  }

  async resolveConflict(data: ResolveConflictData): Promise<ResolveConflictResult> {
    const conflict = this.conflicts.get(data.conflictId);
    if (!conflict) throw conflictNotFound();
    if (conflict.status === 'resolved') throw conflictAlreadyResolved();
    const file = this.spaces.files.get(conflict.fileId);
    if (!file || file.isDeleted) throw fileIsDeleted();
    const space = this.spaces.spaces.get(conflict.spaceId);
    if (!space) throw fileNotFound();
    const now = new Date();
    const revision = space.revision + 1n;
    this.spaces.spaces.set(space.id, { ...space, revision, updatedAt: now });
    const submitted = conflict.submittedVersion;

    let updatedFile: CommittedFileRecord | null = null;
    let eventType: string;
    let payload: SyncEventRecord['payload'];
    if (data.resolution === 'server') {
      eventType = 'conflict_resolved';
      payload = {
        conflictId: conflict.id,
        fileId: file.id,
        resolution: 'server',
        version: file.version,
      };
    } else if (data.resolution === 'client') {
      const newVersion = file.version + 1;
      updatedFile = {
        ...file,
        objectId: submitted.objectId,
        size: BigInt(submitted.size),
        sha256: submitted.sha256,
        version: newVersion,
        lastModifiedByDeviceId: data.deviceId,
        updatedAt: now,
      };
      this.spaces.files.set(file.id, updatedFile);
      const version: FileVersionRecord = {
        id: randomUUID(),
        fileId: file.id,
        version: newVersion,
        objectId: submitted.objectId,
        size: BigInt(submitted.size),
        sha256: submitted.sha256,
        createdAt: now,
        createdByDeviceId: data.deviceId,
      };
      this.versions.set(version.id, version);
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
      const copyPath = this.uniqueConflictPath(conflict.spaceId, file.relativePath, data.deviceId);
      const copyName = copyPath.split('/').at(-1)!;
      updatedFile = {
        id: randomUUID(),
        spaceId: conflict.spaceId,
        relativePath: copyPath,
        fileName: copyName,
        objectId: submitted.objectId,
        size: BigInt(submitted.size),
        sha256: submitted.sha256,
        version: 1,
        isDeleted: false,
        createdAt: now,
        updatedAt: now,
        lastModifiedByDeviceId: data.deviceId,
      };
      this.spaces.files.set(updatedFile.id, updatedFile);
      const version: FileVersionRecord = {
        id: randomUUID(),
        fileId: updatedFile.id,
        version: 1,
        objectId: submitted.objectId,
        size: BigInt(submitted.size),
        sha256: submitted.sha256,
        createdAt: now,
        createdByDeviceId: data.deviceId,
      };
      this.versions.set(version.id, version);
      eventType = 'file_created';
      payload = {
        fileId: updatedFile.id,
        relativePath: copyPath,
        fileName: copyName,
        objectId: submitted.objectId,
        size: submitted.size,
        sha256: submitted.sha256,
        version: 1,
        lastModifiedByDeviceId: data.deviceId,
      };
    }

    const resolved: ConflictRecord = { ...conflict, status: 'resolved', resolvedAt: now };
    this.conflicts.set(conflict.id, resolved);
    const event: SyncEventRecord = {
      id: randomUUID(),
      spaceId: conflict.spaceId,
      revision,
      type: eventType,
      deviceId: data.deviceId,
      operationId: data.operationId,
      payload,
      createdAt: now,
    };
    this.events.set(event.id, event);
    return { conflict: { ...resolved }, file: updatedFile, event };
  }

  private uniqueConflictPath(spaceId: string, relativePath: string, deviceId: string): string {
    const slash = relativePath.lastIndexOf('/');
    const dir = slash >= 0 ? relativePath.slice(0, slash + 1) : '';
    const name = slash >= 0 ? relativePath.slice(slash + 1) : relativePath;
    const dot = name.lastIndexOf('.');
    const base = dot > 0 ? name.slice(0, dot) : name;
    const ext = dot > 0 ? name.slice(dot) : '';
    const tag = `conflict from ${deviceId.slice(0, 8)}`;
    let candidate = `${dir}${base} (${tag})${ext}`;
    let suffix = 2;
    while (
      [...this.spaces.files.values()].some(
        (file) => file.spaceId === spaceId && file.relativePath === candidate && !file.isDeleted,
      )
    ) {
      candidate = `${dir}${base} (${tag} ${suffix})${ext}`;
      suffix += 1;
    }
    return candidate;
  }

  async listTrash(spaceId: string): Promise<TrashItemRecord[]> {
    return [...this.trash.values()]
      .filter((item) => item.spaceId === spaceId)
      .sort((a, b) => b.deletedAt.getTime() - a.deletedAt.getTime())
      .map((item) => ({ ...item }));
  }

  async findTrashItemById(trashId: string): Promise<TrashItemRecord | null> {
    const item = this.trash.get(trashId);
    return item ? { ...item } : null;
  }

  async restoreTrashItem(
    trashId: string,
    deviceId: string,
    operationId: string,
  ): Promise<RestoreTrashResult> {
    const item = this.trash.get(trashId);
    if (!item) throw trashNotFound();
    const file = this.spaces.files.get(item.fileId);
    if (!file || !file.isDeleted) throw trashNotFound();
    const occupant = [...this.spaces.files.values()].find(
      (candidate) =>
        candidate.spaceId === item.spaceId &&
        candidate.relativePath === item.relativePath &&
        !candidate.isDeleted &&
        candidate.id !== file.id,
    );
    if (occupant) throw pathConflict();
    const space = this.spaces.spaces.get(item.spaceId);
    if (!space) throw trashNotFound();
    const now = new Date();
    const revision = space.revision + 1n;
    this.spaces.spaces.set(space.id, { ...space, revision, updatedAt: now });
    const restored: CommittedFileRecord = {
      ...file,
      isDeleted: false,
      lastModifiedByDeviceId: deviceId,
      updatedAt: now,
    };
    this.spaces.files.set(file.id, restored);
    this.trash.delete(trashId);
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
    const event: SyncEventRecord = {
      id: randomUUID(),
      spaceId: item.spaceId,
      revision,
      type: 'file_restored',
      deviceId,
      operationId,
      payload,
      createdAt: now,
    };
    this.events.set(event.id, event);
    return { file: restored, event };
  }

  async purgeTrashItem(trashId: string): Promise<TrashItemRecord | null> {
    const item = this.trash.get(trashId);
    if (!item) return null;
    this.trash.delete(trashId);
    return { ...item };
  }

  async countRecentDeletions(spaceId: string, deviceId: string, since: Date): Promise<number> {
    return [...this.events.values()].filter(
      (event) =>
        event.spaceId === spaceId &&
        event.deviceId === deviceId &&
        event.type === 'file_deleted' &&
        event.createdAt.getTime() >= since.getTime(),
    ).length;
  }
}
