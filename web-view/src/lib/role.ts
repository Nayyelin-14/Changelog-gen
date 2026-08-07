const STORAGE_KEY = 'changelog-role';

export type Role = 'dev' | 'qa' | 'business';

export function getStoredRole(): Role | null {
  if (typeof window === 'undefined') return null;
  const raw = localStorage.getItem(STORAGE_KEY);
  if (raw === 'dev' || raw === 'qa' || raw === 'business') return raw;
  return null;
}

export function setStoredRole(role: Role) {
  localStorage.setItem(STORAGE_KEY, role);
}

export function clearStoredRole() {
  localStorage.removeItem(STORAGE_KEY);
}

export function roleHome(role: Role): string {
  if (role === 'dev') return '/dev';
  return '/' + role;
}
