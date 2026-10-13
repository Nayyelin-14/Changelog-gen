package com.hubsabai.changelog.api;

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
 * <p>Ownership (403) is <em>not</em> decided here — see {@code AccessGuard}. This filter only
 * answers "is anyone signed in?", so that every resource can assume a user exists.
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
        if (currentUser.isPresent() || currentUser.isService()) {
            return;
        }
        requestContext.abortWith(Response.status(Response.Status.UNAUTHORIZED)
                .type(MediaType.APPLICATION_JSON)
                .entity(Map.of("error", "not_authenticated"))
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
