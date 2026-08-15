# API Endpoints

All endpoints are JAX-RS (Quarkus/RESTEasy Reactive) resources under `com.hubsabai.changelog.api`.
Unless noted otherwise, requests/responses are JSON (`application/json`).

Five resource classes, five purposes:

| Class | Base path | Who calls it |
|---|---|---|
| `AzureDevOpsResource` | `/api` | The web dashboard (Dev/QA/Business pages), Azure DevOps provider |
| `GitHubResource` | `/api/github` | The web dashboard (Dev/QA/Business pages), GitHub provider — same surface as Azure, mounted under `/github` so the two providers' routes never collide |
| `PipelineResource` | `/api/pipeline` | Azure DevOps CI/CD release pipelines (bearer-token auth) |
| `GitHubPipelineResource` | `/api/github/pipeline` | GitHub Actions workflows (bearer-token auth) |
| `PipelineRunResource` | `/api/pipeline/runs` | Recorded run snapshots for **both** providers — lives at the root so it's provider-agnostic (`provider` is a query param, not a path segment) |
| `AiBenchmarkResource` | `/api/ai/models/bench` | Manual/ad-hoc model reliability testing |

Errors are JSON `{"error": "..."}` (sometimes with an extra `"hint"` field), mapped from exceptions:

| Exception | HTTP status | When |
|---|---|---|
| `AiException` | 400 Bad Request | Invalid input (missing version, bad audience, etc.) or the AI call itself failed |
| `IllegalStateException` | 502 Bad Gateway | A provider returned something unusable (expired PAT/token, HTML instead of JSON) or a GitHub push failed |
| `WebApplicationException` (404 upstream) | 404 Not Found | Project/repo/branch doesn't exist on Azure DevOps or GitHub |
| `WebApplicationException` (other upstream) | 502 Bad Gateway | Any other Azure DevOps / GitHub HTTP failure |
| Anything else | 500 Internal Server Error | Unexpected bug — logged server-side, message hidden from the client |

---

## `AzureDevOpsResource` (`/api`)

The dashboard's navigation + generation surface: browse projects → repos → history, and
generate/edit/restore/push changelogs for a version.

### `GET /api/projects`
- **For:** listing every project in the Azure DevOps org, to populate the project picker.
- **Request:** no parameters.
- **Response:** `ProjectSummary[]` — `{ id, name, description }`.
- **Notes:** paginates internally against Azure DevOps; the caller just gets the full list.

### `GET /api/projects/{project}/repos`
- **For:** listing a project's repositories.
- **Request:** path param `project`.
- **Response:** `RepositorySummary[]` — `{ id, name, project, defaultBranch }`.

