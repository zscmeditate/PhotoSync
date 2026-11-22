import { z } from 'zod';
import { validationError } from './errors';

/** zod 手写校验：失败统一抛出 400 VALIDATION_ERROR */
export function parse<T extends z.ZodTypeAny>(schema: T, data: unknown): z.infer<T> {
  const result = schema.safeParse(data);
  if (!result.success) {
    const issue = result.error.issues[0];
    const path = issue && issue.path.length > 0 ? issue.path.join('.') : 'body';
    throw validationError(issue ? `${path}: ${issue.message}` : 'Invalid request');
  }
  return result.data;
}
