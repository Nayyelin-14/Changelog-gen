package com.hubsabai.changelog.api;

import jakarta.annotation.Priority;
import jakarta.ws.rs.Priorities;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.Provider;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

/**
 * Gate for anything annotated {@link PipelineAuth} — one key per calling project rather than one
 * shared secret, so a new project is onboarded by adding an entry and one project's access is
 * revoked by removing its entry, without touching anyone else's key.
 */
@Provider
@PipelineAuth
@Priority(Priorities.AUTHENTICATION)
public class PipelineAuthFilter implements ContainerRequestFilter {

    @ConfigProperty(name = "pipeline.api-keys")
    Optional<String> configuredKeys;

    @Override
    public void filter(ContainerRequestContext requestContext) {
        List<String> validKeys = configuredKeys
                .map(PipelineAuthFilter::splitKeys)
                .orElse(List.of());

        // Fail closed: no keys configured means nothing can authenticate, not "let everything through."
        String presentedKey = validKeys.isEmpty() ? null : extractBearerToken(requestContext);

        if (presentedKey == null || validKeys.stream().noneMatch(key -> constantTimeEquals(key, presentedKey))) {
            requestContext.abortWith(Response.status(Response.Status.UNAUTHORIZED).build());
        }
    }

    private static List<String> splitKeys(String raw) {
        return Arrays.stream(raw.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList();
    }

    private static String extractBearerToken(ContainerRequestContext requestContext) {
        String header = requestContext.getHeaderString(HttpHeaders.AUTHORIZATION);
        if (header == null || !header.startsWith("Bearer ")) {
            return null;
        }
        String token = header.substring("Bearer ".length()).trim();
        return token.isEmpty() ? null : token;
    }

    /** Avoids leaking key-length/prefix info via response-time differences. */
    private static boolean constantTimeEquals(String a, String b) {
        return MessageDigest.isEqual(
                a.getBytes(StandardCharsets.UTF_8),
                b.getBytes(StandardCharsets.UTF_8));
    }
}
