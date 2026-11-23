import {
  alreadyMember,
  deviceNotPrimary,
  invalidDevice,
  joinRequestNotFound,
  joinRequestResolved,
  mappingExists,
  mappingNotFound,
  notMember,
  spaceForbidden,
  spaceNotFound,
  validationError,
} from '../lib/errors';
import type {
  DeviceRecord,
  DeviceRepository,
  FolderMappingRecord,
  JoinRequestStatusValue,
  MappingUpdateData,
  MemberRoleValue,
  SpaceJoinRequestWithDeviceRecord,
  SpaceMemberWithDeviceRecord,
  SpaceRepository,
  SpaceUpdateData,
  SpaceWithCountsRecord,
  SyncSpaceRecord,
} from '../repositories/ports';

const MAX_REMOTE_FILES = 10000;
const ONLINE_WINDOW_MS = 5 * 60 * 1000;
const ROLE_RANK: Record<MemberRoleValue, number> = {
  viewer: 0,
  member: 1,
  admin: 2,
  owner: 3,
};

export interface SpaceDto {
  id: string;
  ownerId: string;
  name: string;
  description: string | null;
  revision: string;
  role: MemberRoleValue;
  memberCount: number;
  mappingCount: number;
  createdAt: string;
  updatedAt: string;
}

export interface MemberDto {
  deviceId: string;
  deviceName: string;
  platform: string;
  role: MemberRoleValue;
  joinedAt: string;
  lastSeenAt: string | null;
  online: boolean;
}

export interface JoinRequestDto {
  id: string;
  spaceId: string;
  deviceId: string;
  deviceName: string;
  platform: string;
  status: JoinRequestStatusValue;
  requestedAt: string;
  resolvedAt: string | null;
}

export interface MappingDto {
  id: string;
  spaceId: string;
  deviceId: string;
  localPathLabel: string;
  onlyMedia: boolean;
  createdAt: string;
}

export interface CreateSpaceInput {
  name: string;
  description?: string;
  deviceId: string;
}

export interface CreateMappingInput {
  deviceId: string;
  localPathLabel: string;
  onlyMedia: boolean;
}

export interface UpdateMappingInput {
  localPathLabel?: string;
  onlyMedia?: boolean;
}

export interface ScanFileInput {
  relativePath: string;
  size: number;
  sha256?: string;
  modifiedAt?: string;
}

export interface ScanManifestInput {
  deviceId: string;
  mappingId: string;
  scanComplete: boolean;
  files: ScanFileInput[];
}

export interface ScanManifestResult {
  summary: {
    localCount: number;
    remoteCount: number;
    toUpload: number;
    toDownload: number;
    conflictCount: number;
    diffComplete: boolean;
  };
  remoteFiles: Array<{
    fileId: string;
    relativePath: string;
    size: number;
    sha256: string;
    version: number;
  }>;
}

export interface SpaceServiceDeps {
  spaces: SpaceRepository;
  devices: DeviceRepository;
  now?: () => Date;
}

interface SpaceAccess {
  space: SpaceWithCountsRecord;
  role: MemberRoleValue;
  deviceIds: string[];
}

/** 规范化远端相对路径（文件路径或目录路径），空串表示空间根目录。 */
export function normalizeRemotePath(raw: string): string {
  const normalized = raw
    .trim()
    .replace(/\\/g, '/')
    .replace(/\/{2,}/g, '/')
    .replace(/^\/+|\/+$/g, '');

  if (normalized.length > 512) {
    throw validationError('remote path must be at most 512 characters');
  }
  if (/[\u0000-\u001f\u007f]/.test(normalized)) {
    throw validationError('remote path must not contain control characters');
  }
  if (normalized === '') {
    return '';
  }

  const segments = normalized.split('/');
  for (const segment of segments) {
    if (segment === '' || segment === '..') {
      throw validationError('remote path contains an invalid path segment');
    }
    if (segment.length > 128) {
      throw validationError('remote path segments must be at most 128 characters');
    }
  }
  return normalized;
}

