const STORAGE_KEY = 'changelog-provider';

export type Provider = 'azure' | 'github';

export function getStoredProvider(): Provider {
  if (typeof window === 'undefined') return 'azure';
  const raw = localStorage.getItem(STORAGE_KEY);
  return raw === 'github' ? 'github' : 'azure';
}
export function setStoredProvider(provider: Provider): void {
  localStorage.setItem(STORAGE_KEY, provider);
}
export function clearStoredProvider(): void {
  localStorage.removeItem(STORAGE_KEY);
}

/** The backend API base path for a provider. Azure rides the root (/api via the axios baseURL);
 * GitHub is mounted under /github so its own rest routes never collide with Azure's. */
export function providerApiBase(provider: Provider): string {
  return provider === 'github' ? '/api/github' : '/api';
}