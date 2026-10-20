import { useEffect, useState } from 'react';
import { RefreshCw, WifiOff, X } from 'lucide-react';

import { subscribeServiceStatus } from '@/api/client';
import { Button } from '@/components/ui/button';

export function ServiceStatusBanner() {
  const [offline, setOffline] = useState(false);
  const [dismissed, setDismissed] = useState(false);

  useEffect(
    () =>
      subscribeServiceStatus((status) => {
        setOffline(status === 'offline');
        if (status === 'online') setDismissed(false);
      }),
    [],
  );

  if (!offline || dismissed) return null;

  return (
    <div
      role="status"
      aria-live="polite"
      className="mb-3 overflow-hidden rounded-xl border border-amber-500/30 bg-amber-500/10 shadow-sm"
    >
      <div className="flex items-start gap-3 px-3.5 py-3">
        <div className="mt-0.5 flex size-8 shrink-0 items-center justify-center rounded-lg bg-amber-500/15 text-amber-600 dark:text-amber-400">
          <WifiOff className="size-4" />
        </div>
        <div className="min-w-0 flex-1">
          <p className="text-xs font-semibold text-foreground">We're having a moment</p>
          <p className="mt-0.5 text-[11px] leading-relaxed text-muted-foreground">
            The service is temporarily unavailable. Your work is safe — please try again in a
            moment.
          </p>
        </div>
        <div className="flex shrink-0 items-center gap-1">
          <Button
            type="button"
            size="sm"
            variant="outline"
            className="gap-1.5"
            onClick={() => window.location.reload()}
          >
            <RefreshCw />
            Try again
          </Button>
          <Button
            type="button"
            size="icon"
            variant="ghost"
            aria-label="Dismiss service status"
            onClick={() => setDismissed(true)}
          >
            <X />
          </Button>
        </div>
      </div>
    </div>
  );
}