### `GET /api/projects/{project}/work-items`
- **For:** a project's work items (not yet attributed to a specific repo).
- **Request:** path param `project`.
- **Response:** `ChangeItem[]` filtered to `type: WORK_ITEM` — see [`ChangeItem` shape](#changeitem-shape) below.

### `GET /api/projects/{project}/repos/{repo}/branches`
- **For:** the branch picker.
- **Request:** path params `project`, `repo`.
- **Response:** `string[]` of short branch names (e.g. `"main"`, `"develop"`).

### `GET /api/projects/{project}/repos/{repo}/changes`
- **For:** raw commits + PRs for a repo, either unbounded or scoped to a version range.
- **Request:** path params `project`, `repo`; query params `fromVersion` (optional), `toVersion` (optional), `branch` (optional, defaults to the repo's default branch).
- **Response:** `ReleaseData` — `{ release: {org, project, repo, branch, milestone, releaseDate}, items: ChangeItem[] }`.
- **Notes:** if `toVersion` is omitted, walks the branch tip unbounded. Otherwise resolves the tag/release-marker boundary for that version — see the connector refactor notes for how that range detection works.

### `GET /api/projects/{project}/repos/{repo}/commit-count`
- **For:** a quick count without fetching full commit/PR bodies.
- **Request:** path params `project`, `repo`; query params `version`, `branch` (optional).
- **Response:** `int` — commit count in that version's range (`0` if the version/tag can't be resolved).

### `GET /api/fetch-all`
- **For:** the "fetch everything" bulk view — walks the entire org.
- **Request:** no parameters.
- **Response:** `OrgFetchResult` — `{ org, projects: ProjectFetchResult[] }`, where each `ProjectFetchResult` is `{ project: ProjectSummary, workItems: ChangeItem[], repositories: ReleaseData[] }`.
- **Notes:** fetches every project and repo concurrently (virtual threads). Best-effort: a failure on one project/repo becomes a diagnostic `ChangeItem` inside that project/repo's own result instead of failing the whole call.

### `POST /api/projects/{project}/repos/{repo}/generate`
- **For:** the dashboard's "Generate" button — produces Developer/QA/Business changelog text for a version and caches it.
- **Request:** path params `project`, `repo`; query params:
  - `version` **(required)**
  - `model` **(required)** — must be an explicit user choice, never silently substituted
  - `fromVersion` (optional)
  - `branch` (optional)
  - `manualText` (optional) — raw `=== title` / file-path text pasted by the user instead of fetching from Azure DevOps
  - `audience` (optional) — `developer` | `qa` | `business`; omit to generate all three
  - `force` (optional, default `false`) — regenerate even if something's already cached (the "Regenerate" button)
- **Response:** `GenerateResponse` — `{ developer, qa, business, usage: AiUsage[], durationMs, saved }`. Only the requested audience's field is populated when `audience` is passed; the other two are `null`.
- **Errors:** 400 if `version`/`model` missing, or if there's nothing to generate from (no commits/PRs found for that range).
- **Notes:** QA is generated using Developer's text as context; Business uses Developer + QA. Requesting a single `audience` still transparently generates whatever it depends on if not already cached.

### `POST /api/projects/{project}/repos/{repo}/generate-stream`
- **For:** same as `/generate`, but streamed as Server-Sent Events so the UI can show each audience as it completes instead of waiting for all three.
- **Request:** same params as `/generate` minus `audience` and `force` (always generates all three, never force-regenerates).
- **Response:** `text/event-stream`. Events, in order:
  - `event: audience` × 3 — `data: { audience, text, usage }`, one per Developer/QA/Business as each finishes
  - `event: done` — `data: { durationMs, totalTokens }` (only if nothing failed)
  - `event: error` — `data: { error }` (stops the stream early if a generation fails)
- **Errors:** 400 up front (before streaming starts) if `version`/`model` missing or nothing to generate from.

### `GET /api/projects/{project}/repos/{repo}/history`
- **For:** the version list shown in Dev/QA/Business — parses the repo's CHANGELOG.md into entries.
- **Request:** path params `project`, `repo`; query param `branch` (optional).
- **Response:** `HistoryEntry[]` — `{ id, project, repo, branch, version, authors, timestamp, developer }`, newest-first.
- **Notes:** `developer` is the *best available* text — a saved edit or AI generation from Postgres if one exists for that version, otherwise the raw CHANGELOG.md body (source `"import"` — see `ChangelogCacheService`). Returns `[]` if the repo has no CHANGELOG.md.

### `GET /api/projects/{project}/repos/{repo}/pull-requests/{prId}/changelog-location`
- **For:** resolving which release (if any) a PR actually shipped in — a direct, indexed lookup against the `release_pr` table populated at pipeline-ingestion time, never a search over generated changelog text. Backs an external deep-link feature (e.g. a dashboard linking straight from a PR number to its changelog).
- **Request:** path params `project`, `repo`, `prId`.
- **Response:** `ChangelogLocationResponse` — `{ status, version, stage }`. `status` is `released`, `prerelease`, or `not_found`; `version`/`stage` are `null` when `not_found`.
- **Notes:** `prerelease` is a real, expected, non-error state — it means the PR has been reported by the pipeline but not yet promoted to an actual release, not that something went wrong.

### `GET /api/ai/models`
- **For:** populating the model-selection dropdown.
- **Request:** no parameters.
- **Response:** `AiModelOption[]` — `{ id, label, recommended }`.
- **Notes:** live list from the AI provider account; falls back to a hardcoded curated list (`AiModelCatalog.FREE_MODELS`) if that call fails or comes back empty.

### `GET /api/projects/{project}/repos/{repo}/changelog-text`
- **For:** checking whether a version+audience already has saved text, with no AI call and no Azure DevOps round-trip (lets a tab show existing content the instant it opens).
- **Request:** path params `project`, `repo`; query params `version` **(required)**, `audience` **(required: `developer`|`qa`|`business`)**.
- **Response:** `{ "text": string | null }` — `null` if nothing's saved yet.
- **Errors:** 400 if `version` missing or `audience` invalid.

### `GET /api/projects/{project}/repos/{repo}/changelog-meta`
- **For:** the footer showing "AI-generated by X" / "edited by Y at Z", whether a restore is available, and whether "Push to repo" should be shown.
- **Request:** same required params as `changelog-text`.
- **Response:** `ChangelogMeta` — `{ source, model, editedBy, at, hasPrevious, previousText, hasUnpushedChanges, pushedAt, pushedPullRequestUrl }`. All fields `null`/`false` if nothing's saved yet (a normal case, not an error).
- **Notes:** `hasUnpushedChanges` is `true` whenever the current text differs from whatever was last pushed (or nothing has ever been pushed) — always `false` for `qa`/`business`, which never push. `pushedAt`/`pushedPullRequestUrl` describe the last successful push, if any.

### `PUT /api/projects/{project}/repos/{repo}/changelog-restore`
- **For:** rolling back to whatever was current before the last edit or regeneration.
- **Request:** path params `project`, `repo`; query params `version` **(required)**, `audience` **(required)**.
- **Response:** `{ "text": string }` — the restored text.
- **Errors:** 400 if `version` missing, `audience` invalid, or there's nothing to restore.
- **Notes:** works for all three audiences (unlike push, which is developer-only) — this is a Postgres-only swap, never touches the repo.

### `PUT /api/projects/{project}/repos/{repo}/changelog-restore-pushed`
- **For:** rolling back to whatever was last successfully pushed to the repo — a separate rollback target from `changelog-restore`, which only undoes the last edit/regeneration.
- **Request:** path params `project`, `repo`; query params `version` **(required)**, `audience` **(required, must be `developer`)**.
- **Response:** `{ "text": string }` — the restored (pushed) text.
- **Errors:** 400 if `audience` isn't `developer`, `version` missing, or nothing has been pushed yet.
- **Notes:** Postgres-only swap, never touches the repo/PR. Whatever was current before this call becomes the new "previous," so it can itself be undone via `changelog-restore`.

### `GET /api/projects/{project}/repos/{repo}/has-changelog`
- **For:** showing the "CHANGELOG.md / No CHANGELOG.md" badge.
- **Request:** path params `project`, `repo`; query param `branch` (optional).
- **Response:** `boolean`.

### `GET /api/projects/{project}/repos/{repo}/changelog-preview`
- **For:** the read-only QA/Business viewer pages (no model choice — a view, not a generation action).
- **Request:** path params `project`, `repo`; query params `audience` **(required: `qa`|`business`)**, `version` (optional, defaults to latest), `branch` (optional).
- **Response:** `ChangelogPreview` — `{ project, repo, version, audience, text }`.
- **Errors:** 400 if `audience` isn't `qa`/`business`, or if there's no CHANGELOG.md / no entries / nothing reconstructable for that version.
- **Notes:** reads the same cache the dashboard writes to — never re-triggers a fresh AI call for a version someone already generated.

### `PUT /api/projects/{project}/repos/{repo}/changelog-edit`
- **For:** saving a human edit to one audience's text.
- **Request:** path params `project`, `repo`; body `ChangelogEditRequest` — `{ version, branch, audience, text, editedBy }` (`version`, `audience`, `text` required).
- **Response:** `GenerateResponse` — same shape as `/generate`, `saved: true`.
- **Errors:** 400 if `audience` invalid, `version`/`text` missing or blank.
- **Notes:** editing `developer` cascades forward — QA/Business, if not themselves hand-edited, get silently regenerated from the new Developer text so they don't go stale. A QA/Business tab that already carries its own human edit is left alone, never overwritten by the cascade.

### `POST /api/projects/{project}/repos/{repo}/changelog-push`
- **For:** the "Push to repo" button — opens a real pull request updating CHANGELOG.md on Azure DevOps.
- **Request:** path params `project`, `repo`; query params `version` **(required)**, `branch` **(required)**, `audience` **(required, must be `developer`)**.
- **Response:** `{ "pullRequestUrl": string }`.
- **Errors:** 400 if `audience` isn't `developer`, `version`/`branch` missing, nothing generated/edited yet to push, or the branch's CHANGELOG.md has no entry for that version anymore (stale page — asks the user to refresh).
- **Notes:** always via a new branch + PR, never a direct commit. Re-fetches the target branch's file fresh at push time rather than trusting what the page loaded earlier.

<a id="changeitem-shape"></a>
**`ChangeItem` shape** (used in `changes`, `work-items`, `fetch-all`):
`{ type: "WORK_ITEM"|"PULL_REQUEST"|"COMMIT", id, title, category, description, author, project, repo, date, links: string[], filePaths: string[] }`

---

## `GitHubResource` (`/api/github`)

GitHub mirror of the dashboard API. Everything except the underlying connector is shared with
Azure — the same Postgres cache, the same AI provider, the same edit/restore/push semantics. The
two structural differences:

- A GitHub **"project" is the configured owner** (an org or personal account) — GitHub has no
  per-account project tier, so `listProjects()` returns exactly one entry and the project level
  collapses to that owner everywhere else.
- GitHub **"builds" are Actions workflow runs**, and `buildId` is a `long` (GitHub run IDs exceed
  `int` range — see `PipelineRunSummary`).
- **Push is a branch + PR** (`pushChangelogEdit` opens a PR back into the source branch), unlike
  Azure's direct single-commit push — GitHub has no direct-commit API for a foreign file.

The frontend picks the provider and swaps its API base between `/api` and `/api/github`
(`web-view/src/lib/provider.ts`). No auth on any of these endpoints (same as Azure).

### `GET /api/github/projects`
- **For:** populating the project picker — always the single configured owner (`github.owner`).
- **Response:** `ProjectSummary[]` with one entry — `{ id, name, description }`.

### `GET /api/github/projects/{project}/repos`
- **For:** listing a repo owner's repositories.
- **Request:** path param `project` (the owner).
- **Response:** `RepositorySummary[]` — `{ id, name, project, defaultBranch, visibility }`.
  `visibility` is `"public"`/`"private"` (Azure returns `null`).

### `GET /api/github/projects/{project}/work-items`
- **Response:** always `[]` — GitHub has no work-item tier (kept for surface parity with Azure).

### `GET /api/github/projects/{project}/repos-with-changelog`
- **For:** QA/Business browse — repos that have a `CHANGELOG.md`/`changelog.md` on the default branch.
- **Response:** `RepositorySummary[]` filtered by `hasChangelogFileSafely` (best-effort; a fetch
  failure filters the repo out rather than failing the whole call).

### `GET /api/github/projects/{project}/repos-overview`
- **For:** the Dev dashboard's repo table.
- **Response:** `RepoOverview[]` — `{ name, defaultBranch, latestVersion, latestVersionAt, needsReviewCount }`.

### `GET /api/github/projects/{project}/repos/{repo}/release-version`
- **For:** pre-filling the "new changelog" form.
- **Response:** `ReleaseVersionResolution` — `{ latestVersion, suggestedNextVersion, currentBranchSha, changelogExists, requiresInitialVersion }`. `suggestedNextVersion` is `latestVersion`'s patch bumped, or `"1.0.0"` when no changelog exists yet.

### `GET /api/github/projects/{project}/repos/{repo}/branches`
- **Response:** `string[]` of branch short names.

### `GET /api/github/projects/{project}/repos/{repo}/changes`
- **For:** raw commits + PRs for a repo, either unbounded or scoped to a version range.
- **Request:** query params `fromVersion`, `toVersion`, `branch` (optional). Version ranges resolve
  against git tags + the Compare API instead of Azure's commit-scan heuristics.
- **Response:** `ReleaseData`.

### `GET /api/github/projects/{project}/repos/{repo}/commit-count`
- **Response:** `int` — commit count in a version's range (`0` if the version/tag can't be resolved).

