import { rootApiClient, ApiError } from '@/api/client';
import type { AuthUser } from '@/api/types';

/** The identity the current browser session is acting as — the signed-in GitHub user, or null
 * when the shared service account is in play (no sign-in yet). `Unauthorized` and the
 * not-configured case both mean "anonymous". */
export async function getAuthMe(): Promise<AuthUser | null> {
  try {
    const { data } = await rootApiClient.get<AuthUser>('/auth/me');
    return data;
  } catch (error) {
    if (error instanceof ApiError && error.status === 401) return null;
    throw error;
  }
}

/**
 * Starts the GitHub OAuth dance as a full-page navigation (the server answers 302 to
 * github.com; the callback eventually lands back on {@code /#/{next}}). The cookie that carries
 * the CSRF state is HttpOnly, so this must be a browser navigation — not an XHR.
 */
export function buildGithubSignInUrl(next: string): string {
  return `/api/auth/github/authorize?next=${encodeURIComponent(next)}`;
}

/**
 * Starts the {@code mode=connect} OAuth flow — for a user who is already signed in and wants to
 * attach a (possibly different) GitHub account to the same app account, e.g. after a disconnect.
 * The server refuses (with {@code error=github_linked_elsewhere}) if that GitHub identity already
 * belongs to a different app account; it never merges two accounts.
 */
export function buildGithubConnectUrl(next: string): string {
  return `/api/auth/github/authorize?next=${encodeURIComponent(next)}&mode=connect`;
}

/**
 * Disconnects GitHub: revokes the OAuth token and erases it server-side, but keeps the app
 * account and every changelog it owns. The session survives, so the user stays signed in —
 * just without GitHub capabilities until they reconnect.
 */
export async function disconnectGithub(): Promise<void> {
  await rootApiClient.post('/auth/github/disconnect');
}

export async function signOut(): Promise<void> {
  await rootApiClient.post('/auth/logout');
}

/** Deletes ONLY the stored sign-in credentials (GitHub token revoked server-side); generated
 * changelogs and history are never touched. */
export async function deleteGithubAccount(): Promise<void> {
  await rootApiClient.delete('/auth/github/account');
}