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
    // GitHub-backed routes answer this when the user disconnected GitHub — the shell blocks on it,
    // but a request already in flight can still land here. Say what to do, not "no permission".
    if (error.status === 403 && error.code === 'github_not_connected')
      return 'GitHub is disconnected — reconnect it to fetch repository data.';
    if (error.status === 403)
      return "You don't have permission to modify this changelog — it may belong to another account.";
    if (error.status === 404) return 'This changelog entry could not be found — it may have been deleted.';
    if (error.status === 503)
      return 'The service is temporarily unavailable. Please try again in a moment.';
    return error.message || fallback;
  }
  return error instanceof Error && error.message ? error.message : fallback;
}
