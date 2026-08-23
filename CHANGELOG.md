## v1.0.12 — 2026-08-23 (promoted build.17)

## v1.0.11 — 2026-07-24

- fix: recreate generated_changelog and raw_release tables for old entity compatibility
- fix: make V8 backfill conditional on generated_changelog existence
- fix: add flyway repair-at-start env var for CI checksum mismatches
- update
- update
- fix: scope revision history to shared snapshots, fix wrong-PR data and unwrapped fences
- fix: reorder migrations so backfill runs before old tables are dropped, fix restorePrevious bug
- refactor: overhaul prompt architecture, add changelog revisions, fix data fallback for QA/Business generation
- chore: bump version to 1.0.10 + update CHANGELOG.md ***NO_CI***

## v1.0.10 — 2026-07-23

- fix: remove unused setSearchParams causing tsc build failure
- fix: branch selector hardcoded to dev, DeployToVm stage, SPA refresh 404 fallback, build script ref namespace
- chore: bump version to 1.0.9 + update CHANGELOG.md ***NO_CI***

## v1.0.9 — 2026-07-23

- fix: production SPA refresh 404s, resolveReleaseData missing raw-ingest fallback, silent Push errors
- chore: bump version to 1.0.8 + update CHANGELOG.md ***NO_CI***

## v1.0.8 — 2026-07-22

