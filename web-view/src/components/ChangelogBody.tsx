import type { ReactNode } from "react";
import { Check } from "lucide-react";
import { Badge } from "@/components/ui/badge";
import { cn } from "@/lib/utils";

interface ChangelogBullet {
  type: string | null;
  text: string;
  ref: string | null;
}

type ChangelogBlock =
  | { kind: "heading"; text: string }
  | { kind: "bullets"; items: ChangelogBullet[] }
  | { kind: "paragraph"; text: string };

function renderInline(text: string): ReactNode {
  const parts = text.split(/(\*\*[^*]+\*\*)/g);
  return parts.map((part, i) => {
    if (part.startsWith("**") && part.endsWith("**")) {
      return <strong key={i}>{part.slice(2, -2)}</strong>;
    }
    return part;
  });
}

function changelogTypeColor(type: string): string {
  const base = type.split("(")[0].toLowerCase();
  if (base === "feat" || base === "feature") return "bg-success/15 text-success";
  if (base === "fix") return "bg-warning/15 text-warning-foreground dark:text-warning";
  return "bg-muted text-muted-foreground";
}

interface ParsedLine {
  type: string | null;
  text: string;
  ref: string | null;
}

function tryParseTypeRef(raw: string): ParsedLine | null {
  let rest = raw;

  let type: string | null = null;
  const boldTypeMatch = rest.match(/^\*\*([A-Za-z]+(?:\([\w.-]+\))?)\*\*:\s*/);
  if (boldTypeMatch) {
    type = boldTypeMatch[1];
    rest = rest.slice(boldTypeMatch[0].length);
  } else {
    const plainTypeMatch = rest.match(/^([A-Za-z]+(?:\([\w.-]+\))?):\s*/);
    if (plainTypeMatch) {
      type = plainTypeMatch[1];
      rest = rest.slice(plainTypeMatch[0].length);
    }
  }

  let ref: string | null = null;
  const refMatch = rest.match(/\s*\((?:PR\s*!?\d+|#\d+)\)\s*$/i);
  if (refMatch) {
    ref = refMatch[0].trim().replace(/^\(/, "").replace(/\)$/, "");
    rest = rest.slice(0, refMatch.index).trimEnd();
  }

  return { type, text: rest, ref };
}

export function ChangelogBody({ text }: { text: string | null | undefined }) {
  const blocks: ChangelogBlock[] = [];
  let currentBullets: ChangelogBullet[] = [];

  const flushBullets = () => {
    if (currentBullets.length > 0) blocks.push({ kind: "bullets", items: currentBullets });
    currentBullets = [];
  };

  for (const raw of (text ?? "").split("\n")) {
    const line = raw.trim();
    if (!line) {
      flushBullets();
      continue;
    }

    const headingMatch = line.match(/^#{1,6}\s+(.*)$/);
    if (headingMatch) {
      flushBullets();
      blocks.push({ kind: "heading", text: headingMatch[1] });
      continue;
    }

    const bulletContent = line.replace(/^[-*]\s+/, "");
    const isBullet = bulletContent !== line;
    const parsed = tryParseTypeRef(bulletContent);

    if (isBullet || parsed?.type) {
      currentBullets.push({ type: parsed?.type ?? null, text: parsed?.text ?? bulletContent, ref: parsed?.ref ?? null });
      continue;
    }

    flushBullets();
    blocks.push({ kind: "paragraph", text: line });
  }
  flushBullets();

  if (blocks.length === 0) {
    return     <p className="text-sm text-muted-foreground">No content.</p>
  }

  return (
    <div className="space-y-4">
      {blocks.map((block, i) => {
        if (block.kind === "heading") {
          return (
            <div
              key={i}
              className={cn(
                "flex items-center gap-2",
                i > 0 && "mt-6 border-t border-border/40 pt-5",
              )}
            >
              <span className="h-3.5 w-1 shrink-0 rounded-full bg-primary/60" />
              <p className="text-[11px] font-semibold tracking-wide text-foreground">
                {block.text}
              </p>
            </div>
          );
        }
        if (block.kind === "paragraph") {
          return (
            <p key={i} className="text-sm leading-relaxed text-foreground">
              {renderInline(block.text)}
            </p>
          );
        }
        return (
          <ul key={i} className="space-y-2.5">
            {block.items.map((item, j) => (
              <li key={j} className="flex items-start gap-3 text-sm leading-relaxed">
                {item.type ? (
                  <span className="mt-2 size-1.5 shrink-0 rounded-full bg-foreground/30" />
                ) : (
                  <span className="mt-0.5 flex size-4 shrink-0 items-center justify-center rounded-[4px] border border-border/60 bg-muted/40 text-muted-foreground">
                    <Check className="size-2.5" strokeWidth={3} />
                  </span>
                )}
                <span className="flex-1 min-w-0">
                  {item.type && (
                    <Badge
                      variant="outline"
                      className={cn("mr-2 border-transparent align-middle text-[10px] font-medium leading-normal", changelogTypeColor(item.type))}
                    >
                      {item.type}
                    </Badge>
                  )}
                  {renderInline(item.text)}
                  {item.ref && <span className="ml-2 font-mono text-[10px] text-muted-foreground">{item.ref}</span>}
                </span>
              </li>
            ))}
          </ul>
        );
      })}
    </div>
  );
}
