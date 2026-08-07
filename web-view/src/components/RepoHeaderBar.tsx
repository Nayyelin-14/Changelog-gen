import {
  CheckCircle2,
  FolderGit2,
  GitBranch,
  GitCommit,
  GitPullRequest,
  Users,
} from "lucide-react";

import type { RepositorySummary } from "@/api/types";
import { ErrorView } from "@/components/StatusView";
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from "@/components/ui/select";
import { Skeleton } from "@/components/ui/skeleton";
import type { QueryState } from "@/hooks/useQuery";
import { cn } from "@/lib/utils";

interface ActivitySummary {
  commits: number;
  pullRequests: number;
  contributors: number;
}

interface RepoHeaderBarProps {
  repo: string;
  repos: QueryState<RepositorySummary[]>;
  onRepoChange: (repoName: string) => void;
  changelogStatus: QueryState<boolean>;
  selectedBranch: string | undefined;
  summary: QueryState<ActivitySummary>;
}

export function RepoHeaderBar({
  repo,
  repos,
  onRepoChange,
  changelogStatus,
  selectedBranch,
  summary,
}: RepoHeaderBarProps) {
  return (
    <div className="flex flex-wrap items-center justify-between gap-x-6 gap-y-2">
      <div className="flex flex-wrap items-center gap-x-4 gap-y-1.5">
        {repos.status === "loading" ? (
          <Skeleton className="h-4 w-28" />
        ) : repos.status === "error" ? (
          <ErrorView message={repos.error.message} />
        ) : (
          <Select value={repo} onValueChange={onRepoChange}>
            <SelectTrigger className="h-auto w-auto gap-2 border-0 bg-transparent p-0 text-sm font-semibold text-foreground shadow-none hover:text-foreground focus:ring-0 [&_svg]:opacity-70">
              <FolderGit2 className="size-4 text-muted-foreground/80" />
              <SelectValue>{repo}</SelectValue>
            </SelectTrigger>
            <SelectContent>
              {repos.data.map((r) => (
                <SelectItem key={r.id} value={r.name}>
                  <span className="flex items-center gap-2">
                    <FolderGit2 className="size-4 text-muted-foreground" />
                    {r.name}
                  </span>
                </SelectItem>
              ))}
            </SelectContent>
          </Select>
        )}

        {selectedBranch && (
          <span className="flex items-center gap-1.5 text-xs text-muted-foreground">
            <GitBranch className="size-3" />
            {selectedBranch}
          </span>
        )}

        {changelogStatus.status === "success" && (
          <span
            className={cn(
              "flex items-center gap-1 text-xs",
              changelogStatus.data ? "text-green-600 dark:text-green-400" : "text-muted-foreground/70",
            )}
          >
            <CheckCircle2 className="size-3" />
            {changelogStatus.data ? "CHANGELOG.md" : "No changelog"}
          </span>
        )}
      </div>

      {summary.status === "success" && (
        <div className="flex flex-wrap items-center gap-x-3 gap-y-1">
          {[
            { label: "Commits", value: summary.data.commits, icon: GitCommit, color: "text-indigo-500 dark:text-indigo-400" },
            { label: "PRs", value: summary.data.pullRequests, icon: GitPullRequest, color: "text-cyan-600 dark:text-cyan-400" },
            { label: "Contributors", value: summary.data.contributors, icon: Users, color: "text-blue-600 dark:text-blue-400" },
          ].map((stat) => (
            <span
              key={stat.label}
              className="flex items-center gap-1 text-xs text-muted-foreground/80"
            >
              <stat.icon className={cn("size-3", stat.color)} />
              <span className="font-medium tabular-nums text-muted-foreground">
                {stat.value}
              </span>
              <span className="hidden sm:inline">{stat.label.toLowerCase()}</span>
            </span>
          ))}
        </div>
      )}
      {summary.status === "loading" ||
        (summary.status === "error" &&
          summary.error.message === "repo-not-ready" && (
            <div className="flex flex-wrap gap-3">
              {Array.from({ length: 3 }, (_, i) => (
                <Skeleton key={i} className="h-3 w-12" />
              ))}
            </div>
          ))}
      {summary.status === "error" &&
        summary.error.message !== "repo-not-ready" && (
          <p className="text-xs text-destructive">Failed to load activity.</p>
        )}
    </div>
  );
}
