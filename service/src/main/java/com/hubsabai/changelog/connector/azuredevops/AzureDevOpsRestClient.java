package com.hubsabai.changelog.connector.azuredevops;

import com.hubsabai.changelog.connector.azuredevops.dto.AzureDevOpsListResponse;
import com.hubsabai.changelog.connector.azuredevops.dto.BuildChange;
import com.hubsabai.changelog.connector.azuredevops.dto.BuildResponse;
import com.hubsabai.changelog.connector.azuredevops.dto.CommitChangesResponse;
import com.hubsabai.changelog.connector.azuredevops.dto.CommitResponse;
import com.hubsabai.changelog.connector.azuredevops.dto.CreatePullRequestRequest;
import com.hubsabai.changelog.connector.azuredevops.dto.GitPushRequest;
import com.hubsabai.changelog.connector.azuredevops.dto.PullRequestResponse;
import com.hubsabai.changelog.connector.azuredevops.dto.RefUpdate;
import com.hubsabai.changelog.connector.azuredevops.dto.RepositoryResponse;
import com.hubsabai.changelog.connector.azuredevops.dto.WiqlQuery;
import com.hubsabai.changelog.connector.azuredevops.dto.WiqlResult;
import com.hubsabai.changelog.connector.azuredevops.dto.WorkItemResponse;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.time.temporal.ChronoUnit;
import java.util.List;
import org.eclipse.microprofile.faulttolerance.Timeout;
import org.eclipse.microprofile.rest.client.annotation.RegisterProvider;
import org.eclipse.microprofile.rest.client.inject.RegisterRestClient;

// baseUri is a real fallback (https://dev.azure.com is the cloud API root, same for every org).
// Without it, the client had no URI in production (the config key was only in a gitignored
// application.properties) — every call through this client failed while listProjects (a separate
// hardcoded HTTP call) kept working.
@RegisterRestClient(configKey = "azure-devops", baseUri = "https://dev.azure.com")
@RegisterProvider(AzureDevOpsAuthFilter.class)
@Produces(MediaType.APPLICATION_JSON)
// Every call here was previously unbounded — a stalled Azure DevOps response (bad/expired PAT,
// network stall) hung forever with no timeout anywhere in the chain. This caps each individual
// call so it fails loudly instead.
@Timeout(value = 15, unit = ChronoUnit.SECONDS)
public interface AzureDevOpsRestClient {

    String API_VERSION = "7.1";

    /** Returns the raw {@link Response} because pagination reads the {@code x-ms-continuationtoken} header. */
    @GET
    @Path("/{org}/_apis/projects")
    Response listProjectsPage(
            @PathParam("org") String org,
            @QueryParam("continuationToken") String continuationToken,
            @QueryParam("$top") @DefaultValue("100") int top,
            @QueryParam("api-version") @DefaultValue(API_VERSION) String apiVersion
    );

    @GET
    @Path("/{org}/{project}/_apis/git/repositories")
    AzureDevOpsListResponse<RepositoryResponse> listRepositories(
            @PathParam("org") String org,
            @PathParam("project") String project,
            @QueryParam("api-version") @DefaultValue(API_VERSION) String apiVersion
    );

    /** Commits filtered by dates (original approach). */
    @GET
    @Path("/{org}/{project}/_apis/git/repositories/{repositoryId}/commits")
    AzureDevOpsListResponse<CommitResponse> listCommits(
            @PathParam("org") String org,
            @PathParam("project") String project,
            @PathParam("repositoryId") String repositoryId,
            @QueryParam("searchCriteria.$top") int top,
            @QueryParam("searchCriteria.$skip") int skip,
            @QueryParam("searchCriteria.fromDate") String fromDate,
            @QueryParam("searchCriteria.toDate") String toDate,
            @QueryParam("searchCriteria.itemVersion.version") String branch,
            @QueryParam("searchCriteria.itemPath") String itemPath,
            @QueryParam("api-version") @DefaultValue(API_VERSION) String apiVersion
    );

