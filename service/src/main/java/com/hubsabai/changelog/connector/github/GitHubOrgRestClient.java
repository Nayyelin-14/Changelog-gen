package com.hubsabai.changelog.connector.github;

import com.hubsabai.changelog.connector.github.dto.GitHubBlob;
import com.hubsabai.changelog.connector.github.dto.GitHubCommit;
import com.hubsabai.changelog.connector.github.dto.GitHubCommitSha;
import com.hubsabai.changelog.connector.github.dto.GitHubCreatePullRequest;
import com.hubsabai.changelog.connector.github.dto.GitHubCreateRef;
import com.hubsabai.changelog.connector.github.dto.GitHubCreatedPullRequest;
import com.hubsabai.changelog.connector.github.dto.GitHubOrgUser;
import com.hubsabai.changelog.connector.github.dto.GitHubPullRequest;
import com.hubsabai.changelog.connector.github.dto.GitHubTree;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.faulttolerance.Timeout;
import org.eclipse.microprofile.rest.client.annotation.RegisterProvider;
import org.eclipse.microprofile.rest.client.inject.RegisterRestClient;

import java.time.temporal.ChronoUnit;

/**
 * MicroProfile REST client for the GitHub REST API. Unlike Azure DevOps (which wraps lists in a
 * {@code { value: [...] }} envelope), GitHub returns bare JSON arrays, so the response-bearing
 * calls return the raw {@link Response} and the connector unwraps them into typed lists via
 * Jackson. Identity auth comes from {@link GitHubOrgAuthFilter}.
 */
@RegisterRestClient(configKey = "github", baseUri = "https://api.github.com")
@RegisterProvider(GitHubOrgAuthFilter.class)
@Produces(MediaType.APPLICATION_JSON)
@Timeout(value = 15, unit = ChronoUnit.SECONDS)
public interface GitHubOrgRestClient {

    /** Repos the authenticated token can access — includes private repos (unlike the public
     * {@code /users/{owner}/repos} endpoint, which only ever lists public repos). */
    @GET
    @Path("/user/repos")
    Response listAuthenticatedRepositories(
            @QueryParam("affiliation") @DefaultValue("owner") String affiliation,
            @QueryParam("per_page") @DefaultValue("100") int perPage,
            @QueryParam("page") @DefaultValue("1") int page);

    @GET
    @Path("/repos")
    Response listRepositories(
            @QueryParam("affiliation") @DefaultValue("owner,collaborator,organization_member") String affiliation,
            @QueryParam("per_page") @DefaultValue("100") int perPage,
            @QueryParam("page") @DefaultValue("1") int page);

    @GET
    @Path("/orgs/{owner}/repos")
    Response listOrgRepositories(
            @PathParam("owner") String owner,
            @QueryParam("per_page") @DefaultValue("100") int perPage,
            @QueryParam("page") @DefaultValue("1") int page);

    @GET
    @Path("/users/{owner}/repos")
    Response listUserRepositories(
            @PathParam("owner") String owner,
            @QueryParam("per_page") @DefaultValue("100") int perPage,
            @QueryParam("page") @DefaultValue("1") int page);

    @GET
    @Path("/orgs/{owner}")
    GitHubOrgUser getOrg(@PathParam("owner") String owner);

    @GET
    @Path("/users/{owner}")
    GitHubOrgUser getUser(@PathParam("owner") String owner);

    /** Recent workflow runs for a repo (GitHub Actions). Returns the same data shape as Azure's
     * pipeline runs list, mapped from GitHub's `/repos/{owner}/{repo}/actions/runs`. */
    @GET
    @Path("/repos/{owner}/{repo}/actions/runs")
    Response listWorkflowRuns(
            @PathParam("owner") String owner,
            @PathParam("repo") String repo,
            @QueryParam("per_page") @DefaultValue("20") int perPage,
            @QueryParam("page") @DefaultValue("1") int page);

    /** Get a single workflow run by its database ID (for fetching source data for changelog generation). */
    @GET
    @Path("/repos/{owner}/{repo}/actions/runs/{run_id}")
    Response getWorkflowRun(
            @PathParam("owner") String owner,
            @PathParam("repo") String repo,
            @PathParam("run_id") long runId);

    /** Compare commits between two SHAs (GitHub Compare API). */
    @GET
    @Path("/repos/{owner}/{repo}/compare/{base}...{head}")
    Response compareCommits(
            @PathParam("owner") String owner,
            @PathParam("repo") String repo,
            @PathParam("base") String base,
            @PathParam("head") String head);

    /** Get a single commit by SHA. */
    @GET
    @Path("/repos/{owner}/{repo}/branches")
    Response listBranches(
            @PathParam("owner") String owner,
            @PathParam("repo") String repo,
            @QueryParam("per_page") @DefaultValue("100") int perPage);

@GET
    @Path("/repos/{owner}/{repo}/tags")
    Response listTags(
            @PathParam("owner") String owner,
            @PathParam("repo") String repo,
            @QueryParam("per_page") @DefaultValue("100") int perPage,
            @QueryParam("page") @DefaultValue("1") int page);

