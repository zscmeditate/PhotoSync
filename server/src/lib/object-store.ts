import { Readable } from 'node:stream';
import {
  AbortMultipartUploadCommand,
  CompleteMultipartUploadCommand,
  CreateBucketCommand,
  CreateMultipartUploadCommand,
  DeleteObjectCommand,
  GetObjectCommand,
  HeadBucketCommand,
  PutObjectCommand,
  S3Client,
  UploadPartCommand,
  type CompletedPart,
} from '@aws-sdk/client-s3';
import type { Config } from '../config';

export interface ObjectContent {
  body: Readable;
  contentLength?: number;
}

/** 元数据仍存在但对象存储中的内容已丢失（如存储被清理或损坏）。 */
export class StorageObjectMissingError extends Error {
  constructor(objectId: string) {
    super(`Storage object ${objectId} not found`);
    this.name = 'StorageObjectMissingError';
  }
}

export interface ObjectStore {
  ensureBucket(): Promise<void>;
  createMultipartUpload(objectId: string): Promise<string>;
  abortMultipartUpload(objectId: string, providerUploadId: string): Promise<void>;
  uploadPart(input: {
    objectId: string;
    providerUploadId: string;
    partNumber: number;
    body: Buffer;
  }): Promise<string>;
  completeMultipartUpload(
    objectId: string,
    providerUploadId: string,
    parts: Array<{ partNumber: number; etag: string }>,
  ): Promise<void>;
  putEmptyObject(objectId: string): Promise<void>;
  getObject(objectId: string): Promise<ObjectContent>;
  deleteObject(objectId: string): Promise<void>;
}

/** 生产对象存储：真实 S3/MinIO API，不使用文件系统或内存替代。 */
export class S3ObjectStore implements ObjectStore {
  private readonly client: S3Client;
  private readonly bucket: string;

  constructor(config: Config) {
    this.bucket = config.S3_BUCKET;
    this.client = new S3Client({
      endpoint: config.S3_ENDPOINT,
      region: config.S3_REGION,
      forcePathStyle: config.S3_FORCE_PATH_STYLE,
      credentials: {
        accessKeyId: config.S3_ACCESS_KEY,
        secretAccessKey: config.S3_SECRET_KEY,
      },
    });
  }

  async ensureBucket(): Promise<void> {
    try {
      await this.client.send(new HeadBucketCommand({ Bucket: this.bucket }));
      return;
    } catch (error) {
      if (!isStorageError(error, ['NotFound', 'NoSuchBucket'], 404)) {
        throw error;
      }
    }
    try {
      await this.client.send(new CreateBucketCommand({ Bucket: this.bucket }));
    } catch (error) {
      if (!isStorageError(error, ['BucketAlreadyOwnedByYou', 'BucketAlreadyExists'])) {
        throw error;
      }
    }
  }

  async createMultipartUpload(objectId: string): Promise<string> {
    const result = await this.client.send(
      new CreateMultipartUploadCommand({ Bucket: this.bucket, Key: objectKey(objectId) }),
    );
    if (!result.UploadId) {
      throw new Error('S3 CreateMultipartUpload did not return an UploadId');
    }
    return result.UploadId;
  }

  async abortMultipartUpload(objectId: string, providerUploadId: string): Promise<void> {
    try {
      await this.client.send(
        new AbortMultipartUploadCommand({
          Bucket: this.bucket,
          Key: objectKey(objectId),
          UploadId: providerUploadId,
        }),
      );
    } catch (error) {
      if (!isStorageError(error, ['NoSuchUpload'], 404)) {
        throw error;
      }
    }
  }

  async uploadPart(input: {
    objectId: string;
    providerUploadId: string;
    partNumber: number;
    body: Buffer;
  }): Promise<string> {
    const result = await this.client.send(
      new UploadPartCommand({
        Bucket: this.bucket,
        Key: objectKey(input.objectId),
        UploadId: input.providerUploadId,
        PartNumber: input.partNumber,
        Body: input.body,
        ContentLength: input.body.byteLength,
      }),
    );
    if (!result.ETag) {
      throw new Error('S3 UploadPart did not return an ETag');
    }
    return result.ETag;
  }

  async completeMultipartUpload(
    objectId: string,
    providerUploadId: string,
    parts: Array<{ partNumber: number; etag: string }>,
  ): Promise<void> {
    const completedParts: CompletedPart[] = parts.map((part) => ({
      PartNumber: part.partNumber,
      ETag: part.etag,
    }));
    await this.client.send(
      new CompleteMultipartUploadCommand({
        Bucket: this.bucket,
        Key: objectKey(objectId),
        UploadId: providerUploadId,
        MultipartUpload: { Parts: completedParts },
      }),
    );
  }

  async putEmptyObject(objectId: string): Promise<void> {
    await this.client.send(
      new PutObjectCommand({
        Bucket: this.bucket,
        Key: objectKey(objectId),
        Body: Buffer.alloc(0),
        ContentLength: 0,
      }),
    );
  }

  async getObject(objectId: string): Promise<ObjectContent> {
    try {
      const result = await this.client.send(
        new GetObjectCommand({ Bucket: this.bucket, Key: objectKey(objectId) }),
      );
      if (!result.Body) {
        throw new Error(`S3 object ${objectId} has no response body`);
      }
      const body =
        result.Body instanceof Readable
          ? result.Body
          : Readable.from(result.Body as AsyncIterable<Uint8Array>);
      return { body, contentLength: result.ContentLength };
    } catch (error) {
      if (isStorageError(error, ['NoSuchKey', 'NotFound'], 404)) {
        throw new StorageObjectMissingError(objectId);
      }
      throw error;
    }
  }

  async deleteObject(objectId: string): Promise<void> {
    await this.client.send(
      new DeleteObjectCommand({ Bucket: this.bucket, Key: objectKey(objectId) }),
    );
  }
}

function objectKey(objectId: string): string {
  return `objects/${objectId}`;
}

function isStorageError(error: unknown, names: string[], status?: number): boolean {
  if (typeof error !== 'object' || error === null) {
    return false;
  }
  const candidate = error as {
    name?: string;
    Code?: string;
    $metadata?: { httpStatusCode?: number };
  };
  return (
    (candidate.name !== undefined && names.includes(candidate.name)) ||
    (candidate.Code !== undefined && names.includes(candidate.Code)) ||
    (status !== undefined && candidate.$metadata?.httpStatusCode === status)
  );
}
