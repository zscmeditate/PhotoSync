/**
 * 统一错误封装。
 * 所有对外错误响应格式固定为：{"error":{"code":"STRING","message":"string"}}
 */

export const ErrorCodes = {
  VALIDATION_ERROR: 'VALIDATION_ERROR',
  PAYLOAD_TOO_LARGE: 'PAYLOAD_TOO_LARGE',
  UNAUTHORIZED: 'UNAUTHORIZED',
  INVALID_CREDENTIALS: 'INVALID_CREDENTIALS',
  INVALID_REFRESH_TOKEN: 'INVALID_REFRESH_TOKEN',
  EMAIL_TAKEN: 'EMAIL_TAKEN',
  DEVICE_NOT_FOUND: 'DEVICE_NOT_FOUND',
  DEVICE_NOT_PRIMARY: 'DEVICE_NOT_PRIMARY',
  DEVICE_NOT_SELF: 'DEVICE_NOT_SELF',
  PRIMARY_DEVICE_TAKEN: 'PRIMARY_DEVICE_TAKEN',
  SPACE_NOT_FOUND: 'SPACE_NOT_FOUND',
  SPACE_FORBIDDEN: 'SPACE_FORBIDDEN',
  NOT_MEMBER: 'NOT_MEMBER',
  ALREADY_MEMBER: 'ALREADY_MEMBER',
  JOIN_REQUEST_NOT_FOUND: 'JOIN_REQUEST_NOT_FOUND',
  JOIN_REQUEST_RESOLVED: 'JOIN_REQUEST_RESOLVED',
  MAPPING_NOT_FOUND: 'MAPPING_NOT_FOUND',
  MAPPING_EXISTS: 'MAPPING_EXISTS',
  INVALID_DEVICE: 'INVALID_DEVICE',
  IDEMPOTENCY_CONFLICT: 'IDEMPOTENCY_CONFLICT',
  UPLOAD_NOT_FOUND: 'UPLOAD_NOT_FOUND',
  UPLOAD_NOT_ACTIVE: 'UPLOAD_NOT_ACTIVE',
  UPLOAD_NOT_COMPLETED: 'UPLOAD_NOT_COMPLETED',
  UPLOAD_INCOMPLETE: 'UPLOAD_INCOMPLETE',
  UPLOAD_INTEGRITY_MISMATCH: 'UPLOAD_INTEGRITY_MISMATCH',
  PART_CONFLICT: 'PART_CONFLICT',
  OPERATION_UNSUPPORTED: 'OPERATION_UNSUPPORTED',
  FILE_ALREADY_EXISTS: 'FILE_ALREADY_EXISTS',
  FILE_NOT_FOUND: 'FILE_NOT_FOUND',
  FILE_IS_DELETED: 'FILE_IS_DELETED',
  VERSION_CONFLICT: 'VERSION_CONFLICT',
  PATH_CONFLICT: 'PATH_CONFLICT',
  CONFLICT_NOT_FOUND: 'CONFLICT_NOT_FOUND',
  CONFLICT_ALREADY_RESOLVED: 'CONFLICT_ALREADY_RESOLVED',
  TRASH_NOT_FOUND: 'TRASH_NOT_FOUND',
  BULK_DELETE_CONFIRMATION_REQUIRED: 'BULK_DELETE_CONFIRMATION_REQUIRED',
  OBJECT_NOT_FOUND: 'OBJECT_NOT_FOUND',
  SYNC_CONFLICT: 'SYNC_CONFLICT',
  NOT_FOUND: 'NOT_FOUND',
  INTERNAL_ERROR: 'INTERNAL_ERROR',
} as const;

export type ErrorCode = (typeof ErrorCodes)[keyof typeof ErrorCodes];

export class AppError extends Error {
  readonly statusCode: number;
  readonly code: ErrorCode | string;

  constructor(statusCode: number, code: ErrorCode | string, message: string) {
    super(message);
    this.name = 'AppError';
    this.statusCode = statusCode;
    this.code = code;
  }

  toJSON(): { error: { code: string; message: string } } {
    return { error: { code: this.code, message: this.message } };
  }
}

export function validationError(message: string): AppError {
  return new AppError(400, ErrorCodes.VALIDATION_ERROR, message);
}

export function unauthorized(message = 'Authentication required'): AppError {
  return new AppError(401, ErrorCodes.UNAUTHORIZED, message);
}

export function invalidCredentials(): AppError {
  return new AppError(401, ErrorCodes.INVALID_CREDENTIALS, 'Invalid email or password');
}

export function invalidRefreshToken(): AppError {
  return new AppError(401, ErrorCodes.INVALID_REFRESH_TOKEN, 'Refresh token is invalid or expired');
}

export function emailTaken(): AppError {
  return new AppError(409, ErrorCodes.EMAIL_TAKEN, 'Email is already registered');
}

export function deviceNotFound(): AppError {
  return new AppError(404, ErrorCodes.DEVICE_NOT_FOUND, 'Device not found');
}

/** 当前设备不是主设备，无权执行主设备专属操作 */
export function deviceNotPrimary(): AppError {
  return new AppError(
    403,
    ErrorCodes.DEVICE_NOT_PRIMARY,
    'Only the primary device can perform this operation',
  );
}

/** 只能操作本机设备（例如重命名），不能操作他人设备 */
export function deviceNotSelf(): AppError {
  return new AppError(403, ErrorCodes.DEVICE_NOT_SELF, 'Can only modify the current device');
}

