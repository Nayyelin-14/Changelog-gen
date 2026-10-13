package com.hubsabai.changelog.api;

import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;

import java.util.Map;

/**
 * Covers AzureDevOpsOrgConnector's upstream-call failures (bad/expired PAT, Azure DevOps
 * returning HTML instead of JSON, etc.) — those carry a specific, actionable message that would
 * otherwise be discarded by GenericExceptionMapper's generic "check the logs" 500.
 */
@Provider
public class IllegalStateExceptionMapper implements ExceptionMapper<IllegalStateException> {

    /** Upstream messages routinely carry URLs, repo paths and provider diagnostics. Pass through
     * only a short, clean operational line; everything else is logged and replaced. */
    static final int MAX_DETAIL_LENGTH = 200;

    private static final java.util.List<String> SECRET_MARKERS = java.util.List.of(
            "bearer ", "authorization", "token=", "pat", "secret", "password", "access_token");

    @Override
    public Response toResponse(IllegalStateException exception) {
        String detail = sanitize(exception.getMessage());
        return Response.status(Response.Status.BAD_GATEWAY)
                .type(MediaType.APPLICATION_JSON)
                .entity(Map.of("error", "Upstream call failed",
                        "detail", detail != null ? detail : "The upstream provider could not be reached."))
                .build();
    }

    /** {@code null} when the message is too long, too secret-shaped, or simply not for a client. */
    static String sanitize(String message) {
        if (message == null || message.isBlank()) {
            return null;
        }
        String trimmed = message.trim();
        if (trimmed.length() > MAX_DETAIL_LENGTH) {
            return null;
        }
        String lower = trimmed.toLowerCase(java.util.Locale.ROOT);
        for (String marker : SECRET_MARKERS) {
            if (lower.contains(marker)) {
                return null;
            }
        }
        // Scheme-bearing text is almost always a URL (which can embed a credential or an internal
        // host) — keep the response free of it.
        if (trimmed.contains("://")) {
            return null;
        }
        return trimmed;
    }
}
