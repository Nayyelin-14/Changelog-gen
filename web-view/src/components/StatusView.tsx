import { AlertCircle } from 'lucide-react';

import { Alert, AlertDescription, AlertTitle } from '@/components/ui/alert';

export function ErrorView({ message }: { message: string }) {
  return (
    <Alert variant="destructive" className="animate-in fade-in slide-in-from-top-1 border-destructive/30">
      <AlertCircle />
      <AlertTitle>Something went wrong</AlertTitle>
      <AlertDescription className="flex items-center justify-between gap-2 text-xs">
        <span className="truncate">{message}</span>
      </AlertDescription>
    </Alert>
  );
}
