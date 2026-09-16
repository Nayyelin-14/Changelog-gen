import { useCallback, useEffect, useMemo, useState } from 'react';
import { useLocation, useNavigate, useParams } from 'react-router-dom';
import {
  Briefcase,
  CheckCircle2,
  ChevronDown,
  ChevronRight,
  GitBranch,
  LogIn,
  LogOut,
  Moon,
  Sparkles,
  Sun,
  Terminal,
  Trash2,
} from 'lucide-react';

import { useAuth } from '@/api/AuthContext';
import { ConfirmDialog } from '@/components/ConfirmDialog';
import { Button } from '@/components/ui/button';
import { getStoredRole, setStoredRole, type Role } from '@/lib/role';
import { getStoredProvider, setStoredProvider, type Provider } from '@/lib/provider';
import { cn } from '@/lib/utils';

function useTheme() {
  const [dark, setDark] = useState(() => document.documentElement.classList.contains('dark'));

  useEffect(() => {
    const observer = new MutationObserver(() => {
      setDark(document.documentElement.classList.contains('dark'));
    });
    observer.observe(document.documentElement, { attributes: true, attributeFilter: ['class'] });
    return () => observer.disconnect();
  }, []);

  const toggle = useCallback(() => {
    const next = !dark;
    document.documentElement.classList.toggle('dark', next);
    localStorage.setItem('theme', next ? 'dark' : 'light');
    setDark(next);
  }, [dark]);

  return { dark, toggle };
}

const ROLES: { key: Role; icon: typeof Terminal; label: string }[] = [
  { key: 'dev', icon: Terminal, label: 'Developer' },
  { key: 'qa', icon: Sparkles, label: 'QA' },
  { key: 'business', icon: Briefcase, label: 'Business' },
];

