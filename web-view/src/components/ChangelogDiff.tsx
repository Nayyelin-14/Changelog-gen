import { diffLines } from "diff";

import { cn } from "@/lib/utils";

interface SideLine {
  text: string;
  type: "same" | "removed" | "added" | "empty";
}

function buildSideBySide(before: string, after: string): [SideLine[], SideLine[]] {
  const parts = diffLines(before, after);
  const left: SideLine[] = [];
  const right: SideLine[] = [];

  for (const part of parts) {
    const lines = part.value.replace(/\n$/, "").split("\n");
    if (part.added) {
      for (const line of lines) {
        left.push({ text: "", type: "empty" });
        right.push({ text: line, type: "added" });
      }
    } else if (part.removed) {
      for (const line of lines) {
        left.push({ text: line, type: "removed" });
        right.push({ text: "", type: "empty" });
      }
    } else {
      for (const line of lines) {
        left.push({ text: line, type: "same" });
        right.push({ text: line, type: "same" });
      }
    }
  }

  return [left, right];
}

export function ChangelogDiff({ before, after }: { before: string; after: string }) {
  const [left, right] = buildSideBySide(before, after);

  if (left.length === 0 && right.length === 0) {
    return <p className="text-sm text-muted-foreground">No changes.</p>;
  }

  return (
    <div className="grid grid-cols-2 gap-0 rounded-lg border border-border/20 overflow-hidden">
      <div className="border-r border-border/20">
        <div className="sticky top-0 bg-muted/80 px-3 py-1.5 text-[11px] font-semibold text-muted-foreground border-b border-border/20">
          Current
        </div>
        <div className="font-mono text-[11px] leading-relaxed">
          {left.map((line, i) => (
            <div
              key={i}
              className={cn(
                "flex items-start gap-2 px-3 py-0.5 min-h-[1.4em]",
                line.type === "removed" && "bg-red-50 text-red-700 dark:bg-red-950 dark:text-red-400",
                line.type === "same" && "text-foreground",
                line.type === "empty" && "bg-muted/20 text-muted-foreground/30",
              )}
            >
              <span className="shrink-0 w-4 text-center select-none text-[10px] opacity-40">
                {line.type === "removed" ? "−" : ""}
              </span>
              <span className="min-w-0 flex-1 whitespace-pre-wrap break-all">
                {line.text || (line.type === "empty" ? " " : " ")}
              </span>
            </div>
          ))}
        </div>
      </div>

      <div>
        <div className="sticky top-0 bg-muted/80 px-3 py-1.5 text-[11px] font-semibold text-muted-foreground border-b border-border/20">
          New
        </div>
        <div className="font-mono text-[11px] leading-relaxed">
          {right.map((line, i) => (
            <div
              key={i}
              className={cn(
                "flex items-start gap-2 px-3 py-0.5 min-h-[1.4em]",
                line.type === "added" && "bg-green-50 text-green-700 dark:bg-green-950 dark:text-green-400",
                line.type === "same" && "text-foreground",
                line.type === "empty" && "bg-muted/20 text-muted-foreground/30",
              )}
            >
              <span className="shrink-0 w-4 text-center select-none text-[10px] opacity-40">
                {line.type === "added" ? "+" : ""}
              </span>
              <span className="min-w-0 flex-1 whitespace-pre-wrap break-all">
                {line.text || (line.type === "empty" ? " " : " ")}
              </span>
            </div>
          ))}
        </div>
      </div>
    </div>
  );
}
