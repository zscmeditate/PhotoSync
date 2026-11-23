import { createHash, randomUUID } from 'node:crypto';
import {
  idempotencyConflict,
  invalidDevice,
  notMember,
  partConflict,
  spaceNotFound,
  uploadIncomplete,
  uploadIntegrityMismatch,
  uploadNotActive,
  uploadNotFound,
  validationError,
} from '../lib/errors';
import type { ObjectStore } from '../lib/object-store';
import type {
  DeviceRepository,
  SpaceRepository,
  TransferRepository,
  UploadedPartRecord,
  UploadSessionRecord,
} from '../repositories/ports';
import { normalizeRemotePath } from './space.service';

export interface CreateUploadInput {
  spaceId: string;
  deviceId: string;
  relativePath: string;
  size: number;
  sha256: string;
  partSize?: number;
  idempotencyKey: string;
}

export interface UploadPartDto {
  partNumber: number;
  size: string;
  sha256: string;
}

export interface UploadDto {
  uploadId: string;
  spaceId: string;
  objectId: string;
  relativePath: string;
  size: string;
  sha256: string;
  partSize: string;
  status: string;
  uploadedParts: UploadPartDto[];
}

export interface TransferServiceDeps {
  transfers: TransferRepository;
  spaces: SpaceRepository;
  devices: DeviceRepository;
  objectStore: ObjectStore;
  uploadPartSize: number;
}

export function toUploadDto(session: UploadSessionRecord): UploadDto {
  return {
    uploadId: session.id,
    spaceId: session.spaceId,
    objectId: session.objectId,
    relativePath: session.relativePath,
    size: session.totalSize.toString(),
    sha256: session.sha256,
    partSize: session.partSize.toString(),
    status: session.status,
    uploadedParts: session.parts.map((part) => ({
      partNumber: part.partNumber,
      size: part.size.toString(),
      sha256: part.sha256,
    })),
  };
}

export class TransferService {
  constructor(private readonly deps: TransferServiceDeps) {}

  async createUpload(
    userId: string,
    input: CreateUploadInput,
  ): Promise<{ upload: UploadDto; created: boolean }> {
    await this.requireSpaceDevice(userId, input.spaceId, input.deviceId);
    const relativePath = normalizeRemotePath(input.relativePath);
    if (relativePath === '') {
      throw validationError('relativePath must not be empty');
    }
    const sha256 = input.sha256.toLowerCase();
    const totalSize = BigInt(input.size);
    const requestedPartSize = input.partSize ?? 0;
    if (requestedPartSize !== 0 && requestedPartSize !== this.deps.uploadPartSize) {
      throw validationError(`partSize must be 0 or ${this.deps.uploadPartSize}`);
    }
    const partSize = BigInt(this.deps.uploadPartSize);

    const existing = await this.deps.transfers.findUploadByIdempotency(
      input.deviceId,
      input.idempotencyKey,
    );
    if (existing) {
      assertSameUpload(existing, input.spaceId, relativePath, totalSize, partSize, sha256);
      return { upload: toUploadDto(existing), created: false };
    }

    const objectId = randomUUID();
    const providerUploadId = await this.deps.objectStore.createMultipartUpload(objectId);
    const result = await this.deps.transfers.createUpload({
      deviceId: input.deviceId,
      spaceId: input.spaceId,
      relativePath,
      sha256,
      idempotencyKey: input.idempotencyKey,
      providerUploadId,
      objectId,
      totalSize,
      partSize,
    });
    if (!result.created) {
      await this.deps.objectStore.abortMultipartUpload(objectId, providerUploadId);
      assertSameUpload(result.session, input.spaceId, relativePath, totalSize, partSize, sha256);
    }
    return { upload: toUploadDto(result.session), created: result.created };
  }

  async getUpload(userId: string, uploadId: string, deviceId: string): Promise<UploadDto> {
    const session = await this.requireUpload(userId, uploadId, deviceId);
    return toUploadDto(session);
  }

  async uploadPart(
    userId: string,
    uploadId: string,
    deviceId: string,
    partNumber: number,
    body: Buffer,
  ): Promise<{ part: UploadedPartRecord }> {
    const session = await this.requireUpload(userId, uploadId, deviceId);
    const expectedSize = expectedPartSize(session, partNumber);
    if (BigInt(body.byteLength) !== expectedSize) {
      throw validationError(`Part ${partNumber} must contain exactly ${expectedSize} bytes`);
    }
    const sha256 = createHash('sha256').update(body).digest('hex');
    const existing = session.parts.find((part) => part.partNumber === partNumber);
    if (existing) {
      if (existing.size === expectedSize && existing.sha256 === sha256) {
        return { part: existing };
      }
      throw partConflict();
    }
    if (session.status !== 'pending' && session.status !== 'uploading') {
      throw uploadNotActive();
    }

    const etag = await this.deps.objectStore.uploadPart({
      objectId: session.objectId,
      providerUploadId: session.providerUploadId,
      partNumber,
      body,
    });
    const part = await this.deps.transfers.saveUploadedPart(session.id, {
      partNumber,
      etag,
      size: expectedSize,
      sha256,
    });
    return { part };
  }