export function Header() {
  const { project, repo } = useParams<{ project?: string; repo?: string }>();
  const navigate = useNavigate();
  const location = useLocation();
  const { dark, toggle } = useTheme();
  const { user, loading, logout, removeAccount } = useAuth();
  const [provider, setProvider] = useState<Provider>(() => getStoredProvider());
  const [accountMenuOpen, setAccountMenuOpen] = useState(false);
  const [deleteOpen, setDeleteOpen] = useState(false);
  const [deletePending, setDeletePending] = useState(false);
  const [deleteError, setDeleteError] = useState<string | null>(null);

  async function handleSignOut() {
    setAccountMenuOpen(false);
    try {
      await logout();
    } catch {
      // The dev gate re-renders to /login if the session is already gone server-side; if the
      // POST failed we simply stay signed in and let the user retry.
    }
  }

  async function handleDeleteAccount() {
    setDeletePending(true);
    setDeleteError(null);
    try {
      await removeAccount();
      setDeleteOpen(false);
    } catch (e) {
      setDeleteError(e instanceof Error ? e.message : 'Account deletion failed. Please try again.');
    } finally {
      setDeletePending(false);
    }
  }

  function goToLogin() {
    navigate('/login', { replace: true, state: { next: location.pathname } });
  }

  // The URL is the actual source of truth for which role is active (see App.tsx's /dev, /qa,
  // /business route prefixes) — re-derived on every navigation via `location.pathname` as a dep,
  // unlike a one-time `getStoredRole()` read, which would freeze on whatever role was current
  // when this component first mounted and never reflect a later switch. Falls back to the stored
  // role only on a path with no role prefix at all (e.g. the "/" role-select landing page).
  const role = useMemo<Role | null>(() => {
    const prefix = location.pathname.replace(/^\/+/, '').split('/')[0];
    if (prefix === 'dev' || prefix === 'qa' || prefix === 'business') return prefix;
    return getStoredRole();
  }, [location.pathname]);

  function handleRoleClick(next: Role) {
    setStoredRole(next);
    const path = next === 'dev' ? '/dev' : `/${next}`;
    navigate(path, { replace: true });
  }

function handleProviderClick(next: Provider) {
    setStoredProvider(next);
    setProvider(next);
    navigate(location.pathname, { replace: true });
  }

  return (
    <header className="sticky top-0 z-10 border-b border-border/60 bg-card/70 backdrop-blur-lg supports-backdrop-filter:bg-card/50">
      <div className="mx-auto flex max-w-6xl flex-wrap items-center gap-x-4 gap-y-2 px-6 py-3">
        <a
          href="/"
          onClick={(e) => {
            e.preventDefault();
            if (role) handleRoleClick(role);
            else navigate('/');
          }}
          className="group flex shrink-0 items-center gap-2"
        >
          <div className="relative flex size-8 items-center justify-center rounded-xl bg-linear-to-br from-primary via-[oklch(0.65_0.16_225)] to-[oklch(0.5_0.2_275)] text-[10px] font-bold text-primary-foreground shadow-md shadow-primary/30 transition-transform group-hover:scale-105">
            <span className="absolute inset-0 rounded-xl bg-linear-to-br from-primary via-[oklch(0.65_0.16_225)] to-[oklch(0.5_0.2_275)] opacity-0 blur-md transition-opacity group-hover:opacity-70" />
            <span className="relative">CC</span>
          </div>
          <div className="hidden leading-tight sm:block">
            <p className="text-xs font-semibold tracking-tight">Changelog Composer</p>
            <p className="text-[10px] text-muted-foreground">
              {provider === 'github' ? 'GitHub' : 'Azure DevOps · datasabai'}
            </p>
          </div>
        </a>

        <div className="hidden h-6 w-px bg-border sm:block" />

        <nav className="flex min-w-0 flex-1 items-center gap-1.5 overflow-x-auto text-sm">
          {project && (
            <>
              <a
                href={role === 'dev' ? `/${role}/projects/${encodeURIComponent(project)}` : `/${role}?project=${encodeURIComponent(project)}`}
                onClick={(e) => {
                  e.preventDefault();
                  navigate(role === 'dev' ? `/${role}/projects/${encodeURIComponent(project)}` : `/${role}?project=${encodeURIComponent(project)}`);
                }}
                className={cn(
                  'shrink-0 truncate rounded-md px-2 py-1 transition-colors',
                  !repo
                    ? 'font-medium text-foreground'
                    : 'text-muted-foreground hover:text-foreground',
                )}
              >
                {project}
              </a>
            </>
          )}

          {repo && (
            <>
              <ChevronRight className="size-3.5 shrink-0 text-muted-foreground/40" />
              <span className="shrink-0 truncate rounded-md px-2 py-1 font-medium text-foreground">{repo}</span>
            </>
          )}
        </nav>

        {role && (
          <div className="flex items-center gap-1 rounded-lg bg-muted/50 p-0.5">
            {ROLES.map((r) => {
              const Icon = r.icon;
              const active = r.key === role;
              return (
                <button
                  key={r.key}
                  type="button"
                  onClick={() => handleRoleClick(r.key)}
                  className={cn(
                    'flex cursor-pointer items-center gap-1.5 rounded-md px-2.5 py-1.5 text-xs font-medium transition-colors',
                    active
                      ? 'bg-card text-foreground shadow-xs'
                      : 'text-muted-foreground hover:text-foreground',
                  )}
                >
                  <Icon className="size-3.5" />
                  <span className="hidden sm:inline">{r.label}</span>
                </button>
              );
            })}
          </div>
        )}

        <button
          type="button"
          onClick={() => handleProviderClick(provider === 'github' ? 'azure' : 'github')}
          className="flex items-center gap-1.5 rounded-lg px-2.5 py-1.5 text-xs font-medium text-muted-foreground transition-colors hover:bg-accent hover:text-accent-foreground"
          aria-label={provider === 'github' ? 'Switch to Azure DevOps' : 'Switch to GitHub'}
        >
          {provider === 'github' ? <GitBranch className="size-4" /> : <Terminal className="size-4" />}
          <span className="hidden sm:inline">{provider === 'github' ? 'GitHub' : 'Azure'}</span>
        </button>

        {provider === 'github' && (
          <div className="flex items-center">
            {loading ? (
              <div className="size-7 animate-pulse rounded-full bg-muted" />
            ) : user ? (
              <div className="relative">
                <button
                  type="button"
                  onClick={() => setAccountMenuOpen((open) => !open)}
                  className="flex cursor-pointer items-center gap-1 rounded-lg px-1.5 py-1 transition-colors hover:bg-accent"
                  aria-label="Account menu"
                  aria-expanded={accountMenuOpen}
                >
                  {user.avatarUrl ? (
                    <img src={user.avatarUrl} alt="" className="size-7 rounded-full" />
                  ) : (
                    <span className="flex size-7 items-center justify-center rounded-full bg-primary/15 text-xs font-semibold text-primary">
                      {user.login.charAt(0).toUpperCase()}
                    </span>
                  )}
                  <ChevronDown className="size-3.5 text-muted-foreground" />
                </button>

                {accountMenuOpen && (
                  <>
                    <div className="fixed inset-0 z-20" onClick={() => setAccountMenuOpen(false)} />
                    <div className="absolute right-0 z-30 mt-2 w-64 overflow-hidden rounded-lg border border-border/60 bg-card shadow-lg">
                      <div className="flex items-center gap-2 border-b border-border/40 px-3 py-2.5">
                        {user.avatarUrl ? (
                          <img src={user.avatarUrl} alt="" className="size-8 rounded-full" />
                        ) : (
                          <span className="flex size-8 items-center justify-center rounded-full bg-primary/15 text-xs font-semibold text-primary">
                            {user.login.charAt(0).toUpperCase()}
                          </span>
                        )}
                        <div className="min-w-0">
                          <p className="truncate text-xs font-semibold text-foreground">{user.login}</p>
                          <p className="text-[10px] text-muted-foreground">Signed in with GitHub</p>
                        </div>
                      </div>

                      <button
                        type="button"
                        onClick={handleSignOut}
                        className="flex w-full cursor-pointer items-start gap-2.5 px-3 py-2.5 text-left transition-colors hover:bg-accent"
                      >
                        <LogOut className="mt-0.5 size-4 shrink-0 text-muted-foreground" />
                        <span>
                          <span className="block text-sm font-medium text-foreground">Sign out</span>
                          <span className="block text-[11px] leading-snug text-muted-foreground">
                            Ends this session. You can sign back in anytime.
                          </span>
                        </span>
                      </button>

                      <div className="border-t border-border/40 px-3 py-1">
                        <button
                          type="button"
                          onClick={() => {
                            setAccountMenuOpen(false);
                            setDeleteOpen(true);
                          }}
                          className="flex w-full cursor-pointer items-start gap-2.5 rounded-md px-2 py-2 text-left transition-colors hover:bg-destructive/5"
                        >
                          <Trash2 className="mt-0.5 size-4 shrink-0 text-destructive" />
                          <span>
                            <span className="block text-sm font-medium text-destructive">
                              Delete account
                            </span>
                            <span className="block text-[11px] leading-snug text-muted-foreground">
                              Removes your GitHub token and stored login.
                            </span>
                          </span>
                        </button>
                      </div>
                    </div>
                  </>
                )}
              </div>
            ) : (
              <Button variant="outline" size="sm" className="gap-1.5" onClick={goToLogin}>
                <LogIn className="size-3.5" /> Sign in
              </Button>
            )}
          </div>
        )}

        <button
          type="button"
          onClick={toggle}
          className="flex size-9 items-center justify-center rounded-lg text-muted-foreground transition-colors hover:bg-accent hover:text-accent-foreground"
          aria-label={dark ? 'Switch to light mode' : 'Switch to dark mode'}
        >
          {dark ? <Sun className="size-4.5" /> : <Moon className="size-4.5" />}
        </button>
        </div>

        <ConfirmDialog
          open={deleteOpen}
          title="Delete your account?"
          description="Only sign-in credentials are removed. Nothing you generated is touched."
          confirmLabel="Delete my account"
          pendingLabel="Deleting…"
          loading={deletePending}
          error={deleteError}
          onConfirm={handleDeleteAccount}
          onCancel={() => setDeleteOpen(false)}
        >
          <div className="space-y-3">
            <div className="rounded-lg border border-destructive/20 bg-destructive/5 p-3">
              <p className="mb-1.5 flex items-center gap-1.5 text-xs font-semibold text-destructive">
                <Trash2 className="size-3.5" /> This will be removed
              </p>
              <ul className="space-y-1 text-[11px] text-foreground/85">
                <li>Your GitHub login and profile info in this app</li>
                <li>The encrypted access token, and its revocation on GitHub</li>
                <li>Your active session — you'll be signed out</li>
              </ul>
            </div>
            <div className="rounded-lg border border-emerald-500/20 bg-emerald-500/5 p-3">
              <p className="mb-1.5 flex items-center gap-1.5 text-xs font-semibold text-emerald-600 dark:text-emerald-400">
                <CheckCircle2 className="size-3.5" /> This stays untouched
              </p>
              <ul className="space-y-1 text-[11px] text-foreground/85">
                <li>Every changelog and release note you generated</li>
                <li>Your version history and any saved edits</li>
                <li>Other users' accounts and data</li>
              </ul>
            </div>
          </div>
        </ConfirmDialog>
    </header>
  );
}
