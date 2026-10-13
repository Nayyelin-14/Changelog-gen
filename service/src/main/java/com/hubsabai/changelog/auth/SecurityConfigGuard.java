package com.hubsabai.changelog.auth;

import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

/**
 * Refuses to start in a non-dev profile when {@code auth.session-secret} is missing, placeholder,
 * or too weak to derive a real key from.
 *
 * <p>That secret is the single input to two things that must not be guessable: the SHA-256 digest
 * under which every session token is stored (so it doubles as the session-keying secret) and the
 * AES-256-GCM key that protects stored OAuth tokens at rest. Shipping the placeholder value to
 * production would mean every session cookie could be forged by anyone who reads this repository.
 *
 * <p>Deliberately fails <em>startup</em>, not first use: a half-configured auth stack that boots
 * and then 500s on sign-in is worse than one that refuses to boot.
 */
@ApplicationScoped
public class SecurityConfigGuard {

    private static final Logger LOG = Logger.getLogger(SecurityConfigGuard.class.getName());

    /** Must stay in sync with the default in {@code application.properties}. */
    static final String PLACEHOLDER = "change-me-in-production";
    private static final int MIN_SECRET_LENGTH = 32;

    void onStart(@Observes StartupEvent event,
                 @ConfigProperty(name = "quarkus.profile", defaultValue = "prod") String profile,
                 @ConfigProperty(name = "auth.session-secret", defaultValue = "") String sessionSecret) {
        if (isDevProfile(profile)) {
            if (sessionSecret == null || sessionSecret.isBlank()) {
                LOG.warn("auth.session-secret is blank in the dev profile — GitHub sign-in will be disabled.");
            }
            return;
        }
        String problem = describeProblem(sessionSecret);
        if (problem == null) {
            return;
        }
        throw new IllegalStateException(
                "Refusing to start: " + problem
                        + " Generate one with `openssl rand -base64 48` and set AUTH_SESSION_SECRET.");
    }

    static boolean isDevProfile(String profile) {
        return profile != null && (profile.contains("dev") || profile.contains("test"));
    }

    /** {@code null} when the secret is usable; otherwise the reason it is not. */
    static String describeProblem(String sessionSecret) {
        if (sessionSecret == null || sessionSecret.isBlank()) {
            return "auth.session-secret is not set.";
        }
        if (PLACEHOLDER.equals(sessionSecret)) {
            return "auth.session-secret is still the shipped placeholder value.";
        }
        if (sessionSecret.trim().length() < MIN_SECRET_LENGTH) {
            return "auth.session-secret is shorter than " + MIN_SECRET_LENGTH + " characters.";
        }
        return null;
    }
}
