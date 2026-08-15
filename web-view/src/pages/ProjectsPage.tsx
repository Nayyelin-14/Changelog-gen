import { useCallback, useMemo } from 'react';
import { useNavigate } from 'react-router-dom';
import { FolderGit2, GitBranch, GitCommit, GitPullRequest, Layers, Sparkles } from 'lucide-react';
import { Bar, BarChart, CartesianGrid, ResponsiveContainer, Tooltip, XAxis, YAxis } from 'recharts';

import { listProjects, listRepositories } from '@/api/client';
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '@/components/ui/card';
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from '@/components/ui/select';
import { Skeleton } from '@/components/ui/skeleton';
import { useQuery } from '@/hooks/useQuery';
import { getStoredRole, roleHome } from '@/lib/role';

const AVATAR_GRADIENTS = [
  'from-blue-500 to-indigo-600',
  'from-cyan-500 to-blue-600',
  'from-sky-400 to-blue-500',
  'from-indigo-500 to-blue-600',
  'from-blue-600 to-cyan-500',
  'from-sky-500 to-indigo-500',
];

const STAT_THEMES = [
  { icon: Layers, iconWrap: 'bg-indigo-500/15 text-indigo-400' },
  { icon: GitBranch, iconWrap: 'bg-cyan-500/15 text-cyan-400' },
  { icon: GitCommit, iconWrap: 'bg-blue-500/15 text-blue-400' },
];

function StatCard({
  icon: Icon,
  iconWrap,
  label,
  value,
  description,
  delay,
}: {
  icon: React.ComponentType<{ className?: string }>;
  iconWrap: string;
  label: string;
  value: string | number;
  description: string;
  delay: number;
}) {
  return (
    <Card
      className="animate-in fade-in slide-in-from-bottom-4 fill-mode-both overflow-hidden border-border/60 transition-all hover:-translate-y-0.5 hover:shadow-lg hover:shadow-primary/5"
      style={{ animationDelay: `${delay}ms`, animationDuration: '500ms' }}
    >
      <CardHeader className="flex flex-row items-center justify-between pb-1">
        <CardTitle className="text-[10px] font-medium text-muted-foreground">{label}</CardTitle>
        <div className={`flex size-7 items-center justify-center rounded-lg ${iconWrap}`}>
          <Icon className="size-3.5" />
        </div>
      </CardHeader>
      <CardContent>
        <div className="text-2xl font-bold tracking-tight">{value}</div>
        <p className="text-[10px] text-muted-foreground">{description}</p>
      </CardContent>
    </Card>
  );
}

function ChartTooltip({ active, payload, label }: { active?: boolean; payload?: Array<{ value: number }>; label?: string }) {
  if (!active || !payload?.length) return null;
  return (
    <div className="rounded-lg border border-border/60 bg-popover px-3 py-2 text-xs shadow-md">
      <p className="font-medium text-popover-foreground">{label}</p>
      <p className="text-muted-foreground">{payload[0].value} repositories</p>
    </div>
  );
}

