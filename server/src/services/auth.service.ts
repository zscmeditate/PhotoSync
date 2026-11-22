import { hashPassword, randomToken, sha256Hex, verifyPassword } from '../lib/crypto';
import {
  emailTaken,
  invalidCredentials,
  invalidRefreshToken,
} from '../lib/errors';
import type { JwtService } from '../lib/jwt';
import type { RefreshTokenRecord, RefreshTokenRepository, UserRecord, UserRepository } from '../repositories/ports';

export interface UserDto {
  id: string;
  email: string;
  displayName: string;
}

export interface AuthResultDto {
  user: UserDto;
  accessToken: string;
  refreshToken: string;
}

export interface TokenPairDto {
  accessToken: string;
  refreshToken: string;
}

export interface AuthServiceDeps {
  users: UserRepository;
  refreshTokens: RefreshTokenRepository;
  jwt: JwtService;
  /** 刷新令牌有效期（毫秒） */
  refreshTtlMs: number;
  /** 可注入时钟，便于测试 */
  now?: () => Date;
}

export interface RegisterInput {
  email: string;
  password: string;
  displayName: string;
}

export interface LoginInput {
  email: string;
  password: string;
}

function toUserDto(user: UserRecord): UserDto {
  return { id: user.id, email: user.email, displayName: user.displayName };
}

export class AuthService {
  private readonly now: () => Date;

  constructor(private readonly deps: AuthServiceDeps) {
    this.now = deps.now ?? (() => new Date());
  }

  /** 注册：邮箱唯一，冲突抛 409 EMAIL_TAKEN；成功签发 access + refresh */
  async register(input: RegisterInput): Promise<AuthResultDto> {
    const email = input.email.trim().toLowerCase();
    const existing = await this.deps.users.findByEmail(email);
    if (existing) {
      throw emailTaken();
    }
    const user = await this.deps.users.create({
      email,
      passwordHash: await hashPassword(input.password),
      displayName: input.displayName.trim(),
    });
    const tokens = await this.issueTokens(user, null);
    return { user: toUserDto(user), ...tokens };
  }

  /** 登录：失败统一抛 401 INVALID_CREDENTIALS */
  async login(input: LoginInput): Promise<AuthResultDto> {
    const email = input.email.trim().toLowerCase();
    const user = await this.deps.users.findByEmail(email);
    const passwordOk = user ? await verifyPassword(input.password, user.passwordHash) : false;
    if (!user || user.status !== 'active' || !passwordOk) {
      throw invalidCredentials();
    }
    const tokens = await this.issueTokens(user, null);
    return { user: toUserDto(user), ...tokens };
  }

  /** 刷新：校验并轮换 refresh token（旧 token 吊销、签发新的一对） */
  async refresh(refreshToken: string): Promise<TokenPairDto> {
    const record = await this.deps.refreshTokens.findByHash(sha256Hex(refreshToken));
    if (!record || record.revokedAt !== null || record.expiresAt.getTime() <= this.now().getTime()) {
      throw invalidRefreshToken();
    }
    const user = await this.deps.users.findById(record.userId);
    if (!user || user.status !== 'active') {
      throw invalidRefreshToken();
    }
    await this.deps.refreshTokens.revoke(record.id, this.now());
    return this.issueTokens(user, record.deviceId);
  }

  /** 登出：吊销该 refresh token（幂等，对未知 token 也返回成功） */
  async logout(userId: string, refreshToken: string): Promise<void> {
    const record: RefreshTokenRecord | null = await this.deps.refreshTokens.findByHash(
      sha256Hex(refreshToken),
    );
    if (record && record.userId === userId && record.revokedAt === null) {
      await this.deps.refreshTokens.revoke(record.id, this.now());
    }
  }

  private async issueTokens(user: UserRecord, deviceId: string | null): Promise<TokenPairDto> {
    const accessToken = this.deps.jwt.signAccessToken(user.id);
    const refreshToken = randomToken(64);
    await this.deps.refreshTokens.create({
      userId: user.id,
      deviceId,
      tokenHash: sha256Hex(refreshToken),
      expiresAt: new Date(this.now().getTime() + this.deps.refreshTtlMs),
    });
    return { accessToken, refreshToken };
  }
}
