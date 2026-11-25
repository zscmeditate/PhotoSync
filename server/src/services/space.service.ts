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
  /** none 表示该设备尚未加入该空间（与 spaceDtoSchema 的枚举一致） */
  role: MemberRoleValue | 'none';
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

/** 空间云端存储统计：活动文件与回收站的数量/总大小（字节数以十进制字符串表示）。 */
export interface SpaceStorageStatsDto {
  activeFileCount: number;
  activeSize: string;
  trashCount: number;
  trashSize: string;
}

export interface MappingDto {
  id: string;
  spaceId: string;
  deviceId: string;
  localPathLabel: string;
  /** 设备端 SAF 树 URI，用于重装后恢复绑定 */
  treeUri: string | null;
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
  treeUri?: string;
  onlyMedia: boolean;
}

export interface UpdateMappingInput {
  localPathLabel?: string;
  treeUri?: string;
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
    objectId: string;
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
  /** null 表示该设备属于当前账号但尚未加入这个空间（可用于「加入空间」列表） */
  role: MemberRoleValue | null;
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
    treeUri: mapping.treeUri,
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
    // 其他同账号设备不会自动加入：它们在「加入空间」中能看到该空间（role=none），
    // 需主动提交加入申请，由主设备审批通过后才成为成员并绑定目录。
    return this.toSpaceDto(space, 'admin', 1, 0);
  }

  async listSpaces(userId: string, deviceId?: string): Promise<SpaceDto[]> {
    const deviceIds = (await this.deps.devices.listByUser(userId)).map((device) => device.id);
    const spaces = await this.deps.spaces.listAccessible(userId, deviceIds);
    const result: SpaceDto[] = [];
    const seen = new Set<string>();
    for (const space of spaces) {
      if (seen.has(space.id)) {
        continue;
      }
      seen.add(space.id);
      const role = await this.roleFor(space, userId, deviceIds, deviceId);
      // 未加入该空间的设备仍应看到空间（用于「加入空间」列表），标记为 none
      result.push(this.toSpaceDto(space, role ?? 'none'));
    }
    return result;
  }

  async getSpace(
    userId: string,
    spaceId: string,
    deviceId?: string,
  ): Promise<{ space: SpaceDto; members: MemberDto[] }> {
    const access = await this.requireAccess(userId, spaceId, deviceId);
    const members = await this.deps.spaces.listMembers(spaceId);
    const now = this.now();
    // 各账号的主设备在本系统中等同管理员，成员列表需要体现角色差异
    const primaryByUser = new Map<string, string | null>();
    for (const member of members) {
      const memberUserId = member.device.userId;
      if (!primaryByUser.has(memberUserId)) {
        primaryByUser.set(
          memberUserId,
          await this.deps.devices.findPrimaryDeviceId(memberUserId),
        );
      }
    }
    return {
      space: this.toSpaceDto(access.space, access.role ?? 'none'),
      members: members.map((member) =>
        this.toMemberDto(member, now, primaryByUser.get(member.device.userId) ?? null),
      ),
    };
  }

  async updateSpace(
    userId: string,
    deviceId: string,
    spaceId: string,
    data: SpaceUpdateData,
  ): Promise<SpaceDto> {
    // 只有管理员（owner/admin；操作设备为主设备时自动具备 admin 角色）才可以修改空间信息
    const access = await this.requireAccess(userId, spaceId, deviceId);
    if (access.role !== 'owner' && access.role !== 'admin') {
      throw spaceForbidden();
    }
    const updated = await this.deps.spaces.update(spaceId, {
      ...(data.name !== undefined ? { name: data.name.trim() } : {}),
      ...(data.description !== undefined ? { description: data.description } : {}),
    });
    return this.toSpaceDto(updated, access.role ?? 'admin');
  }

  async deleteSpace(
    userId: string,
    deviceId: string,
    spaceId: string,
  ): Promise<void> {
    await this.requireAccess(userId, spaceId);
    await this.requirePrimaryDevice(userId, deviceId);
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
    const primaryDeviceId = await this.deps.devices.findPrimaryDeviceId(member.device.userId);
    return {
      member: this.toMemberDto(member, this.now(), primaryDeviceId),
      created: existing === null,
    };
  }

  async removeSpaceMember(
    userId: string,
    operatorDeviceId: string,
    spaceId: string,
    deviceId: string,
  ): Promise<void> {
    await this.requireAccess(userId, spaceId);
    const member = await this.deps.spaces.findMember(spaceId, deviceId);
    if (!member) {
      throw notMember(404);
    }
    const isPrimary = await this.isPrimaryDevice(userId, operatorDeviceId);
    // 主设备可以踢任何人；从设备只能退出自己
    if (!isPrimary && operatorDeviceId !== deviceId) {
      throw spaceForbidden();
    }
    await this.deps.spaces.removeMember(spaceId, deviceId);
  }

  /** 设备申请加入本账号下的空间；主设备自己申请时无需审批，直接成为成员。 */
  async requestToJoinSpace(
    userId: string,
    spaceId: string,
    deviceId: string,
  ): Promise<{ request: JoinRequestDto; joined: boolean }> {
    await this.requireOwnedActiveDevice(userId, deviceId);
    const space = await this.deps.spaces.findById(spaceId);
    if (!space) {
      throw spaceNotFound();
    }
    // 同账号即可，不再限 owner
    const deviceIds = (await this.deps.devices.listByUser(userId)).map((d) => d.id);
    const accessible = await this.deps.spaces.listAccessible(userId, deviceIds);
    if (!accessible.some((s) => s.id === spaceId)) {
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
    // 同账号成员均可查看，只有主设备能审批
    const deviceIds = (await this.deps.devices.listByUser(userId)).map((d) => d.id);
    const accessible = await this.deps.spaces.listAccessible(userId, deviceIds);
    if (!accessible.some((s) => s.id === spaceId)) {
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
    const deviceIds = (await this.deps.devices.listByUser(userId)).map((d) => d.id);
    const accessible = await this.deps.spaces.listAccessible(userId, deviceIds);
    if (!accessible.some((s) => s.id === space.id)) {
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
      treeUri: input.treeUri?.trim() || null,
      filters: { onlyMedia: input.onlyMedia },
    });
    return toMappingDto(mapping);
  }

  /** 列出某台设备在全部空间上的映射：重装/重新登录后据此恢复本地目录绑定 */
  async listDeviceMappings(userId: string, deviceId: string): Promise<MappingDto[]> {
    await this.requireOwnedActiveDevice(userId, deviceId);
    const mappings = await this.deps.spaces.listMappingsByDevice(deviceId);
    return mappings.map(toMappingDto);
  }

  async listMappings(userId: string, spaceId: string, deviceId?: string): Promise<MappingDto[]> {
    await this.requireAccess(userId, spaceId);
    const mappings = await this.deps.spaces.listMappings(spaceId, deviceId);
    return mappings.map(toMappingDto);
  }

  /** 空间云端存储统计：活动文件与回收站的数量/总大小。 */
  async getStorageStats(userId: string, spaceId: string): Promise<SpaceStorageStatsDto> {
    await this.requireAccess(userId, spaceId);
    const stats = await this.deps.spaces.storageStats(spaceId);
    return {
      activeFileCount: stats.activeFileCount,
      activeSize: String(stats.activeSize),
      trashCount: stats.trashCount,
      trashSize: String(stats.trashSize),
    };
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
      ...(input.treeUri !== undefined ? { treeUri: input.treeUri.trim() || null } : {}),
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
        objectId: file.objectId,
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

  private async isPrimaryDevice(userId: string, deviceId: string): Promise<boolean> {
    const device = await this.deps.devices.findById(deviceId);
    if (!device || device.userId !== userId || device.credentialStatus !== 'active') {
      return false;
    }
    const primaryDeviceId = await this.deps.devices.findPrimaryDeviceId(userId);
    return primaryDeviceId === deviceId;
  }

  /**
   * 获取指定设备在空间中的角色。
   * 主设备一律视为 admin（全空间管理者），从设备视为 member。
   */
  private async roleFor(
    space: SyncSpaceRecord,
    userId: string,
    deviceIds: string[],
    specificDeviceId?: string,
  ): Promise<MemberRoleValue | null> {
    const primaryDeviceId = await this.deps.devices.findPrimaryDeviceId(userId);

    if (specificDeviceId) {
      // 主设备 → admin，其他设备查 SpaceMember 表
      if (primaryDeviceId === specificDeviceId) return 'admin';
      const member = await this.deps.spaces.findMember(space.id, specificDeviceId);
      if (member) return member.role;
      // 该设备还没加入这个空间，且不是主设备
      return null;
    }
    // 回退：取账号下所有设备的最高角色（兼容老接口）
    const memberships = await this.deps.spaces.listMembershipsForDevices(space.id, deviceIds);
    const roles = memberships.map((m) => m.role);
    if (primaryDeviceId) roles.push('admin');
    return highestRole(roles);
  }

  private async requireAccess(userId: string, spaceId: string, specificDeviceId?: string): Promise<SpaceAccess> {
    const space = await this.deps.spaces.findById(spaceId);
    if (!space) {
      throw spaceNotFound();
    }
    const deviceIds = (await this.deps.devices.listByUser(userId)).map((device) => device.id);
    if (specificDeviceId && !deviceIds.includes(specificDeviceId)) {
      // 指定设备不属于当前账号：直接拒绝，避免越权读取
      throw spaceForbidden();
    }
    const role = await this.roleFor(space, userId, deviceIds, specificDeviceId);
    if (role === null && !specificDeviceId) {
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
    role: MemberRoleValue | 'none',
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

  private toMemberDto(
    member: SpaceMemberWithDeviceRecord,
    now: Date,
    primaryDeviceId: string | null = null,
  ): MemberDto {
    return {
      deviceId: member.deviceId,
      deviceName: member.device.deviceName,
      platform: member.device.platform,
      // 主设备即管理员（与权限判定一致），其余按成员表角色
      role: primaryDeviceId !== null && primaryDeviceId === member.deviceId ? 'admin' : member.role,
      joinedAt: member.joinedAt.toISOString(),
      lastSeenAt: member.device.lastSeenAt?.toISOString() ?? null,
      online:
        member.device.lastSeenAt !== null &&
        now.getTime() - member.device.lastSeenAt.getTime() <= ONLINE_WINDOW_MS,
    };
  }
}