    /** Commits filtered by commit-ID range (version-based approach). */
    @GET
    @Path("/{org}/{project}/_apis/git/repositories/{repositoryId}/commits")
    AzureDevOpsListResponse<CommitResponse> listCommitsByRange(
            @PathParam("org") String org,
            @PathParam("project") String project,
            @PathParam("repositoryId") String repositoryId,
            @QueryParam("searchCriteria.$top") int top,
            @QueryParam("searchCriteria.$skip") int skip,
            @QueryParam("searchCriteria.fromCommitId") String fromCommitId,
            @QueryParam("searchCriteria.toCommitId") String toCommitId,
            @QueryParam("searchCriteria.itemVersion.version") String branch,
            @QueryParam("searchCriteria.itemPath") String itemPath,
            @QueryParam("api-version") @DefaultValue(API_VERSION) String apiVersion
    );

    /**
     * Raw {@link Response} because a missing path returns 404 — that's a normal "doesn't exist"
     * result, not an error. Pass {@code includeContent=true} to get the file content as a
     * base64-encoded string inside the JSON response (see GitItem.content).
     */
    @GET
    @Path("/{org}/{project}/_apis/git/repositories/{repositoryId}/items")
    Response getItem(
            @PathParam("org") String org,
            @PathParam("project") String project,
            @PathParam("repositoryId") String repositoryId,
            @QueryParam("path") String path,
            @QueryParam("versionDescriptor.version") String branch,
            @QueryParam("versionDescriptor.versionType") String versionType,
            @QueryParam("api-version") @DefaultValue(API_VERSION) String apiVersion,
            @QueryParam("includeContent") @DefaultValue("false") boolean includeContent
    );

    @GET
    @Path("/{org}/{project}/_apis/git/repositories/{repositoryId}/pullrequests")
    AzureDevOpsListResponse<PullRequestResponse> listPullRequests(
            @PathParam("org") String org,
            @PathParam("project") String project,
            @PathParam("repositoryId") String repositoryId,
            @QueryParam("searchCriteria.status") @DefaultValue("all") String status,
            @QueryParam("searchCriteria.targetRefName") String targetRefName,
            @QueryParam("$top") int top,
            @QueryParam("$skip") int skip,
            @QueryParam("api-version") @DefaultValue(API_VERSION) String apiVersion
    );

    /**
     * Org-wide "get PR by ID" — no repositoryId needed (pipeline path has a repo name, not a
     * GUID). Used for known PR IDs from pipeline calls or "Merged PR N" commit messages.
     */
    @GET
    @Path("/{org}/_apis/git/pullrequests/{pullRequestId}")
    PullRequestResponse getPullRequestById(
            @PathParam("org") String org,
            @PathParam("pullRequestId") int pullRequestId,
            @QueryParam("api-version") @DefaultValue(API_VERSION) String apiVersion
    );

    /** Metadata for one pipeline run — the pipeline-run changelog flow only needs {@code
     * sourceBranch} and {@code triggerInfo} (for a PR-triggered run's own PR number). */
    @GET
    @Path("/{org}/{project}/_apis/build/builds/{buildId}")
    BuildResponse getBuild(
            @PathParam("org") String org,
            @PathParam("project") String project,
            @PathParam("buildId") int buildId,
            @QueryParam("api-version") @DefaultValue(API_VERSION) String apiVersion
    );

    /** Commits since the previous build of this same pipeline definition — already exactly a
     * per-release delta, no tag lookup or semver reconstruction needed (contrast the dashboard's
     * {@code fetchRepoChanges}). */
    @GET
    @Path("/{org}/{project}/_apis/build/builds/{buildId}/changes")
    AzureDevOpsListResponse<BuildChange> getBuildChanges(
            @PathParam("org") String org,
            @PathParam("project") String project,
            @PathParam("buildId") int buildId,
            @QueryParam("$top") @DefaultValue("500") int top,
            @QueryParam("api-version") @DefaultValue(API_VERSION) String apiVersion
    );

