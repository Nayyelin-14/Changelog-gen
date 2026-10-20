import { Link2, LogOut, RefreshCcw } from 'lucide-react';
import { Outlet, useLocation, useNavigate } from 'react-router-dom';

import { useAuth } from '@/api/AuthContext';
import { Header } from '@/components/Header';
import { ServiceStatusBanner } from '@/components/ServiceStatusBanner';
import { Button } from '@/components/ui/button';
import { Card, CardContent } from '@/components/ui/card';
import { useResolvedAiProvider } from '@/hooks/useAiProvider';
import { clearQueryCache } from '@/hooks/useQuery';
import { getStoredProvider, setStoredProvider } from '@/lib/provider';

export function Layout() {
  // A stored AI provider can outlive the admin's enabled set (key revoked, old picker value).
  // Resolving once at the app shell persists the correction, so EVERY AI call — including the
  // ones that read localStorage directly (chat, SSE, generate) — sends a provider the server
  // actually serves, on any page.
  useResolvedAiProvider();
  const { user, reconnect, logout } = useAuth();
  const location = useLocation();
  const navigate = useNavigate();

  // A signed-in user with no GitHub credential cannot fetch GitHub data — the API answers 403
  // github_not_connected — so the shell blocks instead of warning: every page behind it would only
  // render half a dashboard. Azure rides the shared service PAT and is unaffected, and /login stays
  // reachable so the reconnect flow itself can't get trapped.
  const githubDisconnected =
    Boolean(user) &&
    !user?.githubConnected &&
    getStoredProvider() === 'github' &&
    location.pathname !== '/login';
  const nextPath = location.pathname + location.search;

  const useAzureInstead = () => {
    setStoredProvider('azure');
    clearQueryCache();
    navigate('/', { replace: true });
  };

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
      {/* main is the ONE scroll container for pages that overflow it (their content just sizes
          naturally). Pages that opt into filling the screen (flex-1 min-h-0 down the tree) get
          a real bounded height here instead — that's what lets their own inner lists scroll
          internally instead of the whole page growing taller than the viewport. */}
      <main className="flex flex-1 flex-col overflow-x-hidden overflow-y-auto">
        <div className="mx-auto flex h-full min-h-0 w-full max-w-7xl flex-1 flex-col px-4 py-4 sm:px-6">
          <ServiceStatusBanner />
          {githubDisconnected ? (
            <div className="flex flex-1 items-center justify-center">
              <Card className="w-full max-w-md border-amber-500/30 shadow-lg">
                <CardContent className="space-y-5 p-6 text-center">
                  <div className="mx-auto flex size-12 items-center justify-center rounded-full bg-amber-500/10">
                    <Link2 className="size-6 text-amber-600 dark:text-amber-400" />
                  </div>
                  <div className="space-y-1.5">
                    <h1 className="text-lg font-semibold tracking-tight">GitHub is disconnected</h1>
                    <p className="text-sm text-muted-foreground">
                      Repository data can't be fetched until GitHub is reconnected. Your account and
                      your saved changelogs stay exactly where they are.
                    </p>
                  </div>
                  <div className="flex flex-col gap-2">
                    <Button type="button" className="w-full gap-2" onClick={() => reconnect(nextPath)}>
                      <RefreshCcw />
                      Reconnect GitHub
                    </Button>
                    <Button
                      type="button"
                      variant="outline"
                      className="w-full"
                      onClick={useAzureInstead}
                    >
                      Continue with Azure DevOps
                    </Button>
                    <Button
                      type="button"
                      variant="ghost"
                      className="w-full gap-2 text-muted-foreground"
                      onClick={() => void logout()}
                    >
                      <LogOut />
                      Sign out
                    </Button>
                  </div>
                </CardContent>
              </Card>
            </div>
          ) : (
            <Outlet />
          )}
        </div>
      </main>
    </div>
  );
}
