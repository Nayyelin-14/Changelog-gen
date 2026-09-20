import { useState } from "react";
import { ChevronDown, ChevronRight, Loader2 } from "lucide-react";

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
  friendlyError,
  confirmDisabled,
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
  /** User-friendly error message — shown as the primary message. Falls back to first line of `error`. */
  friendlyError?: string | null;
  /** Extra gate for the confirm button beyond `loading` — e.g. a validation failure in `children`. */
  confirmDisabled?: boolean;
  onConfirm: () => void;
  onCancel: () => void;
}) {
  const [detailsOpen, setDetailsOpen] = useState(false);

  return (
    <Dialog
      open={open}
      onOpenChange={(next: boolean) => {
        if (!next) onCancel();
      }}
    >
      <DialogContent className={diff ? "flex max-h-[85vh] flex-col overflow-hidden sm:max-w-4xl" : "sm:max-w-lg"}>
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
          <div className="rounded-lg border border-destructive/30 bg-destructive/5 px-4 py-3 text-sm text-destructive space-y-1">
            <p className="break-words">
              {friendlyError ?? error.split("\n")[0]}
            </p>
            {(error.includes("\n") || error.length > 120) ? (
              <button
                type="button"
                onClick={() => setDetailsOpen(!detailsOpen)}
                className="inline-flex items-center gap-1 text-xs text-destructive/70 hover:text-destructive transition-colors"
              >
                {detailsOpen ? <ChevronDown className="size-3" /> : <ChevronRight className="size-3" />}
                Technical details
              </button>
            ) : null}
            {detailsOpen && (
              <pre className="mt-1 max-h-40 overflow-auto whitespace-pre-wrap text-[11px] text-destructive/80 leading-relaxed">
                {error}
              </pre>
            )}
          </div>
        )}

        <DialogFooter>
          <Button variant="ghost" onClick={onCancel} disabled={loading}>
            Cancel
          </Button>
          <Button onClick={onConfirm} disabled={loading || confirmDisabled}>
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
