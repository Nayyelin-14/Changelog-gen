package com.hubsabai.changelog.auth;

import com.hubsabai.changelog.api.DatabaseFailures;
import com.hubsabai.changelog.api.RequestIdFilter;
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

import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Resolves the {@code cc_session} cookie into the request-scoped {@link CurrentUser}. Runs for
 * every request but only ever reads. A database outage must not turn every request into a 500:
 * the request continues as anonymous and {@code AuthenticatedFilter} answers 503 for protected
 * endpoints instead of pretending the session expired.
 */
@Provider
@RegisterForReflection
@Priority(Priorities.AUTHENTICATION)
public class AuthSessionFilter implements ContainerRequestFilter {

    public static final String AUTH_UNAVAILABLE = "auth.session.unavailable";

    private static final Logger LOG = Logger.getLogger(AuthSessionFilter.class.getName());

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
        try {
            sessions.resolve(cookie).ifPresent(resolved ->
                    currentUser.set(resolved.user(), resolved.accessToken()));
        } catch (RuntimeException e) {
            requestContext.setProperty(AUTH_UNAVAILABLE, Boolean.TRUE);
            String requestId = RequestIdFilter.current();
            if (DatabaseFailures.isUnavailable(e)) {
                LOG.log(Level.WARNING, "Session lookup unavailable [requestId=" + requestId + "]", e);
            } else {
                LOG.log(Level.WARNING, "Session lookup failed [requestId=" + requestId + "]", e);
            }
        }
    }
}
