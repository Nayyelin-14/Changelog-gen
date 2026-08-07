import { useCallback, useEffect, useState } from 'react';
import { useNavigate, useParams, useSearchParams } from 'react-router-dom';
import { ArrowLeft, FileText, FileWarning, Loader2, Sparkles } from 'lucide-react';

import { getChangelogPreview, listHistory } from '@/api/client';
import type { PreviewAudience } from '@/api/types';
import { AudienceTabs } from '@/components/AudienceTabs';
import { ChangelogBody } from '@/components/ChangelogBody';
import { ChangelogChatWidget } from '@/components/ChangelogChatWidget';
import { VersionListSidebar } from '@/components/VersionListSidebar';
import { Card, CardContent } from '@/components/ui/card';
import { useQuery } from '@/hooks/useQuery';
import { GENERATED_TABS } from '@/lib/historyTabs';

interface AudienceChangelogPageProps {
  /** Route this page was reached under (qa or business) — used only for the "Back" link, since
   * it can differ from the audience currently selected (QA can browse into the Business tab). */
  roleBase: PreviewAudience;
  /** Which audience tabs this role may switch between. A single entry (Business) renders no tab
   * bar at all; QA's two entries render the same switcher Dev uses, minus the Developer tab and
   * minus every action button — this page never generates, edits, regenerates, or restores. */
  audiences: PreviewAudience[];
}

