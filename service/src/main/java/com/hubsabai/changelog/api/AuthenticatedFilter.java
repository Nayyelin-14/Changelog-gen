package com.hubsabai.changelog.api;

import com.hubsabai.changelog.auth.AuthSessionFilter;
import com.hubsabai.changelog.auth.CurrentUser;
import jakarta.annotation.Priority;
import jakarta.inject.Inject;
import jakarta.ws.rs.Priorities;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.container.ResourceInfo;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.Provider;

import java.lang.reflect.Method;
import java.util.Map;
import java.util.Set;

/**
 * Default-deny authentication for the whole {@code /api} surface. Anything without a valid
 * {@code cc_session} cookie (or a pipeline API key) gets <b>401</b>.
 *
 * <p>Allowlisted (never requires a user):
 * <ul>
 *   <li>{@code /api/auth/**} — the sign-in, {@code /me} and logout endpoints. {@code /me} already
 *       answers 401 for itself, and the OAuth dance must be reachable from a logged-out browser.</li>
 *   <li>Anything annotated {@link PipelineAuth} — those have their own fail-closed key check.</li>
 *   <li>{@code /q/**} — Quarkus health/metrics; outside the REST base path anyway, kept defensive.</li>
 * </ul>
 *
 * <p>Ownership (403) is <em>not</em> decided here — see {@code AccessGuard}. The only 403 this
 * filter answers is {@code github_not_connected} below: a signed-in user without a GitHub
 * credential cannot be allowed to fetch GitHub data as an anonymous caller.
 *
 * <p>Runs at {@link Priorities#AUTHORIZATION}, after {@link AuthSessionFilter} at
 * {@link Priorities#AUTHENTICATION} has populated {@link CurrentUser}.
 */
@Provider
@Priority(Priorities.AUTHORIZATION)
public class AuthenticatedFilter implements ContainerRequestFilter {

    private static final Set<String> PUBLIC_PATH_PREFIXES = Set.of("/auth/", "/q/");

    @Inject
    CurrentUser currentUser;

    @Context
    ResourceInfo resourceInfo;

    @Override
    public void filter(ContainerRequestContext requestContext) {
        String path = resourcePath(requestContext);

        if (isPublicPath(path) || hasPipelineAuth()) {
            return;
        }
        if (Boolean.TRUE.equals(requestContext.getProperty(AuthSessionFilter.AUTH_UNAVAILABLE))) {
            requestContext.abortWith(ApiErrors.serviceUnavailable());
            return;
        }
        if (currentUser.isPresent() || currentUser.isService()) {
            denyDisconnectedGithub(requestContext, path);
            return;
        }
        requestContext.abortWith(Response.status(Response.Status.UNAUTHORIZED)
                .type(MediaType.APPLICATION_JSON)
                .entity(Map.of("error", "not_authenticated"))
                .build());
    }

    /**
     * A signed-in user who disconnected GitHub has no credential to fetch GitHub data with — and
     * GitHub still serves <b>public</b> repositories without one, so falling through would keep the
     * dashboard working behind a "disconnected" warning that isn't true. Answer 403 with a code the
     * frontend turns into a reconnect screen instead of silently fetching as an anonymous caller.
     *
     * <p>Scoped to {@code github/} only: Azure rides the shared service PAT (unaffected by a
     * personal disconnect), {@code /api/github/pipeline/**} authenticates with its own
     * {@link PipelineAuth} key (and already returned above), and service callers are exempt.
     */
    private void denyDisconnectedGithub(ContainerRequestContext requestContext, String path) {
        if (!path.startsWith("github/") || currentUser.isService()) {
            return;
        }
        if (currentUser.accessToken().isPresent()) {
            return;
        }
        requestContext.abortWith(Response.status(Response.Status.FORBIDDEN)
                .type(MediaType.APPLICATION_JSON)
                .entity(Map.of("error", "github_not_connected",
                        "hint", "Reconnect GitHub to fetch repository data."))
                .build());
    }

    /**
     * The request path relative to the {@code /api} base, without leading slashes — e.g.
     * {@code auth/github/authorize}. {@code UriInfo#getPath()} already strips the base and may or
     * may not keep a leading slash depending on the matching resource, so both shapes are
     * normalized here rather than guessed at.
     */
    private static String resourcePath(ContainerRequestContext requestContext) {
        String path = nullToEmpty(requestContext.getUriInfo().getPath());
        int start = 0;
        while (start < path.length() && path.charAt(start) == '/') {
            start++;
        }
        path = path.substring(start);
        // Defensive: if the base path is ever reported as part of the path, strip it too.
        if (path.startsWith("api/")) {
            path = path.substring("api/".length());
        }
        return path;
    }

    private static boolean isPublicPath(String path) {
        return path.isEmpty()
                || PUBLIC_PATH_PREFIXES.stream().anyMatch(prefix -> path.startsWith(prefix.substring(1)));
    }

    /** A {@link PipelineAuth} resource/method authenticates with a pipeline key instead of a session. */
    private boolean hasPipelineAuth() {
        Method method = resourceInfo == null ? null : resourceInfo.getResourceMethod();
        if (method == null) {
            // No method matched (404/405) — let the framework answer rather than masking it with 401.
            return true;
        }
        return method.isAnnotationPresent(PipelineAuth.class)
                || method.getDeclaringClass().isAnnotationPresent(PipelineAuth.class);
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}
