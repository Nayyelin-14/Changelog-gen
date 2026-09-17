const AI_PROVIDER_KEY = 'ai-provider';

export const DEFAULT_AI_PROVIDER = 'nvidia';

/** Last AI provider the user picked in the UI. Admin-configured API keys live server-side. */
export function getStoredAiProvider(): string {
  try {
    return localStorage.getItem(AI_PROVIDER_KEY) || DEFAULT_AI_PROVIDER;
  } catch {
    return DEFAULT_AI_PROVIDER;
  }
}

export function setStoredAiProvider(provider: string): void {
  try {
    localStorage.setItem(AI_PROVIDER_KEY, provider);
  } catch {
    // storage unavailable (private mode) — the request just carries the id this session
  }
}

/**
 * The localStorage value can outlive the admin's enabled set (a provider gets its key revoked,
 * or an old build's picker choice persists). Every AI call must use a provider the server
 * actually serves — otherwise the backend rejects it with "Unknown AI provider" and generation
 * fails. Returns `stored` when it's on the enabled list, otherwise the first enabled provider.
 */
export function resolveAiProvider(
  stored: string,
  enabled: string[],
): string {
  if (enabled.length > 0 && enabled.includes(stored)) return stored;
  // Unknown set (still loading, or nothing enabled yet) — keep the stored value rather than
  // guessing; persisting a guess would clobber a valid choice before the list arrives.
  if (enabled.length === 0) return stored;
  return enabled[0];
}

/** Persists the stored provider once a fetched enabled set says it's stale — so every
 * {@link getStoredAiProvider()} fallback (chat, SSE, generate) sends a provider the server
 * actually serves, even on pages that don't own the picker. */
export function ensureStoredAiProviderValid(enabled: string[]): string {
  const stored = getStoredAiProvider();
  const resolved = resolveAiProvider(stored, enabled);
  if (resolved !== stored) setStoredAiProvider(resolved);
  return resolved;
}