export function AudienceChangelogPage({ roleBase, audiences }: AudienceChangelogPageProps) {
  const { project, repo } = useParams<{ project: string; repo: string }>();
  const tabs = GENERATED_TABS.filter((tab) => audiences.includes(tab.key));
  const [searchParams, setSearchParams] = useSearchParams();
  const navigate = useNavigate();
  const [page, setPage] = useState(0);
  const [audience, setAudience] = useState<PreviewAudience>(audiences[0]);
  const audienceConfig = tabs.find((tab) => tab.key === audience);

  const loadHistory = useCallback(() => listHistory(project!, repo!, undefined, page, 20), [project, repo, page]);
  const history = useQuery(loadHistory, [project, repo, page]);

  // Only versions we can actually regenerate/preview from — an entry without a version tag
  // has nothing to key the AI call or the cache on.
  const versions = history.status === 'success' ? history.data.entries.filter((e) => e.version) : [];
  const selectedVersion = searchParams.get('version') ?? undefined;

  useEffect(() => {
    setPage(0);
    setAudience(audiences[0]);
    // audiences is a per-role constant from the caller, not reactive state — re-running this
    // whenever its identity changes would fight the tab switch on every render.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [project, repo]);

  const loadPreview = useCallback(
    () => {
      if (!selectedVersion) return Promise.reject(new Error('no-version'));
      return getChangelogPreview(project!, repo!, audience, selectedVersion);
    },
    [project, repo, audience, selectedVersion],
  );
  const preview = useQuery(loadPreview, [project, repo, audience, selectedVersion]);

  return (
    <div className="space-y-4">
      <div className="flex flex-wrap items-center justify-between gap-1 text-sm">
        <button
          onClick={() => navigate(`/${roleBase}?project=${encodeURIComponent(project!)}`)}
          className="flex cursor-pointer items-center gap-1.5 rounded-md px-2 py-1 -ml-2 font-medium text-muted-foreground hover:bg-muted/50 hover:text-foreground transition-colors"
        >
          <ArrowLeft className="size-4" />
          Back
        </button>
        <div className="flex items-center gap-2">
          <span className="font-semibold text-foreground">{repo}</span>
          <span className="text-muted-foreground/30">·</span>
          <span className="text-muted-foreground">{project}</span>
        </div>
      </div>

      {history.status === 'loading' && (
        <div className="grid items-start gap-4 lg:grid-cols-[17rem_1fr]">
          <div className="flex h-48 flex-col items-center justify-center gap-2 rounded-lg bg-card shadow-xs lg:h-[calc(100vh-9rem)]">
            <Loader2 className="size-5 animate-spin text-muted-foreground/40" />
            <p className="text-xs text-muted-foreground/60">Loading versions…</p>
          </div>
          <div className="flex h-48 flex-col items-center justify-center gap-2 rounded-xl border border-border/60 bg-card lg:h-[calc(100vh-9rem)]">
            <Loader2 className="size-5 animate-spin text-muted-foreground/40" />
            <p className="text-xs text-muted-foreground/60">Loading changelog…</p>
          </div>
        </div>
      )}

      {history.status === 'error' && (
        <Card>
          <CardContent className="flex flex-col items-center gap-2 py-8 text-center">
            <FileWarning className="size-6 text-muted-foreground" />
            <p className="text-sm font-medium">Couldn't load version history</p>
            <p className="max-w-sm text-xs text-muted-foreground">{history.error.message}</p>
          </CardContent>
        </Card>
      )}

      {history.status === 'success' && versions.length === 0 && (
        <Card>
          <CardContent className="flex flex-col items-center gap-2 py-8 text-center">
            <FileWarning className="size-6 text-muted-foreground" />
            <p className="text-sm font-medium">No versions yet</p>
            <p className="max-w-sm text-xs text-muted-foreground">
              This repo's CHANGELOG.md has no versioned entries yet — check back once a release has shipped.
            </p>
          </CardContent>
        </Card>
      )}

      {history.status === 'success' && versions.length > 0 && (
        <div className="grid items-start gap-4 lg:grid-cols-[17rem_1fr]">
          <VersionListSidebar
            items={versions}
            getId={(entry) => entry.version!}
            getVersion={(entry) => entry.version}
            getTimestamp={(entry) => entry.timestamp}
            selectedId={selectedVersion}
            onSelect={(entry) => setSearchParams(entry.version ? { version: entry.version } : {})}
            page={page}
            onPageChange={setPage}
            total={history.status === "success" ? history.data.total : 0}
            heightClassName="lg:h-[calc(100vh-9rem)]"
          />

          <div className="flex min-w-0 flex-col overflow-hidden rounded-xl border border-border/60 bg-card lg:h-[calc(100vh-9rem)]">
            {!selectedVersion && (
              <div className="flex flex-1 min-h-64 flex-col items-center justify-center gap-2 p-5 text-center text-sm text-muted-foreground md:p-6">
                <FileText className="size-6 text-muted-foreground/40" />
                Pick a version from the list to see its changelog.
              </div>
            )}

            {selectedVersion && (
              <div className="shrink-0 border-b border-border/40 p-5 pb-4 md:p-6 md:pb-4">
                <div className="flex flex-wrap items-center gap-2">
                  <h3 className="font-mono text-lg font-semibold tracking-tight text-foreground">
                    v{preview.status === 'success' ? preview.data.version : selectedVersion}
                  </h3>
                </div>
                <div className="mt-4 space-y-2">
                  {tabs.length > 1 && (
                    <AudienceTabs
                      tabs={tabs}
                      activeTab={audience}
                      onChange={(key) => setAudience(key as PreviewAudience)}
                    />
                  )}
                  {audienceConfig && (
                    <p className="text-xs text-muted-foreground">{audienceConfig.description}</p>
                  )}
                </div>
              </div>
            )}

            {selectedVersion && preview.status === 'loading' && (
              <div className="flex flex-1 min-h-64 flex-col items-center justify-center gap-2 p-5 text-center md:p-6">
                <Loader2 className="size-5 animate-spin text-muted-foreground/40" />
                <p className="text-xs text-muted-foreground/60">Loading changelog…</p>
              </div>
            )}

            {selectedVersion && preview.status === 'error' && (
              <div className="flex flex-1 flex-col items-center justify-center gap-2 p-5 text-center md:p-6">
                <FileWarning className="size-6 text-muted-foreground" />
                <p className="text-sm font-medium">Couldn't load this changelog</p>
                <p className="max-w-sm text-xs text-muted-foreground">{preview.error.message}</p>
              </div>
            )}

            {selectedVersion && preview.status === 'success' && preview.data.text && (
              <div className="flex-1 overflow-y-auto px-5 pb-5 md:px-6 md:pb-6">
                <ChangelogBody text={preview.data.text} />
              </div>
            )}

            {selectedVersion && preview.status === 'success' && !preview.data.text && (
              <div className="flex flex-1 min-h-64 flex-col items-center justify-center gap-2 p-5 text-center text-sm text-muted-foreground md:p-6">
                <Sparkles className="size-6 text-muted-foreground/40" />
                <p className="font-medium">No {audienceConfig?.label ?? audience} summary yet</p>
                <p className="max-w-sm text-xs">A developer needs to generate this from the Dev dashboard.</p>
              </div>
            )}
          </div>
        </div>
      )}

      {selectedVersion && preview.status === 'success' && preview.data.text && (
        <ChangelogChatWidget project={project!} repo={repo!} audience={audience} version={selectedVersion} />
      )}
    </div>
  );
}
