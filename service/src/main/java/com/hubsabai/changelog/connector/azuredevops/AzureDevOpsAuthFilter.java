package com.hubsabai.changelog.connector.azuredevops;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.ws.rs.client.ClientRequestContext;
import jakarta.ws.rs.client.ClientRequestFilter;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.io.IOException;
import java.util.Base64;

/**
 * Stamps the <b>shared</b> Azure DevOps service PAT onto every outbound Azure call.
 *
 * <p><b>Known limitation, by design of Azure DevOps itself:</b> Azure DevOps has no per-user OAuth
 * in this app, so every signed-in user's Azure calls run as this one identity. That means a
 * signed-in user can reach every Azure repository the PAT can reach. It is therefore only ever
 * attached behind {@code AuthenticatedFilter} (a signed-in user is required) and it is <em>not</em>
 * a source of truth about what any particular user may see — the DB {@code owner_user_id} column
 * is what keeps one user's changelogs out of another's hands. GitHub, which does have per-user
 * OAuth, does not have this limitation.
 */
@ApplicationScoped
public class AzureDevOpsAuthFilter implements ClientRequestFilter {

    private final String pat;

    public AzureDevOpsAuthFilter(
            @ConfigProperty(name = "azure.devops.pat", defaultValue = "CHANGE_ME") String pat) {
        this.pat = pat;
    }

    @Override
    public void filter(ClientRequestContext requestContext) throws IOException {
        if (pat != null && !pat.isBlank() && !"CHANGE_ME".equals(pat)) {
            String token = Base64.getEncoder().encodeToString((":" + pat).getBytes());
            requestContext.getHeaders().add("Authorization", "Basic " + token);
        }
    }
}
