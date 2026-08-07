import { useEffect, useState } from "react";
import { useNavigate, useParams } from "react-router-dom";
import { Loader2, FileWarning, Clock } from "lucide-react";

import { getChangelogLocation, listHistory } from "@/api/client";
import { setStoredRole } from "@/lib/role";
import { Card, CardContent } from "@/components/ui/card";
import { Button } from "@/components/ui/button";

type State =
  | { kind: "loading" }
  | { kind: "not_found" }
  | { kind: "prerelease"; version: string }
  | { kind: "error"; message: string };

/** Deep-link entry point: resolves "PR !1149" to a release via release_pr index and redirects
 * into the matching Dev history entry. Unguarded by RouteGuard — sets the role itself rather
 * than bouncing to role-select on a cold click. */
export function PrRedirectPage() {
  const { project, repo, prId } = useParams<{ project: string; repo: string; prId: string }>();
  const navigate = useNavigate();
  const [state, setState] = useState<State>({ kind: "loading" });

  useEffect(() => {
    if (!project || !repo || !prId) {
      setState({ kind: "error", message: "Missing project, repo, or PR number in the link." });
      return;
    }
    let cancelled = false;

    (async () => {
      try {
        const location = await getChangelogLocation(project, repo, prId);
        if (cancelled) return;

        if (location.status === "not_found") {
          setState({ kind: "not_found" });
          return;
        }
        if (location.status === "prerelease") {
          setState({ kind: "prerelease", version: location.version ?? "?" });
          return;
        }

        // released — find the matching history entry so we can jump straight to it. A large
        // limit is used instead of real pagination since even the biggest repo we've seen has a
        // few hundred versions, well under this.
        const history = await listHistory(project, repo, undefined, 0, 1000);
        if (cancelled) return;
        const entry = history.entries.find((e) => e.version === location.version);
        if (!entry) {
          setState({
            kind: "error",
            message: `v${location.version} was reported as released, but no matching history entry was found.`,
          });
          return;
        }

        setStoredRole("dev");
        navigate(
          `/dev/projects/${encodeURIComponent(project)}/repos/${encodeURIComponent(repo)}/history/${encodeURIComponent(entry.id)}`,
          { replace: true },
        );
      } catch (e) {
        if (cancelled) return;
        const message = e instanceof Error ? e.message : "Failed to resolve this PR's changelog.";
        setState({ kind: "error", message });
      }
    })();

    return () => {
      cancelled = true;
    };
  }, [project, repo, prId, navigate]);

  if (state.kind === "loading") {
    return (
      <div className="flex min-h-[60vh] flex-col items-center justify-center gap-2 text-sm text-muted-foreground">
        <Loader2 className="size-5 animate-spin" />
        Looking up PR !{prId}…
      </div>
    );
  }

  if (state.kind === "prerelease") {
    return (
      <div className="flex min-h-[60vh] items-center justify-center p-6">
        <Card className="max-w-md">
          <CardContent className="flex flex-col items-center gap-2 py-8 text-center">
            <Clock className="size-6 text-muted-foreground" />
            <p className="text-sm font-medium">Not released yet</p>
            <p className="text-xs text-muted-foreground">
              PR !{prId} has been reported by the pipeline as part of prerelease build {state.version}, but
              hasn't been promoted to a release yet — check back once it ships.
            </p>
          </CardContent>
        </Card>
      </div>
    );
  }

  if (state.kind === "not_found") {
    return (
      <div className="flex min-h-[60vh] items-center justify-center p-6">
        <Card className="max-w-md">
          <CardContent className="flex flex-col items-center gap-2 py-8 text-center">
            <FileWarning className="size-6 text-muted-foreground" />
            <p className="text-sm font-medium">No changelog found for PR !{prId}</p>
            <p className="text-xs text-muted-foreground">
              No pipeline has ever reported this PR for {project}/{repo} — it may not exist, be in a
              different repo, or its commit didn't carry a resolvable PR reference. If the PR itself
              is real, you can still generate a changelog from its actual commits/work items below.
            </p>
            <Button
              className="mt-2"
              onClick={() => {
                setStoredRole("dev");
                navigate(
                  `/dev/projects/${encodeURIComponent(project!)}/repos/${encodeURIComponent(repo!)}/generate?prId=${encodeURIComponent(prId!)}`,
                );
              }}
            >
              Generate changelog for this PR
            </Button>
          </CardContent>
        </Card>
      </div>
    );
  }

  return (
    <div className="flex min-h-[60vh] items-center justify-center p-6">
      <Card className="max-w-md">
        <CardContent className="flex flex-col items-center gap-2 py-8 text-center">
          <FileWarning className="size-6 text-muted-foreground" />
          <p className="text-sm font-medium">Couldn't open this changelog</p>
          <p className="text-xs text-muted-foreground">{state.message}</p>
        </CardContent>
      </Card>
    </div>
  );
}
