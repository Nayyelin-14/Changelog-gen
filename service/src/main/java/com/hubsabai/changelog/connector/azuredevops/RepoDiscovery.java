package com.hubsabai.changelog.connector.azuredevops;

import com.hubsabai.changelog.connector.azuredevops.dto.AzureDevOpsListResponse;
import com.hubsabai.changelog.connector.azuredevops.dto.GitRef;
import com.hubsabai.changelog.connector.azuredevops.dto.RepositoryResponse;
import com.hubsabai.changelog.core.model.RepositorySummary;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.GenericType;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.eclipse.microprofile.rest.client.inject.RestClient;

import java.util.List;
import java.util.Objects;
import java.util.logging.Logger;

/**
 * Discovers repositories within a project.
 */
@ApplicationScoped
public class RepoDiscovery {

    @Inject
    @RestClient
    AzureDevOpsRestClient client;

    @Inject
    @ConfigProperty(name = "azure.devops.org", defaultValue = "CHANGE_ME")
    String org;

    private static final Logger LOG = Logger.getLogger(RepoDiscovery.class.getName());

    /**
     * Repositories don't paginate on Azure DevOps — one call returns all of them for the project.
     */
    public List<RepositorySummary> listRepositories(String project) {
        AzureDevOpsListResponse<RepositoryResponse> response =
                client.listRepositories(org, project, AzureDevOpsRestClient.API_VERSION);
        return response.valueOrEmpty().stream()
                .map(r -> new RepositorySummary(r.id(), r.name(), project, r.defaultBranch()))
                .toList();
    }

    /**
     * The repo's default branch as a short name (e.g. {@code "main"}), or null if the repo can't be found.
     */
    public String defaultBranch(String project, String repo) {
        return listRepositories(project).stream()
                .filter(r -> r.name().equals(repo))
                .findFirst()
                .map(RepositorySummary::defaultBranch)
                .map(ref -> ref != null && ref.startsWith("refs/heads/") ? ref.substring("refs/heads/".length()) : ref)
                .orElse(null);
    }

    /**
     * All branch short names for a repo (e.g. {@code "main"}, {@code "develop"}), or empty if none/on failure.
     */
    public List<String> listBranches(String project, String repo) {
        try {
            Response response = client.listRefs(org, project, repo, "heads/", true, AzureDevOpsRestClient.API_VERSION);
            try {
                AzureDevOpsListResponse<GitRef> body =
                        response.readEntity(new GenericType<AzureDevOpsListResponse<GitRef>>() {});
                return body.valueOrEmpty().stream()
                        .map(GitRef::name)
                        .filter(Objects::nonNull)
                        .map(name -> name.startsWith("refs/heads/") ? name.substring("refs/heads/".length()) : name)
                        .toList();
            } finally {
                response.close();
            }
        } catch (Exception e) {
            LOG.warning("listBranches failed for " + project + "/" + repo + ": " + e);
            return List.of();
        }
    }
}