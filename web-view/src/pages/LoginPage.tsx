import {
  CheckCircle2,
  FileLock2,
  Fingerprint,
  GitBranch,
  LogIn,
  Lock,
  ShieldCheck,
  Sparkles,
  UserRound,
  Users,
  XCircle,
} from 'lucide-react';
import { useLocation, useNavigate, useSearchParams } from 'react-router-dom';

import { useAuth } from '@/api/AuthContext';
import { Button } from '@/components/ui/button';
import { Card, CardContent } from '@/components/ui/card';
import { cn } from '@/lib/utils';

const ERROR_MESSAGES: Record<string, string> = {
  access_denied: 'Authorization was cancelled — nothing was signed up yet.',
  invalid_request: 'The sign-in request was malformed; please try again.',
  invalid_state: 'This sign-in link has expired or was tampered with; please start again.',
  oauth_failed: 'GitHub rejected the sign-in; please try again.',
};
const UNKNOWN_ERROR = 'Something went wrong during GitHub sign-in; please try again.';

const SHARED_INTO = {
  label: 'Shared service account',
  icon: Users,
  points: [
    'Every action is authored by one shared bot token',
    'Your private repos are invisible to the dashboard',
    'Pushes and PRs show up as the bot, not you',
  ],
};

const YOUR_INTO = {
  label: 'Your GitHub account',
  icon: UserRound,
  points: [
    'Every action is authored as you — repos, PRs, commits',
    'Private and public repos you can access appear',
    'Recognize your own work at a glance',
  ],
};

const TRUST_POINTS = [
  {
    icon: Lock,
    title: 'Encrypted at rest',
    body: 'Your access token is stored AES-encrypted — never as plain text.',
  },
  {
    icon: Fingerprint,
    title: 'No password, ever',
    body: 'Only your GitHub login and an access token. No email or password is collected.',
  },
  {
    icon: FileLock2,
    title: 'You stay in control',
    body: 'Generated changelogs and history live in this app and are never tied to your login.',
  },
];

export function LoginPage() {
  const { user, loading, login } = useAuth();
  const [params] = useSearchParams();
  const navigate = useNavigate();
  const location = useLocation();

  // Where to land after the OAuth round-trip: the page the user was on when they hit the gate
  // (funnel stays put), else the developer dashboard. Server-side sanitization makes this safe.
  const next = (
    (location.state as { next?: string } | null)?.next ??
    params.get('next') ??
    '/dev'
  );
  const error = params.get('error');

  if (loading) {
    return (
      <div className="flex flex-1 items-center justify-center">
        <div className="size-6 animate-spin rounded-full border-2 border-border border-t-primary" />
      </div>
    );
  }

  return (
    <div className="flex flex-1 items-center justify-center py-8">
      <Card className="w-full max-w-lg overflow-hidden border-border/40 shadow-xl shadow-primary/5">
        {/* Hero */}
        <div className="bg-mesh border-b border-border/40 px-8 pb-7 pt-9">
          <div className="flex items-center justify-center gap-1.5 text-[10px] font-medium text-primary">
            <Sparkles className="size-3" />
            Changelog Composer
          </div>
          <h1 className="mt-2 text-center text-2xl font-bold tracking-tight text-gradient">
            Your GitHub, your account
          </h1>
          <p className="mx-auto mt-2 max-w-md text-center text-sm text-muted-foreground">
            The GitHub dashboard runs as{' '}
            <span className="font-medium text-foreground">your own identity</span> once you sign in —
            repos, PRs, commits and pushes are all fetched and authored by you, not by a shared
            service account.
          </p>
        </div>

        <CardContent className="space-y-6 px-8 py-7">
          {error && (
            <div className="rounded-lg border border-destructive/30 bg-destructive/5 px-3 py-2 text-xs text-destructive">
              {ERROR_MESSAGES[error] ?? UNKNOWN_ERROR}
            </div>
          )}

          {/* Before / after */}
          <div className="space-y-3">
            <div className="relative grid grid-cols-1 gap-3 sm:grid-cols-2">
              {['Shared service account', 'Your GitHub account'].map((_, i) => {
                const side = i === 0 ? SHARED_INTO : YOUR_INTO;
                const Icon = side.icon;
                const highlight = i === 1;
                return (
                  <div
                    key={side.label}
                    className={cn(
                      'rounded-xl border p-3.5',
                      highlight
                        ? 'border-primary/30 bg-primary/5'
                        : 'border-border/50 bg-muted/30',
                    )}
                  >
                    <div className="flex items-center gap-2">
                      <Icon
                        className={cn(
                          'size-4',
                          highlight ? 'text-primary' : 'text-muted-foreground',
                        )}
                      />
                      <span className="text-xs font-semibold text-foreground">{side.label}</span>
                      {i === 0 && <XCircle className="ml-auto size-3.5 text-destructive/70" />}
                      {highlight && <CheckCircle2 className="ml-auto size-3.5 text-primary" />}
                    </div>
                    <ul className="mt-2.5 space-y-1.5">
                      {side.points.map((point, j) => (
                        <li
                          key={j}
                          className={cn(
                            'text-[11px] leading-snug',
                            highlight ? 'text-foreground/90' : 'text-muted-foreground/80',
                          )}
                        >
                          {point}
                        </li>
                      ))}
                    </ul>
                  </div>
                );
              })}
            </div>

            <p className="flex items-center gap-1.5 text-[11px] text-muted-foreground/80">
              <ShieldCheck className="size-3.5 shrink-0 text-primary" />
              We only store your GitHub login, avatar and an encrypted access token.
            </p>
          </div>

          {/* CTA */}
          {user ? (
            <div className="flex flex-col gap-2">
              <Button type="button" className="w-full" onClick={() => navigate(next, { replace: true })}>
                <LogIn className="size-4" />
                Continue to {next.replace(/^\//, '')}
              </Button>
              <div className="flex items-center justify-center gap-2 rounded-lg bg-muted/50 px-3 py-2 text-sm">
                {user.avatarUrl ? (
                  <img src={user.avatarUrl} alt="" className="size-5 rounded-full" />
                ) : (
                  <GitBranch className="size-4 text-muted-foreground" />
                )}
                <span className="font-medium text-foreground">{user.login}</span>
                <span className="text-muted-foreground">· signed in</span>
              </div>
            </div>
          ) : (
            <Button type="button" className="w-full gap-2 text-sm font-semibold" onClick={() => login(next)}>
              <GitBranch className="size-4" />
              Continue with GitHub
            </Button>
          )}

          {/* Trust */}
          <div className="grid grid-cols-1 gap-3 border-t border-border/40 pt-5 sm:grid-cols-3">
            {TRUST_POINTS.map(({ icon: Icon, title, body }) => (
              <div key={title} className="space-y-1">
                <div className="flex items-center gap-1.5">
                  <Icon className="size-3.5 text-primary" />
                  <span className="text-[11px] font-semibold text-foreground">{title}</span>
                </div>
                <p className="text-[11px] leading-snug text-muted-foreground">{body}</p>
              </div>
            ))}
          </div>
        </CardContent>
      </Card>
    </div>
  );
}