# Changelog Composer

![Java](https://img.shields.io/badge/Java-25-orange)
![Quarkus](https://img.shields.io/badge/Quarkus-3.37.1-blueviolet)
![React](https://img.shields.io/badge/React-19-61DAFB)

AI-assisted tool that turns Azure DevOps **or GitHub** activity into audience-specific changelogs: **Developer**, **QA**, and **Business/External**. Supports two integration modes:

- **Pipeline mode** — a repo's CI/CD calls `POST /api/pipeline/generate` (authenticated). **No AI call happens here** — it only ingests raw release/PR facts (which PR shipped in which release, at what stage) so they're an indexed lookup later, or writes a plain non-AI Developer draft at PR-merge time. Actual changelog text (Developer/QA/Business) is always generated later, by a human, from the dashboard.
- **Dashboard mode** — a React SPA split into three role-based areas (`/dev`, `/qa`, `/business`), each with its own home and navigation. Dev can browse projects/repos, generate/edit/restore all three audiences, and push a developer changelog to the repo as a PR. QA can generate/edit/restore QA and business text. Business gets a read-only preview.

> Role selection is a **temporary**, client-side-only stand-in (localStorage, no backend enforcement) — see [`AUTH.md`](./AUTH.md) for exactly what it does and what it must become once real SSO/Keycloak is integrated.

---

## Tech stack

| Layer | Technology | Notes |
|-------|------------|-------|
| Backend | Java 25 + Quarkus 3.37.1 | REST API, Azure DevOps + GitHub ingestion, AI generation, Postgres caching |
| Frontend | React 19 + TypeScript 6 + Vite 8 + Tailwind CSS 4 + shadcn/ui | Dashboard SPA |
| Source control | Azure DevOps REST API (v7.1) **and** GitHub REST API | Provider chosen in the UI header (stored in localStorage); each has its own connector |
| LLM provider | NVIDIA NIM (OpenAI-compatible) | Configurable model + fallback chain |
| Storage | PostgreSQL (Neon) + Flyway + Hibernate Panache | `generated_changelog` table with input-hash staleness detection |
| Build | Maven (quarkus-maven-plugin) + npm (Vite) | |

---

## Project structure

```
changelog-composer/
├── service/                          # Quarkus backend
│   ├── pom.xml
│   └── src/main/java/com/hubsabai/changelog/
│       ├── ai/                       # AI provider (NimAiProvider), prompts, model bench
│       ├── api/                      # REST endpoints + DTOs + auth filter + exception mappers
│       ├── connector/                # SourceConnector interface, ConnectionConfig, AzureDevOpsConnector (pipeline mode)
│       ├── connector/azuredevops/    # Org-wide REST client, discovery, change classification (dashboard mode)
│       ├── connector/azuredevops/dto # Azure DevOps wire DTOs, incl. PR/push (CreatePullRequestRequest, GitPushRequest, ...)
│       ├── connector/github/         # GitHub REST client + org connector (owner = project, workflow runs = builds)
│       ├── core/model/               # ChangeItem, ReleaseData, ProjectSummary, PrReference, etc.
│       └── storage/                  # ChangelogCacheService, GeneratedChangelog, InputHash, RawRelease(Service), ReleasePr
├── web-view/                         # React frontend
│   ├── package.json
│   └── src/
│       ├── App.tsx, main.tsx         # Router (role-gated /dev, /qa, /business) + entry
│       ├── api/client.ts             # All REST calls + SSE stream
│       ├── pages/                    # RoleSelectPage, ProjectsPage, GenerateChangelogPage, GenerateNewChangelogPage,
│       │                             # AudienceBrowsePage, AudienceChangelogPage (shared QA/business viewer),
│       │                             # PrRedirectPage (deep link from a PR number to its changelog), NotFoundPage
│       ├── components/               # Layout, Header, RouteGuard, AudienceTabs, RepoHeaderBar, VersionListSidebar,
│       │                             # RestoreConfirmDialog, ConfirmDialog, ChangelogDiff, ChangelogBody,
│       │                             # ChangelogMetaSpans, StatusView, shadcn/ui primitives
│       ├── hooks/                    # useChangelogEditor (shared Dev/QA edit/generate/restore logic), useQuery
│       └── lib/                      # role.ts (localStorage role), historyTabs.ts, utils.ts
└── AUTH.md                           # What's temporary in the current auth/role setup and what replaces it
```

---

## Backend packages

| Package | Responsibility |
|---|---|
| `connector` | `SourceConnector` interface, `ConnectionConfig`, `AzureDevOpsConnector` (pipeline mode — parses a raw `git log` blob passed in by CI). |
| `connector.azuredevops` | Org-wide discovery for dashboard mode: `AzureDevOpsOrgConnector` (discovers everything via REST API, also handles the changelog push/PR flow), `AzureDevOpsRestClient`, `AzureDevOpsAuthFilter`, `ChangeCategoryClassifier`, `WorkItemFields`. |
| `connector.github` | GitHub mirror of dashboard-mode discovery: `GitHubOrgConnector` (the configured owner acts as the single "project"; workflow runs map to builds; tags + Compare API replace Azure's commit-scan), `GitHubOrgRestClient`, `GitHubOrgAuthFilter` (injects `github.token`), `ChangelogMarkdown`. Push is branch + PR via the Git Data API. |
| `connector.azuredevops.dto` | Azure DevOps wire DTOs — commits/repos/work-items plus PR/push types (`CreatePullRequestRequest`, `GitPushRequest`, `GitRef`, `RefUpdate`, `RefUpdateResult`, `PullRequestResponse`). |
| `core.model` | `ChangeItem` (single change), `ReleaseData` (release scope + items), `ProjectSummary`, `RepositorySummary`, `OrgFetchResult`, `PrReference` (extracts PR number references from commit messages). |
| `ai` | `NimAiProvider` — calls NVIDIA NIM `/chat/completions` with configurable model + up to 4 fallback models, temperature 0.3, per-audience prompts. Supports `generateStream` (SSE) and `generateForAudienceStrict` (no fallback, used by benchmark). `AiBenchmarkResource` at `POST /api/ai/models/bench` runs N trials per model and reports success rate + latency percentiles. |
| `storage` | `ChangelogCacheService` — Postgres-backed, keyed by `(project, repo, version, audience)`. Each row holds both a `current_*` and a `previous_*` slot (text/source/model/inputHash/editedBy/at), so `restorePrevious` can swap them back atomically — restoring twice returns to the original state. `current_source` is `"ai"`, `"edit"`, or `"import"` (a version's text copied straight from the repo's `CHANGELOG.md`, never generated/edited here — see `GET /history`). `InputHash` computes SHA-256 of change items; cache returns hit only if hashes match (detects content changes within the same version string). Atomic upsert via `INSERT ... ON CONFLICT DO UPDATE`. `RawReleaseService`/`RawRelease`/`ReleasePr` — raw facts + a PR→release index the pipeline reports via `/pipeline/generate` (see below). `RecordedRunService`/`RecordedPipelineRun` — a per-provider workflow-run snapshot store (`provider, project, repo, build_id`), written eagerly by `/api/github/pipeline/generate` or lazily by the dashboard, so stored run data is never re-fetched from GitHub. |
| `api` | `PipelineResource` (`POST /api/pipeline/generate`, `@PipelineAuth` Bearer token — raw ingestion only, no AI, no auth for other endpoints), `AzureDevOpsResource` (all dashboard CRUD + generation + edit/restore/push, no auth), `GitHubResource` (same dashboard surface mounted at `/api/github`), `GitHubPipelineResource` (`POST /api/github/pipeline/generate`, same auth gate), `PipelineRunResource` (provider-agnostic recorded-run reads at `/api/pipeline/runs`), `AiBenchmarkResource`. |

---

## REST endpoints

All at `/api`. No spec doc currently exists in-repo (the endpoints below are the source of truth) — auth details are in [`AUTH.md`](./AUTH.md). Every dashboard endpoint exists **twice**: under `/api` for Azure DevOps and under `/api/github` for GitHub (the frontend picks the provider and swaps its API base — `web-view/src/lib/provider.ts`). The table below shows the Azure paths; prepend `/github` for the GitHub mirror.

| Method | Path | Auth | Purpose |
|--------|------|------|---------|
| `POST` | `/pipeline/generate` | Bearer token | Azure pipeline entry point — **no AI call happens here.** Ingests raw release/PR facts (bundled ingestion, the default) or writes a plain non-AI Developer draft for one PR (`raw: true`). Real Developer/QA/Business text is only ever generated later, by a human, from the dashboard. See [Full API reference](./service/API_ENDPOINTS.md) for the two request shapes. |
| `POST` | `/github/pipeline/generate` | Bearer token | GitHub Actions counterpart — the **GitHub-side flow**: a workflow in another repo POSTs its `github.run_id` + owner + repo, and the Composer fetches the run's commits/PRs, records the snapshot, and returns a raw non-AI `changelog` the workflow can commit straight into its own `CHANGELOG.md`. Idempotent per run ID, no AI call. See [Full API reference](./service/API_ENDPOINTS.md). |
| `GET` | `/pipeline/runs` | None | Recorded workflow-run list for a repo (`provider` query param selects `azure`/`github`); falls back to live provider runs when nothing's recorded yet |
| `GET` | `/projects` | None | List all projects in the org (GitHub: the single configured owner) |
| `GET` | `/projects/{project}/repos` | None | List repos in a project |
| `GET` | `/projects/{project}/work-items` | None | List work items for a project (GitHub: always empty — no work-item tier) |
| `GET` | `/projects/{project}/repos/{repo}/branches` | None | List branches for a repo |
| `GET` | `/projects/{project}/repos/{repo}/changes` | None | Fetch commits + PRs + work items for a release range |
| `GET` | `/projects/{project}/repos/{repo}/commit-count` | None | Commit/PR/contributor activity stats for a repo |
| `GET` | `/projects/{project}/repos/{repo}/builds` | None | Recent workflow runs / builds for a repo |
| `GET` | `/projects/{project}/repos/{repo}/builds/{buildId}/changes` | None | One build/run's change items (GitHub: stored-first, lazy-captures on a miss) |
| `GET` | `/projects/{project}/repos/{repo}/builds/{buildId}/run-context` | None | One build/run's context — run + PR + commits + files |
| `GET` | `/projects/{project}/repos/{repo}/release-version` | None | Latest semver from CHANGELOG.md, suggested next version, current branch SHA |
| `GET` | `/projects/{project}/repos/{repo}/history` | None | Version list for a repo — parses `CHANGELOG.md` |
| `GET` | `/projects/{project}/repos/{repo}/pull-requests/{prId}/changelog-location` | None | Which release (if any) a PR shipped in — indexed lookup, not a text search |
| `GET` | `/projects/{project}/repos/{repo}/changelog-preview` | None | Preview a previously generated QA or business changelog |
| `GET` | `/projects/{project}/repos/{repo}/changelog-text` | None | Raw current text for a version/audience (no AI call, no staleness check) |
| `GET` | `/projects/{project}/repos/{repo}/changelog-meta` | None | Provenance for a version/audience — source, model, editedBy, `hasPrevious`, `previousText`, push status |
| `GET` | `/projects/{project}/repos/{repo}/changelog-repo-text` | None | The developer entry's body exactly as it exists in the repo's `CHANGELOG.md` right now — read-only, for the push confirmation diff |
| `PUT` | `/projects/{project}/repos/{repo}/changelog-edit` | None | Save a human edit for a version/audience |
| `PUT` | `/projects/{project}/repos/{repo}/changelog-restore` | None | Roll back a version/audience to its `previous_*` slot |
| `PUT` | `/projects/{project}/repos/{repo}/changelog-restore-pushed` | None | Developer-only — roll back to whatever was last successfully pushed to the repo |
| `PUT` | `/projects/{project}/repos/{repo}/changelog-revision-restore` | None | Developer-only — roll back to an arbitrary past revision |
| `POST` | `/projects/{project}/repos/{repo}/changelog-push` | None | Developer-only — opens a PR against the repo with the edited developer entry merged into `CHANGELOG.md` (Azure: direct commit; GitHub: branch + PR) |
| `DELETE` | `/projects/{project}/repos/{repo}/changelog-revision` | None | Delete one shared revision |
| `GET` | `/projects/{project}/repos/{repo}/has-changelog` | None | Check if CHANGELOG.md exists in the repo |
| `POST` | `/projects/{project}/repos/{repo}/generate` | None | On-demand generation for any audience |
| `POST` | `/projects/{project}/repos/{repo}/generate-stream` | None | SSE streaming generation (emits `audience`/`error`/`done` events) |
| `GET` | `/ai/models` | None | Live model list from NVIDIA (filtered, sorted) |
| `POST` | `/ai/models/bench` | None | Benchmark models against real release data |
| `GET` | `/fetch-all` | None | Walk entire org, return all projects/repos/items |

> All of the above except `/pipeline/generate` and `/github/pipeline/generate` are currently **unauthenticated** — see [`AUTH.md`](./AUTH.md) §3 for the planned `@RolesAllowed` mapping once real SSO lands. Full request/response shapes: [`service/API_ENDPOINTS.md`](./service/API_ENDPOINTS.md).

---

## Pipeline flow

**No AI call ever happens in the pipeline path.** That was a deliberate change from an earlier version of this app, which generated the developer changelog synchronously, in the pipeline's own request, blocking on however long the AI took. Real changelog text is now exclusively a human action from the dashboard (Generate/Regenerate/Edit) — the pipeline only reports raw facts.

**Azure** — two shapes of call:

1. **Bundled release ingestion** (default) — the pipeline reports what it knows about a whole release (`project`, `repo`, `version`, `stage`, plus PR/work-item ids). `PipelineAuthFilter` validates the Bearer token first (constant-time compare). This is stored as raw facts (`raw_release` table) and every PR in it is indexed into `release_pr` — so "which release did PR !N ship in" (`GET /pull-requests/{prId}/changelog-location`) is a direct lookup, never a text search. Re-ingesting the same version overwrites in place (safe to retry).
2. **Per-PR raw init** (`raw: true`) — for pipelines that report at PR-merge time instead: fetches that one PR's own data from Azure DevOps and writes a plain, non-AI Developer changelog draft immediately (source `"raw"`), so the Developer tab has *something* to show before anyone generates. Never overwrites an entry that's already been AI-generated or human-edited.

**GitHub** — one shape of call:

1. **Workflow-run capture** (`POST /api/github/pipeline/generate`) — a workflow in the repo (or another repo) POSTs its `github.run_id` plus owner + repo. The Composer fetches the run's own commits/PRs from the GitHub API with its own `github.token` (the caller needs no token), builds a raw non-AI `changelog`, returns it for the workflow to commit into its own `CHANGELOG.md`, and records the snapshot on `recorded_pipeline_run` keyed by `(provider=github, project, repo, run_id)`. Idempotent per run ID (re-POST overwrites). The dashboard then reads that stored snapshot (`GET /builds/{runId}/changes`, `/run-context`) without ever re-fetching GitHub. Version-free raw capture — AI/editorial output stays a separate, version-keyed dashboard flow.

QA and business are **never** touched by the pipeline at all — they're triggered on demand from the dashboard (`POST /generate` or `/generate-stream`), by a QA or Dev user, whenever someone actually wants to see them.

---

## Editing, restore, and push-to-repo

Every generated (or edited) entry is stored with both a `current_*` and a `previous_*` slot (see `V3__current_previous_slots.sql`):

- **Edit** (`PUT /changelog-edit`) overwrites `current_*` for a version/audience, moving the prior value into `previous_*`.
- **Restore** (`PUT /changelog-restore`) swaps `previous_*` back into `current_*` — available for all three audiences, symmetric (restoring twice is a no-op). `GET /changelog-meta` exposes `hasPrevious`/`previousText` so the UI knows when to offer it.
- **Push to repo** (`POST /changelog-push`) is **developer-only** (rejects `qa`/`business`). It re-fetches the branch's live `CHANGELOG.md`, replaces that version's developer entry, pushes to a new branch (`changelog/{version}-developer-{sha8}`), and opens a PR back into the source branch — via the Azure DevOps REST API (Azure) or the GitHub Git Data + Pulls API (GitHub). Returns `{ "pullRequestUrl": "..." }`.

## Frontend roles & routing

The dashboard is split into three role-scoped areas, each behind `RouteGuard`:

| Prefix | Role | Home page | Capabilities |
|---|---|---|---|
| `/dev` | Developer | `ProjectsPage` | Browse projects/repos, generate all audiences, edit, restore, **push to repo (PR)** |
| `/qa` | QA | `AudienceBrowsePage` (qa) | Browse repos with a changelog, generate/edit/restore QA + business text — no push |
| `/business` | Business | `AudienceBrowsePage` (business) | Read-only preview of business changelogs |

`/pr/:project/:repo/:prId` (`PrRedirectPage`, no role gate) resolves a PR number straight to its
changelog location via `GET /pull-requests/{prId}/changelog-location` and redirects there — a deep
link that works from outside the dashboard (e.g. a bot comment on the PR itself).

Role is chosen once on the landing page (`/`, `RoleSelectPage`) and stored in `localStorage` (`changelog-role`) — switchable anytime from the header. **This is explicitly a temporary stand-in, not real auth** — see [`AUTH.md`](./AUTH.md) for what it does today and what must replace it (Keycloak/OIDC) before this goes to real users.

---

## AI prompts

Three built-in prompts, overridable via `application.properties`:

| Config key | Audience | Format |
|---|---|---|
| `ai.prompt.developer` | Developers | Flat conventional-commit bullets (`- **type(scope)**: sentence (PR !N) [#N]`). Type inferred from file paths first; scope from first path segment. |
| `ai.prompt.qa` | QA engineers | Sections: New Areas to Test, Regression Checks, Performance Checks, Risk/Edge Cases. Actionable verification steps. |
| `ai.prompt.business` | Non-technical stakeholders | Sections: What's New, Fixes & Improvements. Plain language, no jargon, no PR/work-item refs. |

Current defaults in `NimAiProvider.java` are prescriptive (modeled after a proven SDK changelog prompt). Uncomment the config keys to supply repo-specific overrides.

---

## Getting started

### Local development

**Backend** (API on `http://localhost:8080`):

```bash
cd service
# .env is gitignored — copy the example and fill in real values (see "Configuration" below).
# Note it's a SEPARATE copy from the repo-root .env; Quarkus reads .env from wherever
# quarkus:dev's own working directory is, not the repo root.
cp ../.env.example .env && $EDITOR .env

./mvnw quarkus:dev
```

**Frontend** (hot reload on `http://localhost:5173`, proxies `/api` to `localhost:8080`):

```bash
cd web-view
npm install
npm run dev
```

Open `http://localhost:5173` in your browser.

### Production / Docker (single image with frontend bundled)

The repo-root `Dockerfile` is a multi-stage build: it runs `./mvnw package -Pprod` itself (the `-Pprod`
profile enables Quinoa, which bundles the built frontend into the JAR), then copies just the runtime
JAR into a slim `eclipse-temurin:25-jre` image. You don't need to build the JAR yourself first.

```bash
# copy .env.example -> .env and fill in real values first (see Configuration below)
docker compose up -d --build
```

This builds the image and starts the `app` service on `http://localhost:8080`, reading its config from
`.env` (see `docker-compose.yml`). An optional `caddy` service (commented out in `docker-compose.yml`)
can front it with automatic HTTPS once you have a real domain — point `Caddyfile` at that domain and
uncomment the service.

### Test the pipeline endpoint

**Azure** — bundled release ingestion (the default shape — note `stage` is required here, unlike everything
else below it):

```bash
curl -s -X POST http://localhost:8080/api/pipeline/generate \
  -H "Authorization: Bearer dev-local-only-key" \
  -H "Content-Type: application/json" \
  -d '{
    "project": "MyProject",
    "repo": "my-repo",
    "version": "1.2.3",
    "stage": "prerelease",
    "rawCommitLog": "=== feat: add login\n\n- file was added\nservice/src/main/java/Login.java\n== fix: fix timeout\n\nservice/src/main/java/Session.java",
    "workItemIds": [42]
  }'
```

**GitHub** — workflow-run capture (the GitHub-side flow; `runId` = `github.run_id`):

```bash
curl -s -X POST http://localhost:8080/api/github/pipeline/generate \
  -H "Authorization: Bearer dev-local-only-key" \
  -H "Content-Type: application/json" \
  -d '{"runId": 1234567890, "project": "my-org", "repo": "my-repo", "branch": "main"}'
```

No AI call happens in either — Azure responds with `PipelineIngestResponse` (`{ project, repo, version, stage, prCount }`), GitHub with `GitHubPipelineIngestResponse` (`{ runId, project, repo, version, branch, prCount, changelog }`). See [Pipeline flow](#pipeline-flow) above for why, and
[`service/API_ENDPOINTS.md`](./service/API_ENDPOINTS.md) for the per-PR raw-init shape (`raw: true`).

---

## Configuration

Both local dev and Docker read the **same `.env` file** — Quarkus automatically loads `.env` in dev
mode, resolving it against `application.properties`' `${ENV_VAR:default}` indirections (that file
itself holds no real secrets, just those references — see [`AUTH.md`](./AUTH.md) and inline config
docs for what maps where). Copy `.env.example` → `.env` at the repo root and fill in real values:

```bash
AZURE_DEVOPS_PAT=your-pat
AZURE_DEVOPS_ORG=datasabai
GITHUB_TOKEN=your-github-pat        # GitHub provider — needs repo + actions:read scopes
GITHUB_OWNER=your-github-org-or-user  # GitHub provider — acts as the single "project"
AI_API_KEY=your-ai-key
AI_BASE_URL=https://integrate.api.nvidia.com/v1/chat/completions
AI_MODEL=mistralai/mistral-large-3-675b-instruct-2512
DB_USERNAME=neondb_owner
DB_PASSWORD=your-db-password
DB_URL=jdbc:postgresql://your-project-pooler.region.aws.neon.tech/neondb?sslmode=require
PIPELINE_API_KEYS=your-pipeline-key
```

> **Gotcha:** Quarkus's `.env` auto-load reads from its own **working directory at process start**,
> not the repo root — if you run `mvn quarkus:dev` from inside `service/`, it needs `service/.env`
> (a separate copy from the repo-root one used by Docker/`web-view`), not the parent directory's. Keep
> both in sync by hand. Also: `.env` is only read once, at process start — adding or changing a
> variable needs a full restart of the dev server, not just a hot reload (a hot reload only
> recompiles code).

Never commit `application.properties` or any `.env` file — both are in `.gitignore`. The values shown
above are placeholders only.

---

## How generation works

This is the dashboard's on-demand `POST /generate`/`/generate-stream` flow — the only place an AI
call ever happens (see [Pipeline flow](#pipeline-flow) above for why the pipeline itself never does).

1. **Source data** is collected per-repo: commits, PRs, and work items linked to them.
2. **Input hash** (SHA-256 of all change item titles, descriptions, types, and file paths) is computed.
3. **Cache check**: if `(project, repo, version, audience)` exists with the same hash, the cached text is returned without calling the LLM.
4. **AI call**: the system prompt is `CHANGELOG_COMMON + audience-specific prompt`. Model fallback chain tries the primary model, then fallbacks, then plain bullets if all fail.
5. **Storage**: generated text is upserted into Postgres for later retrieval by the dashboard.

---

## Example output

### Developer

```
- **feat(service)**: Added pipeline authentication filter with constant-time token comparison (PR !42)
- **fix(webview)**: Fixed changelog preview not updating after stream completion (PR !38)
- **build**: Upgraded Quarkus from 3.36 to 3.37.1 [#1234]
- **ci**: Added Flyway migration for generated_changelog table (PR !35)
```

### QA

```
## New Areas to Test
- Service: pipeline auth filter — verify that requests with missing and invalid tokens return 401

## Regression Checks
- Webview: changelog preview — verify it correctly displays the latest generated text after stream completion

## Risk / Edge Cases
- Build: Quarkus upgrade — verify existing endpoints still function with the new runtime
```

### Business

```
## What's New
- Added security for pipeline requests — only authorized pipelines can generate changelogs

## Fixes & Improvements
- Fixed the changelog preview to always show the latest content
- Upgraded the underlying framework for better performance and stability
```

---

## CI/CD

Two GitHub Actions workflows, split by responsibility:

**1. `ci-cd.yml` — dev CI (auto).** Runs on PR into `dev` and push/merge to `dev`. Nothing here
ever touches production.

| Trigger | Stage(s) | What runs |
|---|---|---|
| Any PR into `dev`, any push to `dev` | `frontend-checks`, `backend-tests` | `npm run lint/build`, `./mvnw test` — pure validation |
| Push/merge to `dev` only | `generate-changelog` (after CI) | `.github/scripts/generate-own-changelog.sh` — this project calling its own `POST /api/github/pipeline/generate`, then committing the raw (non-AI) changelog entry to `CHANGELOG.md` on `dev` under `## <date> — dev (run N)`. **No version bump** — dev never changes semver. If the changelog service is unreachable it logs a warning and exits `0`; the image build always proceeds. |
| Push/merge to `dev` only | `publish-image` (after changelog) | Builds and pushes an immutable image `ghcr.io/<owner>/changelog-composer:build.{run}-{sha}` — the run number + short sha make it unique per merge. **No semver in the tag.** |

**2. `deploy-to-vm.yml` — production promotion (manual).** Triggered by hand via
`workflow_dispatch` on `main`, with `image_tag` (`build.152-a8f92c1`) and an optional `digest`.
This is the only workflow that can touch production, and it **never rebuilds the app**:

```
select build → verify image+digest on GHCR → ssh VM → docker compose pull (docker-compose.prod.yml)
            → docker compose up -d --force-recreate → retry /q/health → SUCCESS → bump semver → commit vX.Y.Z to main
```

Production success comes first, release version second: a failed deploy (bad image, dead health
check) stops the workflow with `main` untouched. Rollback is just re-running this workflow with an
older `image_tag`.

`list-available-builds.yml` (manual `workflow_dispatch`) lists the GHCR candidate tags + digests
you can promote.

The VM owns all production config in `VM_COMPOSE_DIR`: a `docker-compose.prod.yml` (`image: ${IMAGE}`,
no `build:`) with two services — the app and a **Caddy** TLS reverse proxy (so the service is
reachable at `https://<domain>/api`), plus a `Caddyfile` and a `.env` with real secrets and the
`DOMAIN`. GHCR credentials live in the VM's docker config (or are supplied to the workflow as
`VM_GHCR_USER`/`VM_GHCR_TOKEN`) — never in the repo.

> **Chicken-and-egg (first deploy only):** the dev changelog dogfoods a running service at
> `CHANGELOG_SERVICE_URL`, so the very first deployment is done by hand — seed `CHANGELOG.md` with
> the initial version and deploy the first image manually. After that the loop sustains itself.

GitHub Actions secrets (`CHANGELOG_SERVICE_URL`, `CHANGELOG_API_KEY`, `GIT_PUSH_PAT`, `VM_HOST`,
`VM_USER`, `VM_COMPOSE_DIR`, `SSH_PRIVATE_KEY`, optional `VM_GHCR_USER`/`VM_GHCR_TOKEN`) are set in
repo settings, never committed.