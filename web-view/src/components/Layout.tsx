import { Link2, RefreshCcw } from 'lucide-react';
import { Outlet, useLocation } from 'react-router-dom';

import { useAuth } from '@/api/AuthContext';
import { Header } from '@/components/Header';
import { useResolvedAiProvider } from '@/hooks/useAiProvider';
import { getStoredProvider } from '@/lib/provider';

export function Layout() {
  // A stored AI provider can outlive the admin's enabled set (key revoked, old picker value).
  // Resolving once at the app shell persists the correction, so EVERY AI call — including the
  // ones that read localStorage directly (chat, SSE, generate) — sends a provider the server
  // actually serves, on any page.
  useResolvedAiProvider();
  const { user, reconnect } = useAuth();
  const location = useLocation();

  // A signed-in user who disconnected GitHub keeps their account and saved changelogs, but
  // GitHub-backed fetches (repos, history, pushes) will fail until they reconnect. Surface
  // that at the app shell instead of letting each page fail with a raw upstream error.
  // Azure rides the shared service PAT and is unaffected by a personal disconnect.
  const showReconnectNotice = Boolean(user) && !user?.githubConnected && getStoredProvider() === 'github';

  return (
    <div className="relative flex h-screen flex-col overflow-hidden bg-background">
      <div className="pointer-events-none fixed inset-0 -z-10 overflow-hidden">
        <div className="absolute -top-32 -left-24 size-96 rounded-full bg-primary/15 blur-3xl animate-float" />
        <div
          className="absolute top-1/3 -right-32 size-[28rem] rounded-full bg-accent/40 blur-3xl animate-float"
          style={{ animationDelay: '1.5s' }}
        />
        <div
          className="absolute bottom-0 left-1/4 size-80 rounded-full bg-primary/10 blur-3xl animate-float"
          style={{ animationDelay: '3s' }}
        />
      </div>
      <Header />
      {showReconnectNotice && (
        <div
          role="status"
          className="flex flex-wrap items-center justify-center gap-x-2 gap-y-1 border-b border-amber-500/30 bg-amber-500/10 px-4 py-1.5 text-xs text-amber-700 dark:text-amber-400"
        >
          <Link2 className="size-3.5 shrink-0" />
          <span>
            GitHub is disconnected — repository data can't be fetched. Your saved changelogs stay in
            your account.
          </span>
          <button
            type="button"
            onClick={() => reconnect(location.pathname + location.search)}
            className="inline-flex cursor-pointer items-center gap-1 rounded-md px-1.5 py-0.5 font-medium underline-offset-2 transition-colors hover:bg-amber-500/15 hover:underline"
          >
            <RefreshCcw className="size-3" />
            Reconnect GitHub
          </button>
        </div>
      )}
      {/* main is the ONE scroll container for pages that overflow it (their content just sizes
          naturally). Pages that opt into filling the screen (flex-1 min-h-0 down the tree) get
          a real bounded height here instead — that's what lets their own inner lists scroll
          internally instead of the whole page growing taller than the viewport. */}
      <main className="flex flex-1 flex-col overflow-x-hidden overflow-y-auto">
        <div className="mx-auto flex h-full min-h-0 w-full max-w-7xl flex-1 flex-col px-4 py-4 sm:px-6">
          <Outlet />
        </div>
      </main>
    </div>
  );
}