### `GET /api/github/projects/{project}/repos/{repo}/builds`
- **For:** the repo's recent GitHub Actions workflow runs, mapped to the same `PipelineRunSummary`
  shape Azure's builds use.
- **Request:** query param `top` (default `20`).
- **Response:** `PipelineRunSummary[]` — `{ buildId, buildNumber, pipelineRunNumber, status, result, finishTime, sourceBranch, sourceVersion, pipelineName, prNumber, commitTitle }`.

### `GET /api/github/projects/{project}/repos/{repo}/builds/{buildId}/changes`
- **For:** a single workflow run's change items.
- **Request:** path params `project`, `repo`, `buildId`.
- **Response:** `ReleaseData`.
- **Notes:** **stored-first** — reads the recorded snapshot from `recorded_pipeline_run`
  (`provider=github`) if one exists; on a miss it performs a lazy capture
  (`getOrCaptureGitHubRun`) that persists the snapshot so the dashboard never re-fetches GitHub.

### `GET /api/github/projects/{project}/repos/{repo}/builds/{buildId}/run-context`
- **For:** the run-context inspect view — run + PR + commits + files.
- **Response:** `RunChangeContext`. Same stored-first behavior as `/builds/{buildId}/changes`.

### `GET /api/github/projects/{project}/repos/{repo}/pull-requests/{prId}/details`
- **For:** one PR's title/description/commits — live from the GitHub PR API.
- **Response:** `PullRequestDetails` — `{ prId, title, description, author, commitMessages, workItems }` (`workItems` is always empty for GitHub).

