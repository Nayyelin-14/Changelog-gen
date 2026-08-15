package com.hubsabai.changelog.connector.github;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.ws.rs.client.ClientRequestContext;
import jakarta.ws.rs.client.ClientRequestFilter;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.io.IOException;

@ApplicationScoped
public class GitHubOrgAuthFilter implements ClientRequestFilter {

    private final String token;

    public GitHubOrgAuthFilter(
            @ConfigProperty(name = "github.token", defaultValue = "CHANGE_ME") String token) {
        this.token = token;
    }

    @Override
    public void filter(ClientRequestContext requestContext) throws IOException {
        if (token != null && !token.isBlank() && !"CHANGE_ME".equals(token)) {
            requestContext.getHeaders().add("Authorization", "Bearer " + token);
        }
    }
}