function highestRole(roles: MemberRoleValue[]): MemberRoleValue | null {
  let highest: MemberRoleValue | null = null;
  for (const role of roles) {
    if (highest === null || ROLE_RANK[role] > ROLE_RANK[highest]) {
      highest = role;
    }
  }
  return highest;
}

function onlyMediaOf(filters: unknown): boolean {
  if (typeof filters !== 'object' || filters === null || !('onlyMedia' in filters)) {
    return false;
  }
  return (filters as { onlyMedia?: unknown }).onlyMedia === true;
}

const MEDIA_EXTENSIONS = new Set([
  'jpg', 'jpeg', 'png', 'gif', 'webp', 'heic', 'heif', 'bmp', 'avif',
  'mp4', 'mov', 'm4v', '3gp', 'mkv', 'webm', 'avi', 'mpeg', 'mpg',
]);

function isMediaPath(relativePath: string): boolean {
  const extension = relativePath.split('.').at(-1)?.toLowerCase() ?? '';
  return relativePath.includes('.') && MEDIA_EXTENSIONS.has(extension);
}

function toMappingDto(mapping: FolderMappingRecord): MappingDto {
  return {
    id: mapping.id,
    spaceId: mapping.spaceId,
    deviceId: mapping.deviceId,
    localPathLabel: mapping.localPathLabel,
    onlyMedia: onlyMediaOf(mapping.filters),
    createdAt: mapping.createdAt.toISOString(),
  };
}

export class SpaceService {
  private readonly now: () => Date;

  constructor(private readonly deps: SpaceServiceDeps) {
    this.now = deps.now ?? (() => new Date());
  }

  async createSpace(userId: string, input: CreateSpaceInput): Promise<SpaceDto> {
    await this.requireOwnedActiveDevice(userId, input.deviceId);
    // 只有主设备可以创建空间；从设备只能加入或退出已有空间
    const primaryDeviceId = await this.deps.devices.findPrimaryDeviceId(userId);
    if (primaryDeviceId === null || primaryDeviceId !== input.deviceId) {
      throw deviceNotPrimary();
    }
    const space = await this.deps.spaces.createWithOwner({
      ownerId: userId,
      name: input.name.trim(),
      description: input.description ?? null,
      deviceId: input.deviceId,
    });
    return this.toSpaceDto(space, 'owner', 1, 0);
  }

  async listSpaces(userId: string): Promise<SpaceDto[]> {
    const deviceIds = (await this.deps.devices.listByUser(userId)).map((device) => device.id);
    const spaces = await this.deps.spaces.listAccessible(userId, deviceIds);
    const result: SpaceDto[] = [];
    const seen = new Set<string>();
    for (const space of spaces) {
      if (seen.has(space.id)) {
        continue;
      }
      seen.add(space.id);
      const role = await this.roleFor(space, userId, deviceIds);
      if (role === null) {
        throw spaceForbidden();
      }
      result.push(this.toSpaceDto(space, role));
    }
    return result;
  }

  async getSpace(
    userId: string,
    spaceId: string,
  ): Promise<{ space: SpaceDto; members: MemberDto[] }> {
    const access = await this.requireAccess(userId, spaceId);
    const members = await this.deps.spaces.listMembers(spaceId);
    const now = this.now();
    return {
      space: this.toSpaceDto(access.space, access.role),
      members: members.map((member) => this.toMemberDto(member, now)),
    };
  }

  async updateSpace(userId: string, spaceId: string, data: SpaceUpdateData): Promise<SpaceDto> {
    const access = await this.requireAccess(userId, spaceId);
    if (access.role !== 'owner' && access.role !== 'admin') {
      throw spaceForbidden();
    }
    const updated = await this.deps.spaces.update(spaceId, {
      ...(data.name !== undefined ? { name: data.name.trim() } : {}),
      ...(data.description !== undefined ? { description: data.description } : {}),
    });
    return this.toSpaceDto(updated, access.role);
  }

  async deleteSpace(userId: string, spaceId: string): Promise<void> {
    const access = await this.requireAccess(userId, spaceId);
    if (access.space.ownerId !== userId) {
      throw spaceForbidden();
    }
    await this.deps.spaces.delete(spaceId);
  }

