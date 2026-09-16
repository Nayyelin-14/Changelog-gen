package com.hubsabai.changelog.auth;

import io.quarkus.runtime.annotations.RegisterForReflection;
import jakarta.annotation.Priority;
import jakarta.inject.Inject;
import jakarta.ws.rs.Priorities;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.ext.Provider;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * Resolves the {@code cc_session} cookie into the request-scoped {@link CurrentUser}. Runs for
 * every request (it's a plain {@code @Provider}) but only ever reads — authorization decisions
 * live in the resources and the GitHub client filter, so anonymous traffic keeps its current
 * behavior unchanged.
 */
@Provider
@RegisterForReflection
@Priority(Priorities.AUTHENTICATION)
public class AuthSessionFilter implements ContainerRequestFilter {

    @Inject
    SessionService sessions;

    @Inject
    CurrentUser currentUser;

    @Inject
    @ConfigProperty(name = "auth.cookie.secure", defaultValue = "true")
    boolean secureCookies;

    @Context
    HttpHeaders httpHeaders;

    @Override
    public void filter(ContainerRequestContext requestContext) {
        String cookie = SessionService.readCookie(httpHeaders, SessionService.COOKIE_NAME)
                .orElse(null);
        if (cookie == null) {
            return;
        }
        sessions.resolve(cookie).ifPresent(resolved ->
                currentUser.set(resolved.user(), resolved.accessToken()));
    }
}