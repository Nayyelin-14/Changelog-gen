import { Loader2 } from "lucide-react";

import { ChangelogDiff } from "@/components/ChangelogDiff";
import { Button } from "@/components/ui/button";
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from "@/components/ui/dialog";

/** Generic confirm gate for DB-write actions (Generate/Regenerate/Save). AI is called before
 * the dialog opens so `diff` shows the real candidate text. `diff` is optional for Save (the
 * before/after is already known without a network call). */
export function ConfirmDialog({
  open,
  title,
  description,
  children,
  diff,
  confirmLabel,
  pendingLabel,
  loading,
  error,
  onConfirm,
  onCancel,
}: {
  open: boolean;
  title: string;
  description: string;
  /** Extra controls between the description and the diff — e.g. a branch picker. */
  children?: React.ReactNode;
  diff?: { before: string; after: string };
  confirmLabel: string;
  pendingLabel: string;
  loading: boolean;
  error: string | null;
  onConfirm: () => void;
  onCancel: () => void;
}) {
  return (
    <Dialog
      open={open}
      onOpenChange={(next: boolean) => {
        if (!next) onCancel();
      }}
    >
      <DialogContent className={diff ? "flex max-h-[85vh] flex-col overflow-hidden sm:max-w-4xl" : undefined}>
        <DialogHeader>
          <DialogTitle>{title}</DialogTitle>
          <DialogDescription>{description}</DialogDescription>
        </DialogHeader>

        {children}

        {diff && (
          <div className="min-h-0 flex-1 overflow-y-auto rounded-lg border border-border/20">
            <ChangelogDiff before={diff.before} after={diff.after} />
          </div>
        )}

        {error && (
          <div className="rounded-lg border border-destructive/30 bg-destructive/5 px-4 py-3 text-sm text-destructive">
            {error}
          </div>
        )}

        <DialogFooter>
          <Button variant="ghost" onClick={onCancel} disabled={loading}>
            Cancel
          </Button>
          <Button onClick={onConfirm} disabled={loading}>
            {loading ? (
              <>
                <Loader2 className="size-3.5 animate-spin" />
                {pendingLabel}
              </>
            ) : (
              confirmLabel
            )}
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}