  async addSpaceMember(
    userId: string,
    spaceId: string,
    deviceId: string,
  ): Promise<{ member: MemberDto; created: boolean }> {
    await this.requireAccess(userId, spaceId);
    await this.requireOwnedActiveDevice(userId, deviceId);
    const existing = await this.deps.spaces.findMember(spaceId, deviceId);
    const member = await this.deps.spaces.addMember(spaceId, deviceId);
    return { member: this.toMemberDto(member, this.now()), created: existing === null };
  }

  async removeSpaceMember(userId: string, spaceId: string, deviceId: string): Promise<void> {
    const access = await this.requireAccess(userId, spaceId);
    const member = await this.deps.spaces.findMember(spaceId, deviceId);
    if (!member) {
      throw notMember(404);
    }
    if (access.space.ownerId !== userId) {
      await this.requireOwnedActiveDevice(userId, deviceId);
    }
    await this.deps.spaces.removeMember(spaceId, deviceId);
  }

  /** 从设备申请加入空间；主设备自己申请时无需审批，直接成为成员。 */
  async requestToJoinSpace(
    userId: string,
    spaceId: string,
    deviceId: string,
  ): Promise<{ request: JoinRequestDto; joined: boolean }> {
    const device = await this.requireOwnedActiveDevice(userId, deviceId);
    const space = await this.deps.spaces.findById(spaceId);
    if (!space) {
      throw spaceNotFound();
    }
    if (space.ownerId !== userId) {
      throw spaceForbidden();
    }
    if (await this.deps.spaces.findMember(spaceId, deviceId)) {
      throw alreadyMember();
    }

    const primaryDeviceId = await this.deps.devices.findPrimaryDeviceId(userId);
    if (primaryDeviceId === deviceId) {
      await this.deps.spaces.addMember(spaceId, deviceId);
      const request = await this.deps.spaces.createJoinRequest({
        spaceId,
        deviceId,
        status: 'approved',
        resolvedAt: this.now(),
      });
      return { request: this.toJoinRequestDto(request), joined: true };
    }

    // 重复提交返回同一条待处理申请，避免主设备看到重复卡片。
    const pending = await this.deps.spaces.findPendingJoinRequest(spaceId, deviceId);
    if (pending) {
      return { request: this.toJoinRequestDto(pending), joined: false };
    }
    const request = await this.deps.spaces.createJoinRequest({ spaceId, deviceId });
    return { request: this.toJoinRequestDto(request), joined: false };
  }

  /** 主设备查看该空间的待审批加入申请。 */
  async listJoinRequests(
    userId: string,
    spaceId: string,
    approverDeviceId: string,
  ): Promise<JoinRequestDto[]> {
    const space = await this.deps.spaces.findById(spaceId);
    if (!space) {
      throw spaceNotFound();
    }
    if (space.ownerId !== userId) {
      throw spaceForbidden();
    }
    await this.requirePrimaryDevice(userId, approverDeviceId);
    const requests = await this.deps.spaces.listPendingJoinRequests(spaceId);
    return requests.map((request) => this.toJoinRequestDto(request));
  }

  /** 主设备同意或拒绝一条加入申请；同意后设备立即成为成员。 */
  async resolveJoinRequest(
    userId: string,
    requestId: string,
    approverDeviceId: string,
    approve: boolean,
  ): Promise<JoinRequestDto> {
    const request = await this.deps.spaces.findJoinRequestById(requestId);
    if (!request) {
      throw joinRequestNotFound();
    }
    const space = await this.deps.spaces.findById(request.spaceId);
    if (!space) {
      throw spaceNotFound();
    }
    if (space.ownerId !== userId) {
      throw spaceForbidden();
    }
    await this.requirePrimaryDevice(userId, approverDeviceId);
    if (request.status !== 'pending') {
      throw joinRequestResolved();
    }
    if (approve) {
      await this.deps.spaces.addMember(request.spaceId, request.deviceId);
    }
    const updated = await this.deps.spaces.updateJoinRequest(requestId, {
      status: approve ? 'approved' : 'rejected',
      resolvedAt: this.now(),
    });
    return this.toJoinRequestDto(updated);
  }

