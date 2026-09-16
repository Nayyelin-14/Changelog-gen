package com.hubsabai.changelog.connector.github;

import com.hubsabai.changelog.auth.CurrentUser;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.context.ContextNotActiveException;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import jakarta.ws.rs.client.ClientRequestContext;
import jakarta.ws.rs.client.ClientRequestFilter;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.io.IOException;

/**
 * Stamps the GitHub REST client's identity per call:
 * <ul>
 *   <li>a signed-in user's OAuth token ({@link CurrentUser}) — the per-user path — or</li>
 *   <li>the service account's {@code github.token} (kept for the dashboard's non-logged-in /
 *       pipeline mode), or</li>
 *   <li>nothing, when no token exists — GitHub then answers 401 and the caller surfaces a
 *       sign-in prompt.</li>
 * </ul>
 * {@code Instance} + a {@code ContextNotActiveException} guard keeps the fallback safe on worker
 * threads (lazy run capture) that have no HTTP request context.
 */
@ApplicationScoped
public class GitHubOrgAuthFilter implements ClientRequestFilter {

    private final String serviceToken;
    private final Instance<CurrentUser> currentUser;

    @Inject
    public GitHubOrgAuthFilter(
            @ConfigProperty(name = "github.token", defaultValue = "CHANGE_ME") String serviceToken,
            Instance<CurrentUser> currentUser) {
        this.serviceToken = serviceToken;
        this.currentUser = currentUser;
    }

    @Override
    public void filter(ClientRequestContext requestContext) throws IOException {
        String userToken = userAccessToken();
        if (userToken != null && !userToken.isBlank()) {
            requestContext.getHeaders().add("Authorization", "Bearer " + userToken);
            return;
        }
        if (serviceToken != null && !serviceToken.isBlank() && !"CHANGE_ME".equals(serviceToken)) {
            requestContext.getHeaders().add("Authorization", "Bearer " + serviceToken);
        }
    }

    private String userAccessToken() {
        try {
            if (currentUser.isResolvable() && currentUser.get() != null) {
                return currentUser.get().accessToken().orElse(null);
            }
        } catch (ContextNotActiveException e) {
            // No HTTP request context on this thread — fall through to the service token.
        }
        return null;
    }
}