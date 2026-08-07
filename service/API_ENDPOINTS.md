# API Endpoints

All endpoints are JAX-RS (Quarkus/RESTEasy Reactive) resources under `com.hubsabai.changelog.api`.
Unless noted otherwise, requests/responses are JSON (`application/json`).

Three resource classes, three purposes:

| Class | Base path | Who calls it |
|---|---|---|
| `AzureDevOpsResource` | `/api` | The web dashboard (Dev/QA/Business pages) |
| `PipelineResource` | `/api/pipeline` | CI/CD release pipelines (bearer-token auth) |
| `AiBenchmarkResource` | `/api/ai/models/bench` | Manual/ad-hoc model reliability testing |

Errors are JSON `{"error": "..."}` (sometimes with an extra `"hint"` field), mapped from exceptions:

| Exception | HTTP status | When |
|---|---|---|
| `AiException` | 400 Bad Request | Invalid input (missing version, bad audience, etc.) or the AI call itself failed |
| `IllegalStateException` | 502 Bad Gateway | Azure DevOps returned something unusable (expired PAT, HTML instead of JSON) |
| `WebApplicationException` (404 upstream) | 404 Not Found | Project/repo/branch doesn't exist on Azure DevOps |
| `WebApplicationException` (other upstream) | 502 Bad Gateway | Any other Azure DevOps HTTP failure |
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

## `AiBenchmarkResource` (`/api/ai/models/bench`)

Manual tool for measuring model reliability against a real release, rather than assuming it.

### `POST /api/ai/models/bench`
- **For:** running N trials per candidate model against one real repo/version and reporting success rate + latency.
- **Request:** body `BenchmarkRequest` — `{ project, repo, branch, fromVersion, version, models: string[], trials }`. `project`, `repo`, `version` **required**; `models` defaults to the app's recommended models when omitted; `trials` defaults to `3`, clamped to `[1, 10]`.
- **Response:** `ModelBenchResult[]` — one per candidate model: `{ model, trials, successes, failures, successRatePercent, p50Ms, p95Ms, errors: string[], sampleOutput }`. `p50Ms`/`p95Ms`/`sampleOutput` are `null` if every trial for that model failed.
- **Errors:** 400 if `project`/`repo`/`version` missing, or no commits/PRs found for that version.
- **Notes:** runs every candidate model concurrently (virtual threads); each model's own trials run sequentially. Uses the "strict" generation path so a model's real failures can't be masked by the provider's automatic fallback-to-default behavior.