  async createMapping(
    userId: string,
    spaceId: string,
    input: CreateMappingInput,
  ): Promise<MappingDto> {
    await this.requireAccess(userId, spaceId);
    await this.requireOwnedActiveDevice(userId, input.deviceId);
    if (!(await this.deps.spaces.findMember(spaceId, input.deviceId))) {
      throw notMember();
    }
    // 一个空间在一台设备上只允许绑定一个本地目录。
    const existing = await this.deps.spaces.listMappings(spaceId, input.deviceId);
    if (existing.length > 0) {
      throw mappingExists();
    }
    const mapping = await this.deps.spaces.createMapping({
      spaceId,
      deviceId: input.deviceId,
      localPathLabel: input.localPathLabel.trim(),
      filters: { onlyMedia: input.onlyMedia },
    });
    return toMappingDto(mapping);
  }

  async listMappings(userId: string, spaceId: string, deviceId?: string): Promise<MappingDto[]> {
    await this.requireAccess(userId, spaceId);
    const mappings = await this.deps.spaces.listMappings(spaceId, deviceId);
    return mappings.map(toMappingDto);
  }

  async updateMapping(
    userId: string,
    mappingId: string,
    input: UpdateMappingInput,
  ): Promise<MappingDto> {
    const mapping = await this.requireOwnedMapping(userId, mappingId);
    const data: MappingUpdateData = {
      ...(input.localPathLabel !== undefined
        ? { localPathLabel: input.localPathLabel.trim() }
        : {}),
      ...(input.onlyMedia !== undefined ? { filters: { onlyMedia: input.onlyMedia } } : {}),
    };
    return toMappingDto(await this.deps.spaces.updateMapping(mapping.id, data));
  }

  async deleteMapping(userId: string, mappingId: string): Promise<void> {
    const mapping = await this.requireOwnedMapping(userId, mappingId);
    await this.deps.spaces.deleteMapping(mapping.id);
  }

  async scanManifest(
    userId: string,
    spaceId: string,
    input: ScanManifestInput,
  ): Promise<ScanManifestResult> {
    await this.requireAccess(userId, spaceId);
    await this.requireOwnedActiveDevice(userId, input.deviceId);
    const mapping = await this.deps.spaces.findMappingById(input.mappingId);
    if (!mapping || mapping.spaceId !== spaceId || mapping.deviceId !== input.deviceId) {
      throw mappingNotFound();
    }

    const allRemoteFiles = await this.deps.spaces.listActiveFiles(spaceId);
    const onlyMedia = onlyMediaOf(mapping.filters);
    // 远端树完全由本地目录结构决定：映射根即空间根。
    const remoteFiles = allRemoteFiles.filter(
      (file) => !onlyMedia || isMediaPath(file.relativePath),
    );
    const remoteByPath = new Map(remoteFiles.map((file) => [file.relativePath, file]));
    const localByPath = new Map<string, ScanFileInput>();
    const localFiles = input.files.filter(
      (file) => !onlyMedia || isMediaPath(file.relativePath),
    );
    let toUpload = 0;
    let conflictCount = 0;

    for (const file of localFiles) {
      localByPath.set(file.relativePath, file);
      const remote = remoteByPath.get(file.relativePath);
      if (!remote) {
        toUpload += 1;
      } else if (
        file.sha256 !== undefined &&
        file.sha256.toLowerCase() !== remote.sha256.toLowerCase()
      ) {
        toUpload += 1;
        conflictCount += 1;
      }
    }

    let toDownload = 0;
    if (input.scanComplete) {
      for (const remote of remoteFiles) {
        if (!localByPath.has(remote.relativePath)) {
          toDownload += 1;
        }
      }
    }

    const remoteTruncated = remoteFiles.length > MAX_REMOTE_FILES;
    return {
      summary: {
        localCount: input.files.length,
        remoteCount: remoteFiles.length,
        toUpload,
        toDownload,
        conflictCount,
        diffComplete: input.scanComplete && !remoteTruncated,
      },
      remoteFiles: remoteFiles.slice(0, MAX_REMOTE_FILES).map((file) => ({
        fileId: file.id,
        relativePath: file.relativePath,
        size: Number(file.size),
        sha256: file.sha256,
        version: file.version,
      })),
    };
  }