### `GET /api/github/projects/{project}/repos/{repo}/has-changelog`
- **Response:** `boolean` — whether `CHANGELOG.md`/`changelog.md` exists on the (default) branch.

### `GET /api/github/projects/{project}/repos/{repo}/pull-requests/{prId}/changelog-location`
- **For:** resolving which release (if any) a PR shipped in — direct indexed lookup against `release_pr`.
- **Response:** `ChangelogLocationResponse` — `{ status, version, stage }`, `status` = `released`|`prerelease`|`not_found`.

### `GET /api/github/projects/{project}/repos/{repo}/history`
- **For:** the version list in Dev/QA/Business.
- **Request:** query params `branch`, `page`, `limit` (defaults `0`/`10`).
- **Response:** `HistoryResponse` — `{ entries, total }`. Prefers Postgres-stored developer text
  over parsing CHANGELOG.md (same fallback chain as Azure); ungenerated merged PRs are surfaced
  as `generated: false` entries on page 0.

### `POST /api/github/projects/{project}/repos/{repo}/generate`
- **For:** the dashboard's Generate button.
- **Request:** same query params as Azure's `/generate` — `version` + `model` required, plus
  `fromVersion`, `branch`, `manualText`, `audience`, `force`, and GitHub-specific `buildId`
  (when > 0, release data comes from the recorded run snapshot, lazy-captured on a miss — never
  a live GitHub re-fetch).
