/**
 * OpenAPI / Fastify 路由 schema（用于 /docs 文档与基础请求校验）。
 * 业务级精确校验（邮箱格式、长度等）在 handler 中用 zod 完成。
 */

function errorResponse(description: string) {
  return {
    description,
    type: 'object',
    required: ['error'],
    properties: {
      error: {
        type: 'object',
        required: ['code', 'message'],
        properties: {
          code: { type: 'string', description: '稳定的机器可读错误码' },
          message: { type: 'string', description: '人类可读错误描述' },
        },
      },
    },
  } as const;
}

export const errorResponses = {
  400: errorResponse('参数校验失败'),
  401: errorResponse('未认证或令牌无效'),
  403: errorResponse('无权访问该资源'),
  404: errorResponse('资源不存在'),
  409: errorResponse('资源冲突'),
  413: errorResponse('上传分片超过大小限制'),
  422: errorResponse('上传内容完整性校验失败'),
  500: errorResponse('服务器内部错误'),
};

export const userSchema = {
  type: 'object',
  required: ['id', 'email', 'displayName'],
  additionalProperties: true,
  properties: {
    id: { type: 'string', description: '用户 UUID' },
    email: { type: 'string' },
    displayName: { type: 'string' },
  },
} as const;

export const authResultSchema = {
  type: 'object',
  required: ['user', 'accessToken', 'refreshToken'],
  additionalProperties: true,
  properties: {
    user: userSchema,
    accessToken: { type: 'string' },
    refreshToken: { type: 'string' },
  },
} as const;

export const tokenPairSchema = {
  type: 'object',
  required: ['accessToken', 'refreshToken'],
  additionalProperties: true,
  properties: {
    accessToken: { type: 'string' },
    refreshToken: { type: 'string' },
  },
} as const;

export const deviceSchema = {
  type: 'object',
  required: [
    'id',
    'userId',
    'deviceName',
    'platform',
    'credentialStatus',
    'isPrimary',
    'isRejoinRequested',
    'createdAt',
    'lastSeenAt',
  ],
  additionalProperties: true,
  properties: {
    id: { type: 'string', description: '设备 UUID' },
    userId: { type: 'string' },
    deviceName: { type: 'string' },
    platform: { type: 'string', enum: ['android'], description: 'Phase 1 固定 android' },
    credentialStatus: { type: 'string', enum: ['active', 'revoked'] },
    isPrimary: { type: 'boolean', description: '是否为当前账号的主设备' },
    isRejoinRequested: {
      type: 'boolean',
      description: '被移除设备是否正在等待主设备同意重新加入',
    },
    createdAt: { type: 'string', description: 'ISO8601' },
    lastSeenAt: {
      anyOf: [{ type: 'string' }, { type: 'null' }],
      description: 'ISO8601 或 null',
    },
  },
} as const;

export const spaceSchema = {
  type: 'object',
  required: [
    'id',
    'ownerId',
    'name',
    'description',
    'revision',
    'role',
    'memberCount',
    'mappingCount',
    'createdAt',
    'updatedAt',
  ],
  additionalProperties: true,
  properties: {
    id: { type: 'string', description: '空间 UUID' },
    ownerId: { type: 'string', description: '所有者用户 UUID' },
    name: { type: 'string' },
    description: { anyOf: [{ type: 'string' }, { type: 'null' }] },
    coverUpdatedAt: {
      anyOf: [{ type: 'string' }, { type: 'null' }],
      description: '空间头像最近更新时间（ISO8601）；null 表示未设置头像',
    },
    revision: { type: 'string', description: '十进制事件序号' },
    role: {
      type: 'string',
      enum: ['owner', 'admin', 'member', 'viewer', 'none'],
      description: 'none 表示该设备尚未加入该空间',
    },
    memberCount: { type: 'integer' },
    mappingCount: { type: 'integer' },
    createdAt: { type: 'string', description: 'ISO8601' },
    updatedAt: { type: 'string', description: 'ISO8601' },
  },
} as const;

export const memberSchema = {
  type: 'object',
  required: ['deviceId', 'deviceName', 'platform', 'role', 'joinedAt', 'lastSeenAt', 'online'],
  additionalProperties: true,
  properties: {
    deviceId: { type: 'string' },
    deviceName: { type: 'string' },
    platform: { type: 'string' },
    role: { type: 'string', enum: ['owner', 'admin', 'member', 'viewer'] },
    joinedAt: { type: 'string', description: 'ISO8601' },
    lastSeenAt: { anyOf: [{ type: 'string' }, { type: 'null' }] },
    online: { type: 'boolean' },
  },
} as const;