    /** Work items linked to this build run — same delta semantics as {@link #getBuildChanges}.
     * Bare id/url refs only; batch-fetch full details via {@link #getWorkItemsBatch}. */
    @GET
    @Path("/{org}/{project}/_apis/build/builds/{buildId}/workitems")
    AzureDevOpsListResponse<WiqlResult.WorkItemReference> getBuildWorkItems(
            @PathParam("org") String org,
            @PathParam("project") String project,
            @PathParam("buildId") int buildId,
            @QueryParam("$top") @DefaultValue("500") int top,
            @QueryParam("api-version") @DefaultValue(API_VERSION) String apiVersion
    );

    /** Recent runs across every pipeline definition that builds this one repository — filtering
     * by {@code repositoryId} means the dashboard never needs to know which pipeline definition(s)
     * a repo uses. */
    @GET
    @Path("/{org}/{project}/_apis/build/builds")
    AzureDevOpsListResponse<BuildResponse> listBuildsForRepository(
            @PathParam("org") String org,
            @PathParam("project") String project,
            @QueryParam("repositoryId") String repositoryId,
            @QueryParam("repositoryType") @DefaultValue("TfsGit") String repositoryType,
            @QueryParam("$top") int top,
            @QueryParam("queryOrder") @DefaultValue("finishTimeDescending") String queryOrder,
            @QueryParam("api-version") @DefaultValue(API_VERSION) String apiVersion
    );

    /** Builds for one pipeline definition, newest-first — used to find the build immediately
     * preceding a given one so {@link AzureDevOpsOrgConnector#fetchRunChanges} can tell whether
     * Azure's "changes since previous build" window was inflated by a canceled build in between. */
    @GET
    @Path("/{org}/{project}/_apis/build/builds")
    AzureDevOpsListResponse<BuildResponse> listBuildsForDefinition(
            @PathParam("org") String org,
            @PathParam("project") String project,
            @QueryParam("definitions") int definitionId,
            @QueryParam("$top") int top,
            @QueryParam("queryOrder") @DefaultValue("queueTimeDescending") String queryOrder,
            @QueryParam("api-version") @DefaultValue(API_VERSION) String apiVersion
    );

    @POST
    @Path("/{org}/{project}/_apis/wit/wiql")
    @Consumes(MediaType.APPLICATION_JSON)
    WiqlResult queryWorkItems(
            @PathParam("org") String org,
            @PathParam("project") String project,
            WiqlQuery query,
            @QueryParam("api-version") @DefaultValue(API_VERSION) String apiVersion
    );

    @GET
    @Path("/{org}/{project}/_apis/wit/workitems")
    AzureDevOpsListResponse<WorkItemResponse> getWorkItemsBatch(
            @PathParam("org") String org,
            @PathParam("project") String project,
            @QueryParam("ids") String commaSeparatedIds,
            @QueryParam("api-version") @DefaultValue(API_VERSION) String apiVersion
    );

    @GET
    @Path("/{org}/{project}/_apis/wit/workitems/{id}")
    WorkItemResponse getWorkItem(
            @PathParam("org") String org,
            @PathParam("project") String project,
            @PathParam("id") int id,
            @QueryParam("api-version") @DefaultValue(API_VERSION) String apiVersion
    );

    /** Lists git refs (tags, branches). {@code peelTags=true} resolves annotated tags to their
     * commit SHA; otherwise {@code objectId} is the tag object's own SHA. */
    @GET
    @Path("/{org}/{project}/_apis/git/repositories/{repositoryId}/refs")
    Response listRefs(
            @PathParam("org") String org,
            @PathParam("project") String project,
            @PathParam("repositoryId") String repositoryId,
            @QueryParam("filter") String filter,
            @QueryParam("peelTags") @DefaultValue("true") boolean peelTags,
            @QueryParam("api-version") @DefaultValue(API_VERSION) String apiVersion
    );

