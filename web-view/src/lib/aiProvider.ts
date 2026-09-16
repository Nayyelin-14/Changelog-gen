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