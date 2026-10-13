import { ApiError } from '@/api/client';

/**
 * Maps an API failure to safe, actionable copy for mutation surfaces (edit / restore /
 * revision-delete). Backend 401/403/404 bodies are terse codes ("not_authenticated") or
 * ownership prose aimed at logs — neither belongs in a dialog. Any other status keeps the
 * server's own message, so specific validation errors ("nothing to restore") still surface.
 */
export function friendlyApiError(error: unknown, fallback: string): string {
  if (error instanceof ApiError) {
    if (error.status === 401) return 'Your session has expired. Please sign in again.';
    if (error.status === 403)
      return "You don't have permission to modify this changelog — it may belong to another account.";
    if (error.status === 404) return 'This changelog entry could not be found — it may have been deleted.';
  }
  return error instanceof Error && error.message ? error.message : fallback;
}
