import type { ChatTurn, PreviewAudience } from '@/api/types';

const KEY_PREFIX = 'changelog-chat:';
const EXPIRY_MS = 6 * 60 * 60 * 1000;
// Safety cap — cheap insurance against unbounded growth from a bug or scripted flood.
const MAX_STORED_MESSAGES = 100;

interface StoredChat {
  turns: ChatTurn[];
  lastUpdatedAt: number;
}

/** Keyed per user/project/repo/version/audience — switching between versions swaps threads
 * instead of wiping one out. `userId` is 'anon' until Keycloak is wired up. */
function storageKey(project: string, repo: string, version: string, audience: PreviewAudience): string {
  const userId = 'anon';
  return `${KEY_PREFIX}${userId}:${project}:${repo}:${version}:${audience}`;
}

/** Runtime check (not a type assertion) — defends against old bare-`ChatTurn[]` format that
 * crashed the widget when `.turns` was undefined. Anything that isn't a valid StoredChat is
 * treated as if nothing were stored. */
function isStoredChat(value: unknown): value is StoredChat {
  return (
    typeof value === 'object' &&
    value !== null &&
    Array.isArray((value as StoredChat).turns) &&
    typeof (value as StoredChat).lastUpdatedAt === 'number'
  );
}

/** Never throws — missing/corrupt/expired data or private browsing just means starting fresh.
 * Expires 6 hours after last activity (not creation), so active use doesn't lose the thread. */
export function loadChatHistory(project: string, repo: string, version: string, audience: PreviewAudience): ChatTurn[] {
  const key = storageKey(project, repo, version, audience);
  try {
    const raw = localStorage.getItem(key);
    if (!raw) return [];
    const parsed: unknown = JSON.parse(raw);
    if (!isStoredChat(parsed)) {
      localStorage.removeItem(key);
      return [];
    }
    if (Date.now() - parsed.lastUpdatedAt > EXPIRY_MS) {
      localStorage.removeItem(key);
      return [];
    }
    return parsed.turns;
  } catch {
    return [];
  }
}

export function saveChatHistory(
  project: string,
  repo: string,
  version: string,
  audience: PreviewAudience,
  turns: ChatTurn[],
): void {
  try {
    const trimmed = turns.length > MAX_STORED_MESSAGES ? turns.slice(-MAX_STORED_MESSAGES) : turns;
    const stored: StoredChat = { turns: trimmed, lastUpdatedAt: Date.now() };
    localStorage.setItem(storageKey(project, repo, version, audience), JSON.stringify(stored));
  } catch {
    // Storage full or unavailable (private browsing) — chat still works, it just won't persist.
  }
}

/** Explicit reset for the "New conversation" action — lets someone start over now instead of
 * waiting for the 6-hour expiry to kick in on its own. */
export function clearChatHistory(project: string, repo: string, version: string, audience: PreviewAudience): void {
  try {
    localStorage.removeItem(storageKey(project, repo, version, audience));
  } catch {
    // localStorage unavailable — nothing to clear.
  }
}

/** Sweeps all expired conversations (lazy enforcement — localStorage can't run timers while
 * the tab is closed). Called once on widget mount so unused keys don't accumulate forever. */
export function sweepExpiredChatHistory(): void {
  try {
    const now = Date.now();
    const expiredKeys: string[] = [];
    for (let i = 0; i < localStorage.length; i++) {
      const key = localStorage.key(i);
      if (!key || !key.startsWith(KEY_PREFIX)) continue;
      try {
        const parsed: unknown = JSON.parse(localStorage.getItem(key) ?? '');
        // Same legacy-shape check as loadChatHistory — sweep bare-array entries too.
        if (!isStoredChat(parsed) || now - parsed.lastUpdatedAt > EXPIRY_MS) expiredKeys.push(key);
      } catch {
        expiredKeys.push(key); // corrupt entry — safe to drop
      }
    }
    expiredKeys.forEach((key) => localStorage.removeItem(key));
  } catch {
    // localStorage unavailable — nothing to sweep.
  }
}
