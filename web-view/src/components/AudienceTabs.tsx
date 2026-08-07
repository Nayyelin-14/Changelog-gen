import type { ComponentType } from "react";

import { cn } from "@/lib/utils";

export interface AudienceTabConfig {
  key: string;
  label: string;
  icon: ComponentType<{ className?: string }>;
  accent: string;
  activeBg: string;
}

interface AudienceTabsProps {
  tabs: AudienceTabConfig[];
  activeTab: string;
  onChange: (key: string) => void;
  /** Keyed by tab key — presence of a truthy value shows the "already generated" dot. */
  generated?: Partial<Record<string, unknown>>;
}

/** Audience tab bar — developer/QA/business switcher with roving-tabindex arrow-key nav, shared
 * by every history view that lets a user switch audience for a selected version. */
export function AudienceTabs({ tabs, activeTab, onChange, generated }: AudienceTabsProps) {
  return (
    <div
      role="tablist"
      aria-label="Changelog audience"
      className="flex w-fit items-center gap-1 rounded-lg bg-muted/50 p-1"
    >
      {tabs.map((tab, i) => (
        <button
          key={tab.key}
          type="button"
          role="tab"
          aria-selected={activeTab === tab.key}
          tabIndex={activeTab === tab.key ? 0 : -1}
          onClick={() => onChange(tab.key)}
          onKeyDown={(e) => {
            if (e.key !== "ArrowRight" && e.key !== "ArrowLeft") return;
            e.preventDefault();
            const next = tabs[(i + (e.key === "ArrowRight" ? 1 : tabs.length - 1)) % tabs.length];
            onChange(next.key);
            (e.currentTarget.parentElement?.children[tabs.indexOf(next)] as HTMLElement | undefined)?.focus();
          }}
          className={cn(
            "flex items-center gap-1.5 rounded-md px-3 py-1.5 text-xs font-medium transition-colors",
            activeTab === tab.key
              ? cn(tab.activeBg, tab.accent, "shadow-xs")
              : "text-muted-foreground hover:text-foreground",
          )}
        >
          <tab.icon
            className={cn(
              "size-3.5",
              activeTab !== tab.key && tab.accent,
              activeTab !== tab.key && "opacity-70",
            )}
          />
          {tab.label}
          {tab.key !== "developer" && generated?.[tab.key] ? (
            <span className="size-1.5 rounded-full bg-success" />
          ) : null}
        </button>
      ))}
    </div>
  );
}