    /** Compare commits between two refs/commits in the same repo — returns the commits and a
     * {@code ahead_by}/{@code behind_by} summary. Range boundary detection for version fetches. */
    @GET
    @Path("/repos/{owner}/{repo}/commits")
    Response listCommits(
            @PathParam("owner") String owner,
            @PathParam("repo") String repo,
            @QueryParam("sha") String sha,
            @QueryParam("since") String since,
            @QueryParam("until") String until,
            @QueryParam("per_page") @DefaultValue("100") int perPage,
            @QueryParam("page") @DefaultValue("1") int page);

    /** A single commit including its {@code files[]} (changed paths). */
    @GET
    @Path("/repos/{owner}/{repo}/commits/{sha}")
    GitHubCommit getCommit(
            @PathParam("owner") String owner,
            @PathParam("repo") String repo,
            @PathParam("sha") String sha);

    @GET
    @Path("/repos/{owner}/{repo}/pulls")
    Response listPullRequests(
            @PathParam("owner") String owner,
            @PathParam("repo") String repo,
            @QueryParam("state") @DefaultValue("all") String state,
            @QueryParam("base") String base,
            @QueryParam("per_page") @DefaultValue("100") int perPage,
            @QueryParam("page") @DefaultValue("1") int page);

    @GET
    @Path("/repos/{owner}/{repo}/pulls/{number}")
    GitHubPullRequest getPullRequest(
            @PathParam("owner") String owner,
            @PathParam("repo") String repo,
            @PathParam("number") long number);

    /** PRs that mention a specific commit sha — to map repo history to PRs. */
    @GET
    @Path("/repos/{owner}/{repo}/commits/{sha}/pulls")
    Response listPullRequestsForCommit(
            @PathParam("owner") String owner,
            @PathParam("repo") String repo,
            @PathParam("sha") String sha);

    /** The commits that make up one pull request — for the PR details view. */
    @GET
    @Path("/repos/{owner}/{repo}/pulls/{number}/commits")
    Response listPullRequestCommits(
            @PathParam("owner") String owner,
            @PathParam("repo") String repo,
            @PathParam("number") long number,
            @QueryParam("per_page") @DefaultValue("100") int perPage);

    /** Compare two refs/commits in the same repo — returns the commits and a
     * {@code ahead_by}/{@code behind_by} summary. Range boundary detection for version fetches. */
    @GET
    @Path("/repos/{owner}/{repo}/compare/{base}...{head}")
    Response compare(
            @PathParam("owner") String owner,
            @PathParam("repo") String repo,
            @PathParam("base") String base,
            @PathParam("head") String head);

    /** Raw file content at {@code path} on {@code branch} — base64 in {@code content}. 404 if the file
     * doesn't exist there. */
    @GET
    @Path("/repos/{owner}/{repo}/contents/{path}")
    Response getFile(
            @PathParam("owner") String owner,
            @PathParam("repo") String repo,
            @PathParam("path") String path,
            @QueryParam("ref") String branch);

    @GET
    @Path("/repos/{owner}/{repo}/commits")
    Response listPathCommits(
            @PathParam("owner") String owner,
            @PathParam("repo") String repo,
            @QueryParam("path") String path,
            @QueryParam("sha") String branch,
            @QueryParam("per_page") @DefaultValue("100") int perPage);

    // ---- git data API (used to open a PR carrying the pushed changelog) ----

    @POST
    @Path("/repos/{owner}/{repo}/git/refs")
    @Consumes(MediaType.APPLICATION_JSON)
    Response createRef(
            @PathParam("owner") String owner,
            @PathParam("repo") String repo,
            GitHubCreateRef body);

    @POST
    @Path("/repos/{owner}/{repo}/git/blobs")
    @Consumes(MediaType.APPLICATION_JSON)
    GitHubBlob createBlob(
            @PathParam("owner") String owner,
            @PathParam("repo") String repo,
            String json);

    @POST
    @Path("/repos/{owner}/{repo}/git/trees")
    @Consumes(MediaType.APPLICATION_JSON)
    GitHubTree createTree(
            @PathParam("owner") String owner,
            @PathParam("repo") String repo,
            String json);

    @POST
    @Path("/repos/{owner}/{repo}/git/commits")
    @Consumes(MediaType.APPLICATION_JSON)
    GitHubCommitSha createCommit(
            @PathParam("owner") String owner,
            @PathParam("repo") String repo,
            String json);

    @PUT
    @Path("/repos/{owner}/{repo}/git/refs/{ref}")
    @Consumes(MediaType.APPLICATION_JSON)
    Response updateRef(
            @PathParam("owner") String owner,
            @PathParam("repo") String repo,
            @PathParam("ref") String ref,
            String json);

    @POST
    @Path("/repos/{owner}/{repo}/pulls")
    @Consumes(MediaType.APPLICATION_JSON)
    GitHubCreatedPullRequest createPullRequest(
            @PathParam("owner") String owner,
            @PathParam("repo") String repo,
            GitHubCreatePullRequest body);
}