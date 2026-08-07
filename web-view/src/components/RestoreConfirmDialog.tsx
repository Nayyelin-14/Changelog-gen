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

/** Confirms a restore by showing the diff (not just the destination text). Restore is a rollback
 * — unlike Save/Regenerate (reversible via restore), it gets its own confirmation step. Shared
 * by Dev dashboard and QA page. */
export function RestoreConfirmDialog({
  open,
  tabLabel,
  version,
  currentText,
  previousText,
  restoring,
  restoreError,
  onConfirm,
  onCancel,
  title,
  description,
}: {
  open: boolean;
  tabLabel: string;
  version: string | null | undefined;
  currentText: string | undefined;
  previousText: string | undefined;
  restoring: boolean;
  restoreError: string | null;
  onConfirm: () => void;
  onCancel: () => void;
  title?: string;
  description?: string;
}) {
  return (
    <Dialog
      open={open}
      onOpenChange={(next: boolean) => {
        if (!next) onCancel();
      }}
    >
      <DialogContent className="flex max-h-[85vh] flex-col overflow-hidden sm:max-w-4xl">
        <DialogHeader>
          <DialogTitle>{title ?? `Restore previous ${tabLabel} changelog?`}</DialogTitle>
          <DialogDescription>
            {description ??
              `This replaces the current v${version ?? "?"} ${tabLabel} text with the version ` +
                `highlighted below. What you're replacing becomes the new "previous" — restoring ` +
                `again would bring it right back.`}
          </DialogDescription>
        </DialogHeader>

        <div className="min-h-0 flex-1 overflow-y-auto rounded-lg border border-border/20">
          {previousText ? (
            <ChangelogDiff before={currentText ?? ""} after={previousText} />
          ) : (
            <p className="px-4 py-3 text-sm text-muted-foreground">Nothing to preview.</p>
          )}
        </div>

        {restoreError && (
          <div className="rounded-lg border border-destructive/30 bg-destructive/5 px-4 py-3 text-sm text-destructive">
            {restoreError}
          </div>
        )}

        <DialogFooter>
          <Button variant="ghost" onClick={onCancel} disabled={restoring}>
            Cancel
          </Button>
          <Button onClick={onConfirm} disabled={restoring || !previousText}>
            {restoring ? (
              <>
                <Loader2 className="size-3.5 animate-spin" />
                Restoring…
              </>
            ) : (
              "Restore this version"
            )}
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}
