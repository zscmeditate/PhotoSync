import { describe, expect, it } from 'vitest';
import { sha256Hex } from '../src/lib/crypto';
import { AppError } from '../src/lib/errors';
import { JwtService } from '../src/lib/jwt';
import { AuthService } from '../src/services/auth.service';
import { FakeRefreshTokenRepository, FakeUserRepository } from './fakes';

const JWT_SECRET = 'test-secret-0123456789abcdef';
const REFRESH_TTL_MS = 30 * 24 * 60 * 60 * 1000;

function makeService() {
  const users = new FakeUserRepository();
  const refreshTokens = new FakeRefreshTokenRepository();
  const jwt = new JwtService(JWT_SECRET, '15m');
  const auth = new AuthService({
    users,
    refreshTokens,
    jwt,
    refreshTtlMs: REFRESH_TTL_MS,
  });
  return { auth, users, refreshTokens, jwt };
}

const registerInput = {
  email: 'Alice@Example.com',
  password: 'password123',
  displayName: 'Alice',
};

async function expectAppError(fn: () => Promise<unknown>, statusCode: number, code: string) {
  try {
    await fn();
    expect.unreachable('expected AppError to be thrown');
  } catch (err) {
    expect(err).toBeInstanceOf(AppError);
    expect((err as AppError).statusCode).toBe(statusCode);
    expect((err as AppError).code).toBe(code);
  }
}

describe('AuthService.register', () => {
  it('registers a user and issues tokens', async () => {
    const { auth, users, jwt } = makeService();
    const result = await auth.register(registerInput);

    expect(result.user.email).toBe('alice@example.com');
    expect(result.user.displayName).toBe('Alice');
    expect(result.user.id).toBeTypeOf('string');
    expect(result.accessToken).toBeTypeOf('string');
    expect(result.refreshToken).toBeTypeOf('string');

    const stored = users.items.get(result.user.id);
    expect(stored?.passwordHash).toMatch(/^scrypt\$16384\$8\$1\$/);
    expect(stored?.passwordHash).not.toContain('password123');

    const payload = jwt.verifyAccessToken(result.accessToken);
    expect(payload.userId).toBe(result.user.id);
  });

  it('rejects duplicate email with EMAIL_TAKEN', async () => {
    const { auth } = makeService();
    await auth.register(registerInput);
    await expectAppError(
      () => auth.register({ ...registerInput, email: 'alice@example.com' }),
      409,
      'EMAIL_TAKEN',
    );
  });
});

describe('AuthService.login', () => {
  it('logs in with correct credentials', async () => {
    const { auth, jwt } = makeService();
    await auth.register(registerInput);
    const result = await auth.login({ email: 'alice@example.com', password: 'password123' });

    expect(result.user.email).toBe('alice@example.com');
    expect(jwt.verifyAccessToken(result.accessToken).userId).toBe(result.user.id);
  });

  it('rejects wrong password with INVALID_CREDENTIALS', async () => {
    const { auth } = makeService();
    await auth.register(registerInput);
    await expectAppError(
      () => auth.login({ email: 'alice@example.com', password: 'wrong-pass' }),
      401,
      'INVALID_CREDENTIALS',
    );
  });

  it('rejects unknown email with INVALID_CREDENTIALS', async () => {
    const { auth } = makeService();
    await expectAppError(
      () => auth.login({ email: 'nobody@example.com', password: 'password123' }),
      401,
      'INVALID_CREDENTIALS',
    );
  });
});

describe('AuthService.refresh', () => {
  it('rotates refresh tokens and revokes the old one', async () => {
    const { auth, refreshTokens, jwt } = makeService();
    const registered = await auth.register(registerInput);

    const rotated = await auth.refresh(registered.refreshToken);
    expect(rotated.refreshToken).not.toBe(registered.refreshToken);
    expect(rotated.accessToken).toBeTypeOf('string');
    expect(jwt.verifyAccessToken(rotated.accessToken).userId).toBe(registered.user.id);

    // 旧 token 已吊销，不能再用
    await expectAppError(() => auth.refresh(registered.refreshToken), 401, 'INVALID_REFRESH_TOKEN');
    // 新 token 可用
    const again = await auth.refresh(rotated.refreshToken);
    expect(again.refreshToken).not.toBe(rotated.refreshToken);

    const revokedCount = [...refreshTokens.items.values()].filter((t) => t.revokedAt !== null).length;
    expect(revokedCount).toBeGreaterThanOrEqual(2);
  });

  it('rejects unknown refresh token', async () => {
    const { auth } = makeService();
    await expectAppError(() => auth.refresh('not-a-real-token'), 401, 'INVALID_REFRESH_TOKEN');
  });

  it('rejects expired refresh token', async () => {
    const { auth, refreshTokens } = makeService();
    const registered = await auth.register(registerInput);

    const record = await refreshTokens.findByHash(sha256Hex(registered.refreshToken));
    expect(record).not.toBeNull();
    refreshTokens.items.set(record!.id, {
      ...record!,
      expiresAt: new Date(Date.now() - 1000),
    });

    await expectAppError(() => auth.refresh(registered.refreshToken), 401, 'INVALID_REFRESH_TOKEN');
  });
});

describe('AuthService.logout', () => {
  it('revokes the refresh token so it can no longer be used', async () => {
    const { auth } = makeService();
    const registered = await auth.register(registerInput);

    await auth.logout(registered.user.id, registered.refreshToken);
    await expectAppError(() => auth.refresh(registered.refreshToken), 401, 'INVALID_REFRESH_TOKEN');
  });

  it('does not revoke tokens belonging to another user', async () => {
    const { auth } = makeService();
    const alice = await auth.register(registerInput);
    const bob = await auth.register({
      email: 'bob@example.com',
      password: 'password456',
      displayName: 'Bob',
    });

    // bob 尝试吊销 alice 的 token：无权操作，token 仍然有效
    await auth.logout(bob.user.id, alice.refreshToken);
    const rotated = await auth.refresh(alice.refreshToken);
    expect(rotated.refreshToken).toBeTypeOf('string');
  });

  it('is idempotent for unknown tokens', async () => {
    const { auth } = makeService();
    const registered = await auth.register(registerInput);
    await expect(auth.logout(registered.user.id, 'unknown-token')).resolves.toBeUndefined();
  });
});
