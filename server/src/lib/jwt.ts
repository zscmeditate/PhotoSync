import jwt from 'jsonwebtoken';
import { unauthorized } from './errors';

/**
 * 访问令牌（JWT HS256，默认 15 分钟过期）。
 * payload：{ sub: userId, type: 'access' }
 */
export class JwtService {
  constructor(
    private readonly secret: string,
    private readonly ttl: string = '15m',
  ) {}

  signAccessToken(userId: string): string {
    return jwt.sign({ type: 'access' }, this.secret, {
      algorithm: 'HS256',
      subject: userId,
      expiresIn: this.ttl as jwt.SignOptions['expiresIn'],
    });
  }

  /** 校验并解析访问令牌，失败抛出 401 UNAUTHORIZED */
  verifyAccessToken(token: string): { userId: string } {
    let payload: unknown;
    try {
      payload = jwt.verify(token, this.secret, { algorithms: ['HS256'] });
    } catch {
      throw unauthorized('Invalid or expired access token');
    }
    if (
      typeof payload !== 'object' ||
      payload === null ||
      typeof (payload as { sub?: unknown }).sub !== 'string' ||
      (payload as { type?: unknown }).type !== 'access'
    ) {
      throw unauthorized('Invalid or expired access token');
    }
    return { userId: (payload as { sub: string }).sub };
  }
}
