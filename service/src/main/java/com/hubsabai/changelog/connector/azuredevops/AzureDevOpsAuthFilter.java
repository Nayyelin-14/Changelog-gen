package com.hubsabai.changelog.connector.azuredevops;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.ws.rs.client.ClientRequestContext;
import jakarta.ws.rs.client.ClientRequestFilter;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.io.IOException;
import java.util.Base64;

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