- fix: two pipeline-blocking test failures + enable Swagger UI in prod
- fix: stop auto-saving generated changelogs, add push confirmation, fix prod SPA/swagger routing
- chore: bump version to 1.0.7 + update CHANGELOG.md ***NO_CI***
- Add v20260722.3 developer changelog (PR #1286)
- Add v20260722.3 developer changelog (via dashboard)

## v1.0.4 — 2026-07-21

- feat: add LatestRunChangelogPocResource for Azure DevOps pipeline run changelog generation
- chore: bump version to 1.0.3 + update CHANGELOG.md ***NO_CI***
- Merged PR 1264: fix: scope WebApplicationExceptionMapper to /api/* and add Quinoa ignored-pat...
- fix: scope WebApplicationExceptionMapper to /api/* and add Quinoa ignored-path-prefixes
- chore: bump version to 1.0.2 + update CHANGELOG.md ***NO_CI***
- Merged PR 1262: chore: shorten verbose comments across codebase
- Merge branch 'origin/dev' into ds-nay/1933
- chore: shorten verbose comments across codebase
- chore: bump version to 1.0.1 ***NO_CI***
- Merged PR 1261: fix: strip existing credential from origin URL before injecting GIT_PUSH_PAT
- fix: strip existing credential from origin URL before injecting GIT_PUSH_PAT
- Merged PR 1260: Log GIT_PUSH_PAT's length (never its value) before pushing, to diagnose the r...
- Log GIT_PUSH_PAT's length (never its value) before pushing, to diagnose the repeat auth failure
- Merged PR 1259: Push version bump and image tag using a PAT instead of the Build Service iden...
- Push version bump and image tag using a PAT instead of the Build Service identity
- Shorten chat widget's localStorage auto-expiry from 1 day to 6 hours
- Merged PR 1258: update
- Add streaming AI chat widget for exploring a repo's changelog history
- Publish images from dev instead of main, and share one version across the image tag and changelog
- Show the real HTTP error instead of swallowing it in generate-own-changelog.sh
- fix: scope WebApplicationExceptionMapper to /api/* and add Quinoa ignored-pat... (PR #1264)
- chore: shorten verbose comments across codebase (PR #1262)
- fix: strip existing credential from origin URL before injecting GIT_PUSH_PAT (PR #1261)
- Log GIT_PUSH_PAT's length (never its value) before pushing, to diagnose the r... (PR #1260)
- Push version bump and image tag using a PAT instead of the Build Service iden... (PR #1259)
- update (PR #1258)
- Work Item #123 (fetch failed: Received: 'Not Found, status code 404' when invoking REST Client method: 'com.hubsabai.changelog.connector.azuredevops.AzureDevOpsRestClient#getWorkItem') (#123)
- possible chatbot related to specific changelog (#1933)

## v1.0.3 — 2026-07-21

- Merged PR 1264: fix: scope WebApplicationExceptionMapper to /api/* and add Quinoa ignored-pat...
- fix: scope WebApplicationExceptionMapper to /api/* and add Quinoa ignored-path-prefixes
- chore: bump version to 1.0.2 + update CHANGELOG.md ***NO_CI***
- Merged PR 1262: chore: shorten verbose comments across codebase
- Merge branch 'origin/dev' into ds-nay/1933
- chore: shorten verbose comments across codebase
- chore: bump version to 1.0.1 ***NO_CI***
- Merged PR 1261: fix: strip existing credential from origin URL before injecting GIT_PUSH_PAT
- fix: strip existing credential from origin URL before injecting GIT_PUSH_PAT
- Merged PR 1260: Log GIT_PUSH_PAT's length (never its value) before pushing, to diagnose the r...
- Log GIT_PUSH_PAT's length (never its value) before pushing, to diagnose the repeat auth failure
- Merged PR 1259: Push version bump and image tag using a PAT instead of the Build Service iden...
- Push version bump and image tag using a PAT instead of the Build Service identity
- Shorten chat widget's localStorage auto-expiry from 1 day to 6 hours
- Merged PR 1258: update
- Add streaming AI chat widget for exploring a repo's changelog history
- Publish images from dev instead of main, and share one version across the image tag and changelog
- Show the real HTTP error instead of swallowing it in generate-own-changelog.sh
- Fix silent pipeline failure: grep exiting 1 on no work-item matches killed the script under set -e
- Return raw changelog text from the pipeline endpoint; fix own dogfood script and add version bump
- fix: scope WebApplicationExceptionMapper to /api/* and add Quinoa ignored-pat... (PR #1264)
- chore: shorten verbose comments across codebase (PR #1262)
- fix: strip existing credential from origin URL before injecting GIT_PUSH_PAT (PR #1261)
- Log GIT_PUSH_PAT's length (never its value) before pushing, to diagnose the r... (PR #1260)
- Push version bump and image tag using a PAT instead of the Build Service iden... (PR #1259)
- update (PR #1258)
- Work Item #123 (fetch failed: Received: 'Not Found, status code 404' when invoking REST Client method: 'com.hubsabai.changelog.connector.azuredevops.AzureDevOpsRestClient#getWorkItem') (#123)
- possible chatbot related to specific changelog (#1933)

## v1.0.2 — 2026-07-21

- Merged PR 1262: chore: shorten verbose comments across codebase
- Merge branch 'origin/dev' into ds-nay/1933
- chore: shorten verbose comments across codebase
- chore: bump version to 1.0.1 ***NO_CI***
- Merged PR 1261: fix: strip existing credential from origin URL before injecting GIT_PUSH_PAT
- fix: strip existing credential from origin URL before injecting GIT_PUSH_PAT
- Merged PR 1260: Log GIT_PUSH_PAT's length (never its value) before pushing, to diagnose the r...
- Log GIT_PUSH_PAT's length (never its value) before pushing, to diagnose the repeat auth failure
- Merged PR 1259: Push version bump and image tag using a PAT instead of the Build Service iden...
- Push version bump and image tag using a PAT instead of the Build Service identity
- Shorten chat widget's localStorage auto-expiry from 1 day to 6 hours
- Merged PR 1258: update
- Add streaming AI chat widget for exploring a repo's changelog history
- Publish images from dev instead of main, and share one version across the image tag and changelog
- Show the real HTTP error instead of swallowing it in generate-own-changelog.sh
- Fix silent pipeline failure: grep exiting 1 on no work-item matches killed the script under set -e
- Return raw changelog text from the pipeline endpoint; fix own dogfood script and add version bump
- Add manual fallback script for publishing the image when builder-pool is down
- Finish stage-detection revert, fix QA/business repo filtering, polish history UI
- Add release/prerelease stage detection, raw release ingestion, and pipeline/doc fixes
- chore: shorten verbose comments across codebase (PR #1262)
- fix: strip existing credential from origin URL before injecting GIT_PUSH_PAT (PR #1261)
- Log GIT_PUSH_PAT's length (never its value) before pushing, to diagnose the r... (PR #1260)
- Push version bump and image tag using a PAT instead of the Build Service iden... (PR #1259)
- update (PR #1258)
- Work Item #123 (fetch failed: Received: 'Not Found, status code 404' when invoking REST Client method: 'com.hubsabai.changelog.connector.azuredevops.AzureDevOpsRestClient#getWorkItem') (#123)
- possible chatbot related to specific changelog (#1933)
