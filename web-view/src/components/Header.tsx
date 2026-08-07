import { useCallback, useEffect, useMemo, useState } from 'react';
import { useLocation, useNavigate, useParams } from 'react-router-dom';
import { Briefcase, ChevronRight, Moon, Sparkles, Sun, Terminal } from 'lucide-react';

import { getStoredRole, setStoredRole, type Role } from '@/lib/role';
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
            <p className="text-[10px] text-muted-foreground">Azure DevOps · datasabai</p>
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
          onClick={toggle}
          className="flex size-9 items-center justify-center rounded-lg text-muted-foreground transition-colors hover:bg-accent hover:text-accent-foreground"
          aria-label={dark ? 'Switch to light mode' : 'Switch to dark mode'}
        >
          {dark ? <Sun className="size-4.5" /> : <Moon className="size-4.5" />}
        </button>
      </div>
    </header>
  );
}
