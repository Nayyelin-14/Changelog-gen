export interface ProjectSummary {
  id: string;
  name: string;
  description: string | null;
}

export interface RepositorySummary {
  id: string;
  name: string;
  project: string;
  defaultBranch: string | null;
}

/** One repo's status at a glance for the Dev dashboard's repo table. needsReviewCount is capped
 * (see the backend's UNGENERATED_PR_LIMIT) — it may undercount if a repo has many unreviewed PRs. */
export interface RepoOverview {
  name: string;
  defaultBranch: string | null;
  latestVersion: string | null;
  latestVersionAt: string | null;
  needsReviewCount: number;
}

/** One pipeline run for a repo's "Pipeline runs" dashboard list — pick one to generate a
 * changelog from its buildId, without needing to know which pipeline definition built it. */
export interface PipelineRunSummary {
  buildId: number;
  buildNumber: string | null;
  status: string | null;
  result: string | null;
  finishTime: string | null;
  sourceBranch: string | null;
  sourceVersion: string | null;
  pipelineName: string | null;
  prNumber: number | null;
  /** The triggering commit's own message (first line) — e.g. "Merged PR 1277: Revert ..." — this
   * is what actually tells two runs apart; buildNumber alone is often identical across many runs
   * (it tracks the app's version, not the run). */
  commitTitle: string | null;
}

export type ChangeItemType = 'WORK_ITEM' | 'PULL_REQUEST' | 'COMMIT';

export interface ChangeItem {
  type: ChangeItemType;
  id: string | null;
  title: string | null;
  category: string | null;
  description: string | null;
  author: string | null;
  project: string | null;
  repo: string | null;
  date: string | null;
  links: string[];
  filePaths: string[];
}

export interface ReleaseMeta {
  org: string | null;
  project: string | null;
  repo: string | null;
  branch: string | null;
  milestone: string | null;
  releaseDate: string | null;
}

export interface ReleaseData {
  release: ReleaseMeta;
  items: ChangeItem[];
}

export interface GenerateResult {
  developer: string;
  qa: string;
  business: string;
  usage: AiUsage[];
  durationMs: number;
  saved: boolean;
}

export interface AiUsage {
  promptTokens: number;
  completionTokens: number;
  totalTokens: number;
}

export interface AiModelOption {
  id: string;
  label: string;
  recommended?: boolean;
}

export interface GenerationRecord {
  id: string;
  project: string;
  repo: string;
  branch: string | null;
  version: string | null;
  authors: string[];
  timestamp: string;
  developer: string;
  // false for a merged PR shown before anyone has generated a changelog for it — absent (treated
  // as true) for every already-generated entry.
  generated?: boolean;
  // "raw" (pipeline draft, not yet reviewed), "ai", "edit", "import", or "changelog" (straight
  // CHANGELOG.md, no app-side override) — undefined when generated is false.
  source?: string;
}

export interface HistoryResponse {
  entries: GenerationRecord[];
  total: number;
}

export type PreviewAudience = 'qa' | 'business';

export interface ChangelogPreview {
  project: string;
  repo: string;
  version: string;
  audience: PreviewAudience;
  text: string | null;
}

export interface ChangelogRevisionDto {
  sequence: number;
  source: string | null;
  model: string | null;
  tokens: number | null;
  durationMs: number | null;
  editedBy: string | null;
  text: string;
  createdAt: string | null;
}

/** What's known about the current text for a version+audience — an AI generation (with its
 * model) or a human edit (with who and when). All fields null if nothing's been saved yet.
 * `hasPrevious` says whether a `changelog-restore` call would have something to bring back;
 * `previousText` is that text, so a restore confirmation can preview it before committing.
 * `hasUnpushedChanges`/`pushedAt`/`pushedPullRequestUrl` are developer-only (always
 * `false`/`null` for qa/business, which never push) and describe the last successful push.
 * `revisions` is the full edit history chain from the new schema. */
export interface ChangelogMeta {
  source: 'ai' | 'edit' | 'import' | 'raw' | null;
  model: string | null;
  editedBy: string | null;
  at: string | null;
  hasPrevious: boolean;
  previousText: string | null;
  previousSource: 'ai' | 'edit' | 'import' | 'raw' | null;
  previousModel: string | null;
  previousEditedBy: string | null;
  previousAt: string | null;
  hasUnpushedChanges: boolean;
  pushedAt: string | null;
  pushedPullRequestUrl: string | null;
  pushedText: string | null;
  revisions: ChangelogRevisionDto[];
  /** From the current text's own revision, when it's an AI generation — null for edits/imports
   * or if usage wasn't recorded. */
  tokens: number | null;
  durationMs: number | null;
}

/** Where (if anywhere) a PR's changes actually landed — `prerelease` is a real, expected state
 * (reported by the pipeline, not yet promoted to a release), not an error. */
export interface ChangelogLocation {
  status: 'released' | 'prerelease' | 'not_found';
  version: string | null;
  stage: 'prerelease' | 'release' | null;
}

export interface PullRequestWorkItemSummary {
  id: number;
  title: string | null;
  type: string | null;
  /** The work item's own System.Description field — Azure DevOps stores it as HTML, so it needs
   * stripping before it's shown as plain text or sent to the AI. */
  description: string | null;
  /** Process metadata for display only — not meaningful changelog content, don't send to the AI. */
  state: string | null;
  assignedTo: string | null;
  url: string | null;
}

/** One PR's own title/description/commits/linked work items, live from Azure DevOps — for
 * pre-filling the generate flow when a PR has no version/changelog yet to fetch a range from. */
export interface PullRequestDetails {
  prId: number;
  title: string | null;
  description: string | null;
  author: string | null;
  commitMessages: string[];
  workItems: PullRequestWorkItemSummary[];
}

/** One turn of a changelog Q&A conversation — the browser is the only place a conversation is
 * kept (see ChangelogChatWidget's localStorage persistence), so every request resends the
 * turns it still has instead of the server holding any chat state. `at` is a display-only
 * timestamp (when this turn was added) — it's local UI state, never sent to the backend, which
 * only ever needs `role`/`content` to build AI context. */
export interface ChatTurn {
  role: 'user' | 'assistant';
  content: string;
  at?: string;
}