/** 账号下已有其它主设备，不能再抢占 */
export function primaryDeviceTaken(): AppError {
  return new AppError(
    409,
    ErrorCodes.PRIMARY_DEVICE_TAKEN,
    'Another device is already the primary device',
  );
}

export function spaceNotFound(): AppError {
  return new AppError(404, ErrorCodes.SPACE_NOT_FOUND, 'Space not found');
}

export function spaceForbidden(): AppError {
  return new AppError(403, ErrorCodes.SPACE_FORBIDDEN, 'Space access forbidden');
}

export function notMember(statusCode = 409): AppError {
  return new AppError(statusCode, ErrorCodes.NOT_MEMBER, 'Device is not a space member');
}

export function alreadyMember(): AppError {
  return new AppError(409, ErrorCodes.ALREADY_MEMBER, 'Device is already a member of this space');
}

export function joinRequestNotFound(): AppError {
  return new AppError(404, ErrorCodes.JOIN_REQUEST_NOT_FOUND, 'Join request not found');
}

export function joinRequestResolved(): AppError {
  return new AppError(
    409,
    ErrorCodes.JOIN_REQUEST_RESOLVED,
    'Join request has already been resolved',
  );
}

export function mappingNotFound(): AppError {
  return new AppError(404, ErrorCodes.MAPPING_NOT_FOUND, 'Mapping not found');
}

export function mappingExists(): AppError {
  return new AppError(
    409,
    ErrorCodes.MAPPING_EXISTS,
    'This device already has a folder mapping in the space',
  );
}

export function invalidDevice(): AppError {
  return new AppError(400, ErrorCodes.INVALID_DEVICE, 'Device must belong to the user and be active');
}

export function idempotencyConflict(message = 'Idempotency key was used with different parameters'): AppError {
  return new AppError(409, ErrorCodes.IDEMPOTENCY_CONFLICT, message);
}

export function uploadNotFound(): AppError {
  return new AppError(404, ErrorCodes.UPLOAD_NOT_FOUND, 'Upload session not found');
}

export function uploadNotActive(message = 'Upload session is not active'): AppError {
  return new AppError(409, ErrorCodes.UPLOAD_NOT_ACTIVE, message);
}

export function uploadNotCompleted(): AppError {
  return new AppError(409, ErrorCodes.UPLOAD_NOT_COMPLETED, 'Upload session is not completed');
}

export function uploadIncomplete(message = 'Upload parts are incomplete'): AppError {
  return new AppError(409, ErrorCodes.UPLOAD_INCOMPLETE, message);
}

export function uploadIntegrityMismatch(): AppError {
  return new AppError(
    422,
    ErrorCodes.UPLOAD_INTEGRITY_MISMATCH,
    'Completed object size or SHA-256 does not match the upload session',
  );
}

export function partConflict(): AppError {
  return new AppError(409, ErrorCodes.PART_CONFLICT, 'Upload part already exists with different content');
}

export function operationUnsupported(): AppError {
  return new AppError(400, ErrorCodes.OPERATION_UNSUPPORTED, 'Only file_created operations are supported');
}

export function fileAlreadyExists(): AppError {
  return new AppError(409, ErrorCodes.FILE_ALREADY_EXISTS, 'A file already exists at this path');
}

export function fileNotFound(): AppError {
  return new AppError(404, ErrorCodes.FILE_NOT_FOUND, 'File not found');
}

export function fileIsDeleted(): AppError {
  return new AppError(409, ErrorCodes.FILE_IS_DELETED, 'File has been deleted');
}

export function versionConflict(currentVersion: number): AppError {
  return new AppError(
    409,
    ErrorCodes.VERSION_CONFLICT,
    `File version mismatch: current version is ${currentVersion}`,
  );
}

export function pathConflict(): AppError {
  return new AppError(409, ErrorCodes.PATH_CONFLICT, 'Target path is already occupied by an active file');
}

export function conflictNotFound(): AppError {
  return new AppError(404, ErrorCodes.CONFLICT_NOT_FOUND, 'Conflict not found');
}

export function conflictAlreadyResolved(): AppError {
  return new AppError(409, ErrorCodes.CONFLICT_ALREADY_RESOLVED, 'Conflict has already been resolved');
}

export function trashNotFound(): AppError {
  return new AppError(404, ErrorCodes.TRASH_NOT_FOUND, 'Trash item not found');
}

export function bulkDeleteConfirmationRequired(threshold: number): AppError {
  return new AppError(
    409,
    ErrorCodes.BULK_DELETE_CONFIRMATION_REQUIRED,
    `Deletion exceeds the bulk-delete protection threshold (${threshold}); retry with confirmBulkDelete=true`,
  );
}

export function objectNotFound(): AppError {
  return new AppError(404, ErrorCodes.OBJECT_NOT_FOUND, 'Object not found');
}

export function syncConflict(message = 'Concurrent sync transaction conflict; retry the operation'): AppError {
  return new AppError(409, ErrorCodes.SYNC_CONFLICT, message);
}

export function notFound(message = 'Resource not found'): AppError {
  return new AppError(404, ErrorCodes.NOT_FOUND, message);
}

export function internalError(message = 'Internal server error'): AppError {
  return new AppError(500, ErrorCodes.INTERNAL_ERROR, message);
}