export const joinRequestSchema = {
  type: 'object',
  required: [
    'id',
    'spaceId',
    'deviceId',
    'deviceName',
    'platform',
    'status',
    'requestedAt',
    'resolvedAt',
  ],
  additionalProperties: true,
  properties: {
    id: { type: 'string' },
    spaceId: { type: 'string' },
    deviceId: { type: 'string' },
    deviceName: { type: 'string' },
    platform: { type: 'string' },
    status: { type: 'string', enum: ['pending', 'approved', 'rejected'] },
    requestedAt: { type: 'string', description: 'ISO8601' },
    resolvedAt: { anyOf: [{ type: 'string' }, { type: 'null' }] },
  },
} as const;

export const mappingSchema = {
  type: 'object',
  required: [
    'id',
    'spaceId',
    'deviceId',
    'localPathLabel',
    'onlyMedia',
    'createdAt',
  ],
  additionalProperties: true,
  properties: {
    id: { type: 'string' },
    spaceId: { type: 'string' },
    deviceId: { type: 'string' },
    localPathLabel: { type: 'string' },
    treeUri: { anyOf: [{ type: 'string' }, { type: 'null' }] },
    onlyMedia: { type: 'boolean' },
    createdAt: { type: 'string', description: 'ISO8601' },
  },
} as const;

export const uploadSchema = {
  type: 'object',
  required: [
    'uploadId',
    'spaceId',
    'objectId',
    'relativePath',
    'size',
    'sha256',
    'partSize',
    'status',
    'uploadedParts',
  ],
  additionalProperties: false,
  properties: {
    uploadId: { type: 'string' },
    spaceId: { type: 'string' },
    objectId: { type: 'string', description: 'UUID；下载 URL 中不包含对象存储 key 的斜杠' },
    relativePath: { type: 'string' },
    size: { type: 'string', pattern: '^[0-9]+$' },
    sha256: { type: 'string', pattern: '^[0-9a-f]{64}$' },
    partSize: { type: 'string', pattern: '^[0-9]+$' },
    status: { type: 'string', enum: ['pending', 'uploading', 'completed', 'aborted'] },
    uploadedParts: {
      type: 'array',
      items: {
        type: 'object',
        required: ['partNumber', 'size', 'sha256'],
        additionalProperties: false,
        properties: {
          partNumber: { type: 'integer', minimum: 1 },
          size: { type: 'string', pattern: '^[0-9]+$' },
          sha256: { type: 'string', pattern: '^[0-9a-f]{64}$' },
        },
      },
    },
  },
} as const;

export const uploadResponseSchema = {
  type: 'object',
  required: ['upload'],
  additionalProperties: false,
  properties: { upload: uploadSchema },
} as const;

export const fileDtoSchema = {
  type: 'object',
  required: [
    'fileId',
    'spaceId',
    'relativePath',
    'fileName',
    'objectId',
    'size',
    'sha256',
    'version',
    'lastModifiedByDeviceId',
    'createdAt',
    'updatedAt',
    'isDeleted',
  ],
  additionalProperties: false,
  properties: {
    fileId: { type: 'string' },
    spaceId: { type: 'string' },
    relativePath: { type: 'string' },
    fileName: { type: 'string' },
    objectId: { type: 'string' },
    size: { type: 'string', pattern: '^[0-9]+$' },
    sha256: { type: 'string', pattern: '^[0-9a-f]{64}$' },
    version: { type: 'integer', minimum: 1 },
    lastModifiedByDeviceId: { type: 'string' },
    createdAt: { type: 'string' },
    updatedAt: { type: 'string' },
    isDeleted: { type: 'boolean', description: '软删除标记；true 表示文件已删除但对象与历史版本保留' },
  },
} as const;

export const eventDtoSchema = {
  type: 'object',
  required: ['revision', 'type', 'deviceId', 'createdAt', 'payload'],
  additionalProperties: false,
  properties: {
    revision: { type: 'string', pattern: '^[0-9]+$' },
    type: {
      type: 'string',
      enum: ['file_created', 'file_updated', 'file_deleted', 'file_renamed'],
    },
    deviceId: { type: 'string' },
    createdAt: { type: 'string' },
    payload: {
      type: 'object',
      additionalProperties: true,
      description:
        '事件负载按 type 区分：file_created/file_updated 含 objectId/size/sha256；file_deleted 不含对象字段；file_renamed 含 oldPath/newPath',
    },
  },
} as const;

export const bearerSecurity = [{ bearerAuth: [] }];