    /** Returns a single commit by its ID. */
    @GET
    @Path("/{org}/{project}/_apis/git/repositories/{repositoryId}/commits/{commitId}")
    CommitResponse getCommit(
            @PathParam("org") String org,
            @PathParam("project") String project,
            @PathParam("repositoryId") String repositoryId,
            @PathParam("commitId") String commitId,
            @QueryParam("api-version") @DefaultValue(API_VERSION) String apiVersion
    );

    /** File-level changes (add/edit/delete) for one commit — each change carries a file path. */
    @GET
    @Path("/{org}/{project}/_apis/git/repositories/{repositoryId}/commits/{commitId}/changes")
    CommitChangesResponse getCommitChanges(
            @PathParam("org") String org,
            @PathParam("project") String project,
            @PathParam("repositoryId") String repositoryId,
            @PathParam("commitId") String commitId,
            @QueryParam("api-version") @DefaultValue(API_VERSION) String apiVersion
    );

    /** Commits belonging to one PR — the reliable way to match commits to PRs (text matching is
     * unreliable since PR titles are often edited from source commit messages). */
    @GET
    @Path("/{org}/{project}/_apis/git/repositories/{repositoryId}/pullrequests/{pullRequestId}/commits")
    AzureDevOpsListResponse<CommitResponse> listPullRequestCommits(
            @PathParam("org") String org,
            @PathParam("project") String project,
            @PathParam("repositoryId") String repositoryId,
            @PathParam("pullRequestId") int pullRequestId,
            @QueryParam("api-version") @DefaultValue(API_VERSION) String apiVersion
    );

    /** Work items linked to one PR — bare id/url refs only; callers fetch full details via
     * {@link #getWorkItemsBatch}. */
    @GET
    @Path("/{org}/{project}/_apis/git/repositories/{repositoryId}/pullrequests/{pullRequestId}/workitems")
    AzureDevOpsListResponse<WiqlResult.WorkItemReference> getPullRequestWorkItems(
            @PathParam("org") String org,
            @PathParam("project") String project,
            @PathParam("repositoryId") String repositoryId,
            @PathParam("pullRequestId") int pullRequestId,
            @QueryParam("api-version") @DefaultValue(API_VERSION) String apiVersion
    );

    /** Creates refs (new branch before pushing a changelog edit). Raw {@link Response} because
     * Azure DevOps returns HTTP 200 even on rejection — check {@code success}, not status code. */
    @POST
    @Path("/{org}/{project}/_apis/git/repositories/{repositoryId}/refs")
    @Consumes(MediaType.APPLICATION_JSON)
    Response createRefs(
            @PathParam("org") String org,
            @PathParam("project") String project,
            @PathParam("repositoryId") String repositoryId,
            List<RefUpdate> refUpdates,
            @QueryParam("api-version") @DefaultValue(API_VERSION) String apiVersion
    );

    /** Commits one or more file edits onto an existing ref. A stale {@code oldObjectId} (the
     * branch moved since it was read) surfaces as a non-2xx status, which throws normally. */
    @POST
    @Path("/{org}/{project}/_apis/git/repositories/{repositoryId}/pushes")
    @Consumes(MediaType.APPLICATION_JSON)
    Response push(
            @PathParam("org") String org,
            @PathParam("project") String project,
            @PathParam("repositoryId") String repositoryId,
            GitPushRequest request,
            @QueryParam("api-version") @DefaultValue(API_VERSION) String apiVersion
    );

    /** Opens a pull request from {@code sourceRefName} into {@code targetRefName}. */
    @POST
    @Path("/{org}/{project}/_apis/git/repositories/{repositoryId}/pullrequests")
    @Consumes(MediaType.APPLICATION_JSON)
    PullRequestResponse createPullRequest(
            @PathParam("org") String org,
            @PathParam("project") String project,
            @PathParam("repositoryId") String repositoryId,
            CreatePullRequestRequest request,
            @QueryParam("api-version") @DefaultValue(API_VERSION) String apiVersion
    );
}