  private async requireOwnedActiveDevice(userId: string, deviceId: string): Promise<DeviceRecord> {
    const device = await this.deps.devices.findById(deviceId);
    if (!device || device.userId !== userId || device.credentialStatus !== 'active') {
      throw invalidDevice();
    }
    return device;
  }

  /** 审批类操作只允许账号的主设备执行。 */
  private async requirePrimaryDevice(userId: string, deviceId: string): Promise<void> {
    await this.requireOwnedActiveDevice(userId, deviceId);
    const primaryDeviceId = await this.deps.devices.findPrimaryDeviceId(userId);
    if (primaryDeviceId === null || primaryDeviceId !== deviceId) {
      throw deviceNotPrimary();
    }
  }

  private async roleFor(
    space: SyncSpaceRecord,
    userId: string,
    deviceIds: string[],
  ): Promise<MemberRoleValue | null> {
    if (space.ownerId === userId) {
      return 'owner';
    }
    const memberships = await this.deps.spaces.listMembershipsForDevices(space.id, deviceIds);
    return highestRole(memberships.map((member) => member.role));
  }

  private async requireAccess(userId: string, spaceId: string): Promise<SpaceAccess> {
    const space = await this.deps.spaces.findById(spaceId);
    if (!space) {
      throw spaceNotFound();
    }
    const deviceIds = (await this.deps.devices.listByUser(userId)).map((device) => device.id);
    const role = await this.roleFor(space, userId, deviceIds);
    if (role === null) {
      throw spaceForbidden();
    }
    return { space, role, deviceIds };
  }

  private async requireOwnedMapping(
    userId: string,
    mappingId: string,
  ): Promise<FolderMappingRecord> {
    const mapping = await this.deps.spaces.findMappingById(mappingId);
    if (!mapping) {
      throw mappingNotFound();
    }
    const device = await this.deps.devices.findById(mapping.deviceId);
    if (!device || device.userId !== userId) {
      throw mappingNotFound();
    }
    return mapping;
  }

  private toSpaceDto(
    space: SyncSpaceRecord,
    role: MemberRoleValue,
    memberCount?: number,
    mappingCount?: number,
  ): SpaceDto {
    const counts = space as SpaceWithCountsRecord;
    return {
      id: space.id,
      ownerId: space.ownerId,
      name: space.name,
      description: space.description,
      revision: space.revision.toString(),
      role,
      memberCount: memberCount ?? counts.memberCount,
      mappingCount: mappingCount ?? counts.mappingCount,
      createdAt: space.createdAt.toISOString(),
      updatedAt: space.updatedAt.toISOString(),
    };
  }

  private toJoinRequestDto(request: SpaceJoinRequestWithDeviceRecord): JoinRequestDto {
    return {
      id: request.id,
      spaceId: request.spaceId,
      deviceId: request.deviceId,
      deviceName: request.device.deviceName,
      platform: request.device.platform,
      status: request.status,
      requestedAt: request.createdAt.toISOString(),
      resolvedAt: request.resolvedAt?.toISOString() ?? null,
    };
  }

  private toMemberDto(member: SpaceMemberWithDeviceRecord, now: Date): MemberDto {
    return {
      deviceId: member.deviceId,
      deviceName: member.device.deviceName,
      platform: member.device.platform,
      role: member.role,
      joinedAt: member.joinedAt.toISOString(),
      lastSeenAt: member.device.lastSeenAt?.toISOString() ?? null,
      online:
        member.device.lastSeenAt !== null &&
        now.getTime() - member.device.lastSeenAt.getTime() <= ONLINE_WINDOW_MS,
    };
  }
}