- **Response:** `GenerateResponse` — `{ developer, qa, business, usage, durationMs, saved }`.

### `POST /api/github/projects/{project}/repos/{repo}/generate-stream`
- **For:** same as `/generate`, streamed as SSE (Developer only, emitted as `audience`/`done`/`error` events).
- **Request:** JSON body `GenerateStreamRequest` — `{ model, fromVersion, version, branch, manualText, force, buildId }`.

### Read/edit/restore/push (mirrors Azure)
Identical semantics to the Azure equivalents, mounted under `/api/github`:

| Method | Path | Notes |
|---|---|---|
| `GET` | `/projects/{p}/repos/{r}/changelog-text` | Saved text for version+audience, no provider round-trip |
| `GET` | `/projects/{p}/repos/{r}/changelog-meta` | Provenance + push status; includes `tokens`/`durationMs` from the latest revision |
| `GET` | `/projects/{p}/repos/{r}/changelog-preview` | Read-only QA/Business view |
| `GET` | `/projects/{p}/repos/{r}/changelog-repo-text` | Developer entry body exactly as it exists in the repo right now |
| `PUT` | `/projects/{p}/repos/{r}/changelog-edit` | Save a human edit (`ChangelogEditRequest`) |
| `PUT` | `/projects/{p}/repos/{r}/changelog-restore` | Roll back to `previous_*` |
| `PUT` | `/projects/{p}/repos/{r}/changelog-restore-pushed` | Developer-only — roll back to last pushed |
| `PUT` | `/projects/{p}/repos/{r}/changelog-revision-restore` | Roll back to an arbitrary revision (`sequence`) |
| `PUT` | `/projects/{p}/repos/{r}/generate-commit` | Persist a reviewed AI candidate (`GenerateCommitRequest`) |
| `POST` | `/projects/{p}/repos/{r}/changelog-push` | **Branch + PR** — returns `{ pullRequestUrl, commitUrl }` (both set to the PR's URL) |
| `DELETE` | `/projects/{p}/repos/{r}/changelog-revision` | Delete one shared revision (`version` + `sequence`) |
| `GET` | `/ai/models` | Live model list (shared with Azure) |

### `GET /api/github/fetch-all`
- **For:** the bulk "fetch everything" view.
- **Response:** `OrgFetchResult` — walks the configured owner's repos (best-effort, virtual threads).

---

## `PipelineRunResource` (`/api/pipeline/runs`)

Provider-agnostic read API over the recorded-run snapshot store (`recorded_pipeline_run`). Lives
at the root (not under `/github`) because the same store backs both providers — `provider` is a
query param. The frontend pins these calls to `/api` via `rootApiClient`
(`web-view/src/api/client.ts`) so GitHub mode doesn't rewrite them into `/api/github/...`.

### `GET /api/pipeline/runs`
- **For:** the Dev dashboard's "Pipeline runs" list.
- **Request:** query params `provider` (default `"azure"`), `project`, `repo`.
- **Response:** `PipelineRunSummary[]`. If nothing is recorded for that repo, falls back to live
  provider runs — `githubConnector.listWorkflowRuns` for GitHub, `azureConnector.listRecentBuilds` for Azure.

### `GET /api/pipeline/runs/{runId}`
- **Request:** query params `provider`, `project`, `repo`; path param `runId`.
- **Response:** `RecordedRunDetail` — `{ id, provider, project, repo, buildId, version, stage, branch, runMetadata, changeItems, createdAt, updatedAt }`, or `null` if not recorded.

### `GET /api/pipeline/runs/{runId}/changes`
- **For:** one recorded run's change items.
- **Response:** `ReleaseData`. Stored-first; on a miss GitHub performs a **lazy capture** that
  persists the snapshot (`getOrCaptureGitHubRun`) so the dashboard never re-fetches GitHub;
  Azure falls back to a live `fetchRunChanges`.

### `GET /api/pipeline/runs/{runId}/run-context`
- **For:** one recorded run's context (run + PR + commits + files).
- **Response:** `RunChangeContext`. Same stored-first / lazy-capture behavior as `/changes`.

---

## `PipelineResource` (`/api/pipeline`)

Called by CI/CD release pipelines, not the dashboard. **Requires** `Authorization: Bearer <key>`
where `<key>` is one of the comma-separated values in the `pipeline.api-keys` config property —
checked with a constant-time comparison. No keys configured means **nothing** can authenticate
(fails closed). Missing/invalid token → `401 Unauthorized` with an empty body.

### `POST /api/pipeline/generate`
- **For:** ingesting raw release/PR facts from a pipeline — no AI call ever happens here. Changelog text is exclusively generated later, by a human, from the dashboard (Generate/Regenerate/Edit). Two shapes of call, both AI-free:
  1. **Bundled release ingestion** (default) — records what the pipeline knows about a whole release, so a PR's release location is a lookup instead of a guess. The pipeline can supply what it knows two ways:
     - `buildId` (`$(Build.BuildId)`, recommended) — the Composer fetches that run's own commits/work items/PRs directly from Azure DevOps' Build API (`.../builds/{buildId}/changes` and `/workitems`, already scoped to "since the previous build of this pipeline definition" — no tag/semver guessing, no git log scraping). `rawCommitLog`/`workItemIds`/`prIds` are ignored when `buildId` is set.
     - `rawCommitLog`/`workItemIds`/`prIds` (legacy) — the pipeline hand-builds a commit log string and explicit id lists itself.
  2. **Per-PR raw init** (`raw: true`) — fetches one PR's own data from Azure DevOps and writes a plain, non-AI Developer changelog draft immediately, for pipelines that report at PR-merge time rather than at release time.
- **Request:** body `PipelineRequest` — `{ project, repo, branch, version, stage, buildId, rawCommitLog, workItemIds: int[], prIds: int[], model, systemPrompt, raw, pullRequestId }`. `project`, `repo`, `version` always required.
  - **Bundled ingestion** (`raw` absent/`false`): `stage` also required, must be `"prerelease"` or `"release"`. `model`/`systemPrompt` are no longer used here (kept for now, unused).
  - **Per-PR raw init** (`raw: true`): `pullRequestId` also required; `stage` not used by this path.
- **Response:** `PipelineIngestResponse` — `{ project, repo, version, stage, prCount }`. For raw init, `prCount` is always `1` and `stage` echoes back whatever (if anything) was passed.
- **Errors:** 400 if `project`/`repo`/`version` missing; for bundled ingestion, also if `stage` missing/invalid or nothing to ingest (message tells the caller to pass `buildId`, or `rawCommitLog`/`workItemIds`/`prIds`); for raw init, also if `pullRequestId` missing or that PR doesn't exist in Azure DevOps (a 404 from Azure DevOps is translated into a clear 400 here, not a raw upstream error).
- **Notes:**
  - Bundled ingestion is idempotent: re-ingesting the same project/repo/version overwrites the stored raw facts in place, no duplicate rows. Every PR found among the items is upserted into a `project/repo/pr_id → version, stage` index. A PR already recorded at `release` is never regressed back to `prerelease` by a later report.
  - Raw init is also safe to re-run (pipeline retries) — it never overwrites an entry a human has already generated with AI or edited; it only ever writes when nothing more deliberate exists yet (source `"ai"`/`"edit"`), storing its own draft under source `"raw"`.
  - Raw init works even if the AI provider (NIM) is unavailable or a configured model is removed — this path never calls it, by construction.

---

## `GitHubPipelineResource` (`/api/github/pipeline`)

The GitHub Actions counterpart of `PipelineResource`. Called by a **workflow in another repo** at
run time (e.g. on merge to `main`) to capture that run's raw, non-AI changelog snapshot. Same
`@PipelineAuth` bearer-token gate, same fail-closed behavior as Azure's pipeline endpoint.

### `POST /api/github/pipeline/generate`
- **For:** eager workflow-run intake — the Composer fetches the run's own commits/PRs from the
  GitHub API (run ID alone is not enough; GitHub requires owner + repo), builds a plain non-AI
  Developer draft, and persists the snapshot onto `recorded_pipeline_run` keyed by
  `(provider='github', project, repo, build_id)`.
- **Request:** body `GitHubPipelineRequest` — `{ runId, project, repo, version, branch }`.
  `runId` is the GitHub Actions workflow run ID (`github.run_id` in the workflow YAML); `project`
  is the owner; `repo` is the repository name. `version`/`branch` optional (raw capture is
  version-free).
- **Response:** `GitHubPipelineIngestResponse` — `{ runId, project, repo, version, branch, prCount, changelog }`.
  The calling workflow can commit `changelog` straight into its own `CHANGELOG.md`. AI-generated
  text only ever comes from the dashboard and overrides this raw draft.
- **Errors:** 400 if `runId`/`project`/`repo` missing, or the workflow run isn't found/unreachable
  on that owner/repo (bad run ID or a token without `actions:read` access). 401 without a valid
  bearer token. A run with no commits/PRs still returns **200** with an empty `changelog` (the
  snapshot is recorded; a server warning is logged).
- **Notes:**
  - **Idempotent:** re-POSTing the same `runId` upserts the stored snapshot (unique constraint
    `(provider, project, repo, build_id)`), never duplicates it.
  - **No AI call** — the raw draft is a starting Developer draft, version-free, stored for the
    dashboard's stored-first reads (`/api/github/projects/{p}/repos/{r}/builds/{id}/changes`).
  - The workflow does **not** need its own GitHub token — the Composer fetches the run with its
    own `github.token`. The caller only needs the run ID + owner + repo + a pipeline API key.
  - Requires the service's `github.token` to have `repo` + `actions:read` scopes.

Example call from a workflow:
```bash
curl -s -X POST "$CHANGELOG_SERVICE_URL/api/github/pipeline/generate" \
  -H "Authorization: Bearer $CHANGELOG_API_KEY" \
  -H "Content-Type: application/json" \
  -d '{"runId": 1234567890, "project": "my-org", "repo": "my-repo", "branch": "main"}'
```

---

## `AiBenchmarkResource` (`/api/ai/models/bench`)

Manual tool for measuring model reliability against a real release, rather than assuming it.

### `POST /api/ai/models/bench`
- **For:** running N trials per candidate model against one real repo/version and reporting success rate + latency.
- **Request:** body `BenchmarkRequest` — `{ project, repo, branch, fromVersion, version, models: string[], trials }`. `project`, `repo`, `version` **required**; `models` defaults to the app's recommended models when omitted; `trials` defaults to `3`, clamped to `[1, 10]`.
- **Response:** `ModelBenchResult[]` — one per candidate model: `{ model, trials, successes, failures, successRatePercent, p50Ms, p95Ms, errors: string[], sampleOutput }`. `p50Ms`/`p95Ms`/`sampleOutput` are `null` if every trial for that model failed.
- **Errors:** 400 if `project`/`repo`/`version` missing, or no commits/PRs found for that version.
- **Notes:** runs every candidate model concurrently (virtual threads); each model's own trials run sequentially. Uses the "strict" generation path so a model's real failures can't be masked by the provider's automatic fallback-to-default behavior.