  async completeUpload(userId: string, uploadId: string, deviceId: string): Promise<UploadDto> {
    const session = await this.requireUpload(userId, uploadId, deviceId);
    if (session.status === 'completed') {
      return toUploadDto(session);
    }
    if (session.status !== 'pending' && session.status !== 'uploading') {
      throw uploadNotActive();
    }
    const partCount = expectedPartCount(session);
    if (session.parts.length !== partCount) {
      throw uploadIncomplete(`Expected ${partCount} parts, found ${session.parts.length}`);
    }
    for (let partNumber = 1; partNumber <= partCount; partNumber += 1) {
      const part = session.parts[partNumber - 1];
      if (!part || part.partNumber !== partNumber) {
        throw uploadIncomplete(`Missing upload part ${partNumber}`);
      }
      const expectedSize = expectedPartSize(session, partNumber);
      if (part.size !== expectedSize || !/^[0-9a-f]{64}$/.test(part.sha256)) {
        throw uploadIncomplete(`Part ${partNumber} metadata is inconsistent`);
      }
    }

    if (partCount === 0) {
      await this.deps.objectStore.abortMultipartUpload(
        session.objectId,
        session.providerUploadId,
      );
      await this.deps.objectStore.putEmptyObject(session.objectId);
    } else {
      let completionError: unknown = null;
      try {
        await this.deps.objectStore.completeMultipartUpload(
          session.objectId,
          session.providerUploadId,
          session.parts.map((part) => ({ partNumber: part.partNumber, etag: part.etag })),
        );
      } catch (error) {
        // 网络重试时 S3 可能已完成而 DB 尚未更新；继续读取最终对象验证。
        completionError = error;
      }
      try {
        await this.verifyCompletedObject(session);
      } catch (error) {
        if (isIntegrityVerificationFailure(error)) {
          throw error;
        }
        if (completionError) {
          throw completionError;
        }
        throw error;
      }
      const completed = await this.deps.transfers.markUploadStatus(session.id, 'completed');
      return toUploadDto(completed);
    }

    await this.verifyCompletedObject(session);
    const completed = await this.deps.transfers.markUploadStatus(session.id, 'completed');
    return toUploadDto(completed);
  }

  private async verifyCompletedObject(session: UploadSessionRecord): Promise<void> {
    let content;
    try {
      content = await this.deps.objectStore.getObject(session.objectId);
    } catch (error) {
      if (isIntegrityVerificationFailure(error)) {
        throw error;
      }
      throw new IntegrityReadError(error);
    }
    const hash = createHash('sha256');
    let size = 0n;
    for await (const chunk of content.body) {
      const bytes = Buffer.isBuffer(chunk) ? chunk : Buffer.from(chunk as Uint8Array);
      size += BigInt(bytes.byteLength);
      hash.update(bytes);
    }
    if (size !== session.totalSize || hash.digest('hex') !== session.sha256) {
      await Promise.allSettled([
        this.deps.objectStore.deleteObject(session.objectId),
        this.deps.transfers.markUploadStatus(session.id, 'aborted'),
      ]);
      throw uploadIntegrityMismatch();
    }
  }

  private async requireUpload(
    userId: string,
    uploadId: string,
    deviceId: string,
  ): Promise<UploadSessionRecord> {
    const session = await this.deps.transfers.findUploadById(uploadId);
    if (!session) {
      throw uploadNotFound();
    }
    await this.requireSpaceDevice(userId, session.spaceId, deviceId);
    if (session.deviceId !== deviceId) {
      throw uploadNotFound();
    }
    return session;
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

class IntegrityReadError extends Error {
  constructor(readonly cause: unknown) {
    super('Unable to read completed object');
  }
}

function isIntegrityVerificationFailure(error: unknown): boolean {
  return !(error instanceof IntegrityReadError) &&
    typeof error === 'object' &&
    error !== null &&
    'statusCode' in error &&
    (error as { statusCode?: unknown }).statusCode === 422;
}

function assertSameUpload(
  session: UploadSessionRecord,
  spaceId: string,
  relativePath: string,
  totalSize: bigint,
  partSize: bigint,
  sha256: string,
): void {
  if (
    session.spaceId !== spaceId ||
    session.relativePath !== relativePath ||
    session.totalSize !== totalSize ||
    session.partSize !== partSize ||
    session.sha256 !== sha256
  ) {
    throw idempotencyConflict();
  }
}

function expectedPartCount(session: UploadSessionRecord): number {
  if (session.totalSize === 0n) {
    return 0;
  }
  return Number((session.totalSize + session.partSize - 1n) / session.partSize);
}

function expectedPartSize(session: UploadSessionRecord, partNumber: number): bigint {
  const count = expectedPartCount(session);
  if (!Number.isInteger(partNumber) || partNumber < 1 || partNumber > count) {
    throw validationError(`partNumber must be between 1 and ${count}`);
  }
  const offset = BigInt(partNumber - 1) * session.partSize;
  const remaining = session.totalSize - offset;
  return remaining < session.partSize ? remaining : session.partSize;
}
