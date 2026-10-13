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
 *   <li>a signed-in user's OAuth token ({@link CurrentUser}) — the per-user path, and by far the
 *       common one; or</li>
 *   <li>the service account's {@code github.token}, but <b>only</b> when the caller authenticated
 *       as a pipeline/service caller ({@link CurrentUser#isService()}). A CI runner has no OAuth
 *       identity of its own, so this is a deliberate, narrow exception.</li>
 * </ul>
 *
 * <p>There is deliberately <b>no</b> fallback for anonymous or worker-thread callers: sending the
 * shared service token would silently turn any unauthenticated request into the service account's
 * full access to every repository it can reach. Without a token, GitHub answers 401 and the caller
 * surfaces a sign-in prompt instead.
 *
 * <p>{@code Instance} plus a {@link ContextNotActiveException} guard keeps the lookup safe off the
 * request thread; the guard now means "no token", not "use the service token".
 */
@ApplicationScoped
public class GitHubOrgAuthFilter implements ClientRequestFilter {

    private static final String CHANGE_ME = "CHANGE_ME";

    private final String serviceToken;
    private final Instance<CurrentUser> currentUser;

    @Inject
    public GitHubOrgAuthFilter(
            @ConfigProperty(name = "github.token", defaultValue = CHANGE_ME) String serviceToken,
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
        if (isServiceRequest() && hasServiceToken()) {
            requestContext.getHeaders().add("Authorization", "Bearer " + serviceToken);
        }
    }

    private String userAccessToken() {
        try {
            if (currentUser.isResolvable() && currentUser.get() != null) {
                return currentUser.get().accessToken().orElse(null);
            }
        } catch (ContextNotActiveException ignored) {
            // No HTTP request context on this thread — fail closed, no token.
        }
        return null;
    }

    /** Only a pipeline-authenticated request may act as the shared service account. */
    private boolean isServiceRequest() {
        try {
            return currentUser.isResolvable() && currentUser.get() != null && currentUser.get().isService();
        } catch (ContextNotActiveException ignored) {
            return false;
        }
    }

    private boolean hasServiceToken() {
        return serviceToken != null && !serviceToken.isBlank() && !CHANGE_ME.equals(serviceToken);
    }
}
