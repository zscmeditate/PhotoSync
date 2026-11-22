import {
  invalidDevice,
  mappingNotFound,
  mappingOverlap,
  notMember,
  spaceForbidden,
  spaceNotFound,
  validationError,
} from '../lib/errors';
import type {
  DeviceRecord,
  DeviceRepository,
  FolderMappingRecord,
  MappingUpdateData,
  MemberRoleValue,
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

export interface MappingDto {
  id: string;
  spaceId: string;
  deviceId: string;
  localPathLabel: string;
  remotePrefix: string;
  includeSubdirs: boolean;
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
  remotePrefix: string;
  includeSubdirs: boolean;
  onlyMedia: boolean;
}

export interface UpdateMappingInput {
  localPathLabel?: string;
  remotePrefix?: string;
  includeSubdirs?: boolean;
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

export function normalizeRemotePrefix(raw: string): string {
  const normalized = raw
    .trim()
    .replace(/\\/g, '/')
    .replace(/\/{2,}/g, '/')
    .replace(/^\/+|\/+$/g, '');

  if (normalized.length > 512) {
    throw validationError('remotePrefix must be at most 512 characters');
  }
  if (/[\u0000-\u001f\u007f]/.test(normalized)) {
    throw validationError('remotePrefix must not contain control characters');
  }
  if (normalized === '') {
    return '';
  }

  const segments = normalized.split('/');
  for (const segment of segments) {
    if (segment === '' || segment === '..') {
      throw validationError('remotePrefix contains an invalid path segment');
    }
    if (segment.length > 128) {
      throw validationError('remotePrefix segments must be at most 128 characters');
    }
  }
  return normalized;
}

export function remotePrefixesOverlap(a: string, b: string): boolean {
  return a === '' || b === '' || a === b || a.startsWith(`${b}/`) || b.startsWith(`${a}/`);
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

function isInMappingScope(
  relativePath: string,
  remotePrefix: string,
  includeSubdirs: boolean,
): boolean {
  const pathWithinPrefix = remotePrefix === ''
    ? relativePath
    : relativePath.startsWith(`${remotePrefix}/`)
      ? relativePath.slice(remotePrefix.length + 1)
      : null;
  return pathWithinPrefix !== null && pathWithinPrefix !== '' &&
    (includeSubdirs || !pathWithinPrefix.includes('/'));
}

function toMappingDto(mapping: FolderMappingRecord): MappingDto {
  return {
    id: mapping.id,
    spaceId: mapping.spaceId,
    deviceId: mapping.deviceId,
    localPathLabel: mapping.localPathLabel,
    remotePrefix: mapping.remotePrefix,
    includeSubdirs: mapping.includeSubdirs,
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
    const remotePrefix = normalizeRemotePrefix(input.remotePrefix);
    await this.assertNoOverlap(spaceId, input.deviceId, remotePrefix);
    const mapping = await this.deps.spaces.createMapping({
      spaceId,
      deviceId: input.deviceId,
      localPathLabel: input.localPathLabel.trim(),
      remotePrefix,
      includeSubdirs: input.includeSubdirs,
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
    const remotePrefix =
      input.remotePrefix !== undefined ? normalizeRemotePrefix(input.remotePrefix) : undefined;
    if (remotePrefix !== undefined) {
      await this.assertNoOverlap(mapping.spaceId, mapping.deviceId, remotePrefix, mapping.id);
    }
    const data: MappingUpdateData = {
      ...(input.localPathLabel !== undefined
        ? { localPathLabel: input.localPathLabel.trim() }
        : {}),
      ...(remotePrefix !== undefined ? { remotePrefix } : {}),
      ...(input.includeSubdirs !== undefined ? { includeSubdirs: input.includeSubdirs } : {}),
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
    const remoteFiles = allRemoteFiles.filter(
      (file) =>
        isInMappingScope(file.relativePath, mapping.remotePrefix, mapping.includeSubdirs) &&
        (!onlyMedia || isMediaPath(file.relativePath)),
    );
    const remoteByPath = new Map(remoteFiles.map((file) => [file.relativePath, file]));
    const localByPath = new Map<string, ScanFileInput>();
    const localFiles = input.files.filter(
      (file) =>
        isInMappingScope(file.relativePath, '', mapping.includeSubdirs) &&
        (!onlyMedia || isMediaPath(file.relativePath)),
    );
    let toUpload = 0;
    let conflictCount = 0;

    for (const file of localFiles) {
      const logicalPath = mapping.remotePrefix
        ? `${mapping.remotePrefix}/${file.relativePath}`
        : file.relativePath;
      localByPath.set(logicalPath, file);
      const remote = remoteByPath.get(logicalPath);
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

  private async assertNoOverlap(
    spaceId: string,
    deviceId: string,
    remotePrefix: string,
    excludeMappingId?: string,
  ): Promise<void> {
    const mappings = await this.deps.spaces.listMappings(spaceId, deviceId);
    if (
      mappings.some(
        (mapping) =>
          mapping.id !== excludeMappingId &&
          remotePrefixesOverlap(remotePrefix, mapping.remotePrefix),
      )
    ) {
      throw mappingOverlap();
    }
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
