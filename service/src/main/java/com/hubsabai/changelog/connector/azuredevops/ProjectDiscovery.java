package com.hubsabai.changelog.connector.azuredevops;

import com.hubsabai.changelog.connector.azuredevops.dto.AzureDevOpsListResponse;
import com.hubsabai.changelog.connector.azuredevops.dto.ProjectResponse;
import com.hubsabai.changelog.core.model.ProjectSummary;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.GenericType;
import jakarta.ws.rs.core.Response;

import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.eclipse.microprofile.rest.client.inject.RestClient;

import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;

/**
 * Discovers all projects in the Azure DevOps organization.
 */
@ApplicationScoped
public class ProjectDiscovery {

    private static final int PROJECT_PAGE_SIZE = 100;
    private static final int MAX_PAGES = 50;

    @Inject
    @RestClient
    AzureDevOpsRestClient client;

    @Inject
    @ConfigProperty(name = "azure.devops.org", defaultValue = "CHANGE_ME")
    String org;

    private static final Logger LOG = Logger.getLogger(ProjectDiscovery.class.getName());

    /**
     * Lists every project in the org, following the {@code x-ms-continuationtoken} header until it's absent.
     */
    public List<ProjectSummary> listProjects() {
        List<ProjectSummary> projects = new ArrayList<>();
        String continuationToken = null;

        for (int page = 0; page < MAX_PAGES; page++) {
            try (Response response = client.listProjectsPage(org, continuationToken, PROJECT_PAGE_SIZE, AzureDevOpsRestClient.API_VERSION)) {
                int status = response.getStatus();
                if (status < 200 || status >= 300) {
                    String body = response.readEntity(String.class);
                    String snippet = body.length() > 300 ? body.substring(0, 300) + "…" : body;
                    String hint = status == 401 || status == 203
                            ? " — check that the PAT is valid, not expired, and has the required scopes."
                            : "";
                    throw new IllegalStateException(
                            "Azure DevOps call failed while listing projects for org '" + org + "': HTTP "
                                    + status + hint + "\nBody: " + snippet);
                }
                AzureDevOpsListResponse<ProjectResponse> body =
                        response.readEntity(new GenericType<AzureDevOpsListResponse<ProjectResponse>>() {});
                for (ProjectResponse p : body.valueOrEmpty()) {
                    projects.add(new ProjectSummary(p.id(), p.name(), p.description()));
                }
                continuationToken = response.getHeaderString("x-ms-continuationtoken");
            } catch (jakarta.ws.rs.WebApplicationException e) {
                throw new IllegalStateException("Azure DevOps call failed while listing projects for org '" + org + "': " + e.getMessage(), e);
            }
            if (continuationToken == null || continuationToken.isBlank()) {
                break;
            }
        }
        return projects;
    }
}