import { useCallback } from 'react';
import { useNavigate, useSearchParams } from 'react-router-dom';
import { Briefcase, FileText, FolderGit2, Loader2, TestTube2 } from 'lucide-react';

import { listProjects, listRepositoriesWithChangelog } from '@/api/client';
import type { PreviewAudience } from '@/api/types';
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '@/components/ui/card';
import { cn } from '@/lib/utils';
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from '@/components/ui/select';
import { Skeleton } from '@/components/ui/skeleton';
import { useQuery } from '@/hooks/useQuery';

const AUDIENCE_META: Record<PreviewAudience, { label: string; icon: typeof TestTube2; blurb: string }> = {
  qa: {
    label: 'QA',
    icon: TestTube2,
    blurb: 'What changed and what to verify for each release.',
  },
  business: {
    label: 'Business',
    icon: Briefcase,
    blurb: 'A plain-language summary of what shipped, for stakeholders.',
  },
};

export function AudienceBrowsePage({ audience }: { audience: PreviewAudience }) {
  const navigate = useNavigate();
  // In the URL (not local state) so navigating back from a repo's changelog view lands back
  // on the same project instead of an empty picker.
  const [searchParams, setSearchParams] = useSearchParams();
  const selectedProject = searchParams.get('project');
  const setSelectedProject = useCallback(
    (project: string) => setSearchParams({ project }, { replace: true }),
    [setSearchParams],
  );
  const meta = AUDIENCE_META[audience];
  const Icon = meta.icon;

  const loadProjects = useCallback(() => listProjects(), []);
  const projects = useQuery(loadProjects, []);

  const loadRepos = useCallback(async () => {
    if (!selectedProject) return [];
    return listRepositoriesWithChangelog(selectedProject);
  }, [selectedProject]);
  const repos = useQuery(loadRepos, [selectedProject]);

  return (
    <div className="space-y-5">
      <div>
        <div className="flex items-center gap-1.5 text-[10px] font-medium text-primary">
          <Icon className="size-3" />
          {meta.label} view
        </div>
        <h1 className="mt-1 text-2xl font-bold tracking-tight text-gradient">Changelogs</h1>
        <p className="mt-0.5 text-xs text-muted-foreground">{meta.blurb}</p>
      </div>

      <div className="max-w-xs space-y-1.5">
        <label className="text-xs font-medium text-muted-foreground">Project</label>
        {projects.status === 'loading' && <Skeleton className="h-9 w-full" />}
        {projects.status === 'error' && (
          <p className="text-xs text-destructive">Failed to load projects: {projects.error.message}</p>
        )}
        {projects.status === 'success' && (
          <Select value={selectedProject ?? undefined} onValueChange={setSelectedProject}>
            <SelectTrigger className="h-9 w-full gap-2">
              <FolderGit2 className="size-4 shrink-0 text-muted-foreground" />
              <SelectValue placeholder="Select a project…" />
            </SelectTrigger>
            <SelectContent>
              {projects.data.map((p) => (
                <SelectItem key={p.id} value={p.id}>
                  {p.name}
                </SelectItem>
              ))}
            </SelectContent>
          </Select>
        )}
      </div>

      {selectedProject && (
        <div className="space-y-3">
          <h2 className="text-sm font-semibold tracking-tight">Repositories with a changelog</h2>

          {repos.status === 'loading' && (
            <Card>
              <CardContent className="flex flex-col items-center gap-2 py-10 text-center">
                <Loader2 className="size-5 animate-spin text-muted-foreground/40" />
                <p className="text-xs text-muted-foreground/60">Loading repositories…</p>
              </CardContent>
            </Card>
          )}

          {repos.status === 'error' && (
            <Card>
              <CardHeader>
                <CardTitle className="text-destructive">Failed to load repositories</CardTitle>
                <CardDescription>{repos.error.message}</CardDescription>
              </CardHeader>
            </Card>
          )}

          {repos.status === 'success' && repos.data.length === 0 && (
            <Card>
              <CardContent className="flex flex-col items-center gap-2 py-8 text-center">
                <FileText className="size-6 text-muted-foreground" />
                <p className="text-sm font-medium">No changelogs yet</p>
                <p className="max-w-sm text-xs text-muted-foreground">
                  None of this project's repositories have a CHANGELOG.md yet — check back once a release has shipped.
                </p>
              </CardContent>
            </Card>
          )}

          {repos.status === 'success' && repos.data.length > 0 && (
            <div className="grid gap-3 md:grid-cols-2 lg:grid-cols-3">
              {repos.data.map((r) => (
                <button
                  key={r.id}
                  onClick={() => navigate(`/${audience}/${encodeURIComponent(selectedProject)}/${encodeURIComponent(r.name)}`)}
                  className="group flex cursor-pointer items-center gap-2.5 rounded-xl border border-border/60 bg-card p-3 text-left transition-all hover:-translate-y-0.5 hover:border-primary/40 hover:shadow-lg hover:shadow-primary/10"
                >
                  <span className="flex size-8 shrink-0 items-center justify-center rounded-lg bg-primary/10 text-primary">
                    <FileText className="size-4" />
                  </span>
                  <span className="min-w-0 flex-1">
                    <span className="flex items-center gap-1.5">
                      <span className="truncate text-xs font-medium transition-colors group-hover:text-primary">{r.name}</span>
                      {r.visibility && (
                        <span
                          className={cn(
                            "shrink-0 rounded-full border px-1.5 py-px text-[9px] font-medium capitalize",
                            r.visibility === "private"
                              ? "border-amber-500/30 bg-amber-500/10 text-amber-400"
                              : "border-emerald-500/30 bg-emerald-500/10 text-emerald-400",
                          )}
                        >
                          {r.visibility}
                        </span>
                      )}
                    </span>
                    <p className="truncate text-[10px] text-muted-foreground">View latest release</p>
                  </span>
                </button>
              ))}
            </div>
          )}
        </div>
      )}
    </div>
  );
}
