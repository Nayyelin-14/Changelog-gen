import { GitBranch, ShieldCheck, } from 'lucide-react';
import { useLocation, useNavigate, useSearchParams } from 'react-router-dom';

import { useAuth } from '@/api/AuthContext';
import { Button } from '@/components/ui/button';
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card';

const ERROR_MESSAGES: Record<string, string> = {
  access_denied: 'Authorization was cancelled — nothing was signed up yet.',
  invalid_request: 'The sign-in request was malformed; please try again.',
  invalid_state: 'This sign-in link has expired or was tampered with; please start again.',
  oauth_failed: 'GitHub rejected the sign-in; please try again.',
};
const UNKNOWN_ERROR = 'Something went wrong during GitHub sign-in; please try again.';

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
      <Card className="w-full max-w-md">
        <CardHeader>
          <CardTitle className="flex items-center gap-2 text-lg">
            <GitBranch className="size-5 text-primary" />
            Sign in with GitHub
          </CardTitle>
        </CardHeader>
        <CardContent className="space-y-5 text-sm text-muted-foreground">
          <p>
            The GitHub dashboard runs as <span className="font-medium text-foreground">your own GitHub
            account</span> once you sign in — every repo, PR, commit and push is then fetched and
            authored by you, not by the shared service account.
          </p>

          <ul className="space-y-2">
            {[
              <>We only store your GitHub login, avatar and an <span className="font-medium text-foreground">encrypted</span> access token.</>,
              <>No password or email is ever asked for or stored.</>,
              <>Generated changelogs and history stay in this app regardless of how you sign in.</>,
              <>You can <span className="font-medium text-foreground">delete your account whenever you want</span> — that removes only the stored sign-in credentials (and revokes the token server-side), never any content you generated.</>,
            ].map((point, i) => (
              <li key={i} className="flex items-start gap-2">
                <ShieldCheck className="mt-0.5 size-4 shrink-0 text-primary" />
                <span>{point}</span>
              </li>
            ))}
          </ul>

          {error && (
            <div className="rounded-lg border border-destructive/30 bg-destructive/5 px-3 py-2 text-xs text-destructive">
              {ERROR_MESSAGES[error] ?? UNKNOWN_ERROR}
            </div>
          )}

          {user ? (
            <div className="flex flex-col gap-2">
              <div className="flex items-center gap-2 rounded-lg bg-muted/50 px-3 py-2">
                {user.avatarUrl ? (
                  <img src={user.avatarUrl} alt="" className="size-6 rounded-full" />
                ) : (
                  <GitBranch className="size-5" />
                )}
                <span className="text-foreground">{user.login}</span>
              </div>
              <Button type="button" onClick={() => navigate(next, { replace: true })}>
                Continue to {next.replace(/^\//, '')}
              </Button>
            </div>
          ) : (
            <Button type="button" className="w-full" onClick={() => login(next)}>
              <GitBranch className="size-4" />
              Continue with GitHub
            </Button>
          )}

          <p className="text-center text-xs text-muted-foreground/80">
            Mounted routes are accessed through the developer dashboard once you're in.
          </p>
        </CardContent>
      </Card>
    </div>
  );
}