export function ProjectsPage() {
  const navigate = useNavigate();
  const base = roleHome(getStoredRole() ?? 'dev');

  const loadProjects = useCallback(() => listProjects(), []);
  const projects = useQuery(loadProjects, []);
  // useQuery returns a brand-new result object every render (even when nothing changed) — using
  // `projects` itself as a dep below would re-fire this effect on every single render forever
  // (each fetch resolving triggers a re-render, which creates a new `projects` object, which looks
  // like a changed dep, which re-fires the effect...). Depending on the actual data reference
  // instead is stable across renders that don't correspond to a real new fetch.
  const projectsData = projects.status === 'success' ? projects.data : undefined;

  // Reuses the projects query's own result instead of fetching the project list a second
  // time — waits for it to succeed rather than re-requesting the same data independently.
  const loadAllRepos = useCallback(async () => {
    if (!projectsData) return [];
    const results = await Promise.allSettled(projectsData.map((p) => listRepositories(p.id)));
    return results.map((r, i) => ({
      project: projectsData[i].name,
      // null (not []) marks a failed fetch, distinct from a project that genuinely has zero repos.
      repos: r.status === 'fulfilled' ? r.value : null,
    }));
  }, [projectsData]);
  const repoBreakdown = useQuery(loadAllRepos, [projectsData]);

  const failedRepoFetches =
    repoBreakdown.status === 'success' ? repoBreakdown.data.filter((p) => p.repos === null).length : 0;

  const totalProjects = projects.status === 'success' ? projects.data.length : 0;
  const totalRepos =
    repoBreakdown.status === 'success'
      ? repoBreakdown.data.reduce((n, p) => n + (p.repos?.length ?? 0), 0)
      : 0;

  const chartData = useMemo(() => {
    if (repoBreakdown.status !== 'success') return [];
    return repoBreakdown.data
      .map((p) => ({ name: p.project, repos: p.repos?.length ?? 0 }))
      .sort((a, b) => b.repos - a.repos)
      .slice(0, 8);
  }, [repoBreakdown]);

  const recentProjects = useMemo(() => {
    if (projects.status !== 'success') return [];
    return projects.data.slice(0, 6);
  }, [projects]);

  const isLoading = projects.status === 'loading' || repoBreakdown.status === 'loading';

  return (
    <div className="space-y-5">
      <div className="animate-in fade-in slide-in-from-bottom-2 flex flex-wrap items-end justify-between gap-4">
        <div>
          <div className="flex items-center gap-1.5 text-[10px] font-medium text-primary">
            <Sparkles className="size-3" />
            Azure DevOps · datasabai
          </div>
          <h1 className="mt-1 text-2xl font-bold tracking-tight text-gradient">Dashboard</h1>
          <p className="mt-0.5 text-xs text-muted-foreground">
            Overview of your organization&apos;s projects, repositories, and changelog activity.
          </p>
        </div>

        <div className="flex flex-col items-start gap-1.5 sm:items-end">
          <span className="text-[10px] font-medium text-muted-foreground">Jump to a project</span>
          {projects.status === 'loading' && <Skeleton className="h-9 w-full max-w-56" />}
          {projects.status === 'success' && (
            <Select onValueChange={(v) => navigate(`${base}/projects/${encodeURIComponent(v)}`)}>
              <SelectTrigger className="h-9 w-full max-w-56 gap-2 rounded-xl border-border/60 bg-card px-3.5 text-xs shadow-sm transition-colors hover:border-primary/40 focus:ring-1 focus:ring-ring">
                <FolderGit2 className="size-4 shrink-0 text-muted-foreground" />
                <SelectValue placeholder="Select a project…" />
              </SelectTrigger>
              <SelectContent align="end">
                {projects.data.map((p) => (
                  <SelectItem key={p.id} value={p.id}>
                    {p.name}
                  </SelectItem>
                ))}
              </SelectContent>
            </Select>
          )}
        </div>
      </div>

      {isLoading ? (
        <div className="grid gap-3 md:grid-cols-3">
          {Array.from({ length: 3 }, (_, i) => (
            <Card key={i}>
              <CardHeader className="pb-2">
                <Skeleton className="h-4 w-24" />
              </CardHeader>
              <CardContent>
                <Skeleton className="h-8 w-16" />
                <Skeleton className="mt-2 h-3 w-32" />
              </CardContent>
            </Card>
          ))}
        </div>
      ) : (
        <div className="grid gap-3 md:grid-cols-3">
          <StatCard
            icon={STAT_THEMES[0].icon}
            iconWrap={STAT_THEMES[0].iconWrap}
            label="Projects"
            value={totalProjects}
            description="Total Azure DevOps projects"
            delay={0}
          />
          <StatCard
            icon={STAT_THEMES[1].icon}
            iconWrap={STAT_THEMES[1].iconWrap}
            label="Repositories"
            value={totalRepos}
            description="Across all projects"
            delay={80}
          />
          <StatCard
            icon={STAT_THEMES[2].icon}
            iconWrap={STAT_THEMES[2].iconWrap}
            label="Avg. per project"
            value={totalProjects ? (totalRepos / totalProjects).toFixed(1) : '—'}
            description="Repositories per project"
            delay={160}
          />
        </div>
      )}

      {failedRepoFetches > 0 && (
        <p className="text-[10px] text-destructive">
          Couldn't load the repository count for {failedRepoFetches} project{failedRepoFetches === 1 ? '' : 's'} —
          totals above may be undercounted.
        </p>
      )}

      {projects.status === 'success' && (
        <>
          <div className="animate-in fade-in slide-in-from-bottom-2 fill-mode-both" style={{ animationDelay: '220ms' }}>
            <h2 className="text-sm font-semibold tracking-tight">Projects</h2>
            <p className="text-[10px] text-muted-foreground">Select a project to browse its repositories.</p>
          </div>

          <div className="grid gap-3 md:grid-cols-2 lg:grid-cols-3">
            {recentProjects.map((p, i) => (
              <button
                key={p.id}
                onClick={() => navigate(`${base}/projects/${encodeURIComponent(p.id)}`)}
                className="group animate-in fade-in slide-in-from-bottom-4 fill-mode-both relative overflow-hidden rounded-xl border border-border/60 bg-card p-3 text-left transition-all duration-300 hover:-translate-y-0.5 hover:border-primary/40 hover:shadow-lg hover:shadow-primary/10"
                style={{ animationDelay: `${300 + i * 60}ms` }}
              >
                <div
                  className={`pointer-events-none absolute -top-6 -right-6 size-20 rounded-full bg-linear-to-br ${AVATAR_GRADIENTS[i % AVATAR_GRADIENTS.length]} opacity-0 blur-2xl transition-opacity duration-300 group-hover:opacity-25`}
                />
                <div className="relative flex items-center gap-2.5">
                  <div
                    className={`flex size-8 shrink-0 items-center justify-center rounded-lg bg-linear-to-br ${AVATAR_GRADIENTS[i % AVATAR_GRADIENTS.length]} text-xs font-bold text-white shadow-sm transition-transform group-hover:scale-105`}
                  >
                    {p.name.charAt(0).toUpperCase()}
                  </div>
                  <div className="min-w-0 flex-1">
                    <p className="truncate text-xs font-medium transition-colors group-hover:text-primary">{p.name}</p>
                    {p.description && <p className="truncate text-[10px] text-muted-foreground">{p.description}</p>}
                  </div>
                  <GitPullRequest className="size-3.5 shrink-0 text-muted-foreground/40 transition-all group-hover:translate-x-0.5 group-hover:text-primary/60" />
                </div>
              </button>
            ))}
          </div>
        </>
      )}

      {chartData.length > 0 && (
        <Card className="animate-in fade-in slide-in-from-bottom-4 fill-mode-both border-border/60" style={{ animationDelay: '500ms' }}>
          <CardHeader>
            <CardTitle className="flex items-center gap-2 text-sm">
              <GitPullRequest className="size-3.5 text-primary" />
              Repositories by project
            </CardTitle>
            <CardDescription className="text-xs">Where most of your repos live, at a glance.</CardDescription>
          </CardHeader>
          <CardContent>
            <ResponsiveContainer width="100%" height={180}>
              <BarChart data={chartData} margin={{ top: 4, right: 8, left: -20, bottom: 0 }}>
                <defs>
                  <linearGradient id="repoBar" x1="0" y1="0" x2="0" y2="1">
                    <stop offset="0%" stopColor="var(--chart-1)" stopOpacity={0.95} />
                    <stop offset="100%" stopColor="var(--chart-1)" stopOpacity={0.5} />
                  </linearGradient>
                </defs>
                <CartesianGrid strokeDasharray="3 3" stroke="var(--border)" vertical={false} />
                <XAxis
                  dataKey="name"
                  tick={{ fontSize: 12, fill: 'var(--muted-foreground)' }}
                  tickLine={false}
                  axisLine={{ stroke: 'var(--border)' }}
                  interval="preserveStartEnd"
                  angle={-20}
                  textAnchor="end"
                  height={60}
                />
                <YAxis tick={{ fontSize: 12, fill: 'var(--muted-foreground)' }} tickLine={false} axisLine={false} allowDecimals={false} />
                <Tooltip content={<ChartTooltip />} cursor={{ fill: 'var(--muted)', opacity: 0.4 }} />
                <Bar dataKey="repos" fill="url(#repoBar)" radius={[6, 6, 0, 0]} maxBarSize={48} animationDuration={800} />
              </BarChart>
            </ResponsiveContainer>
          </CardContent>
        </Card>
      )}

      {projects.status === 'error' && (
        <Card>
          <CardHeader>
            <CardTitle className="text-destructive">Failed to load projects</CardTitle>
            <CardDescription>{projects.error.message}</CardDescription>
          </CardHeader>
        </Card>
      )}
    </div>
  );
}
