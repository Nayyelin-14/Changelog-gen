package com.hubsabai.changelog.api;

import com.hubsabai.changelog.ai.AiException;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;

import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Pattern;

/**
 * Validation failures (missing version/model/audience) stay actionable. Provider failures are
 * logged and replaced with a generic message — their text routinely carries HTTP status codes,
 * model names, and token-shaped details.
 */
@Provider
public class AiExceptionMapper implements ExceptionMapper<AiException> {

    private static final Logger LOG = Logger.getLogger(AiExceptionMapper.class.getName());
    private static final int MAX_SAFE_MESSAGE = 180;
    private static final Pattern UNSAFE = Pattern.compile(
            "(?i)(https?://|\\bhttps?\\b|bearer|authorization|token|api[_ -]?key|secret|password|"
                    + "providers?\\s+(?:error|failed|returned|response|call|request|diagnostic|failure|timeout)|"
                    + "unavailable\\s*:|stack ?trace|\\bsql\\b|jdbc|quota|rate[ -]?limit|"
                    + "\\b(?:429|500|501|502|503|504)\\b|\\bfailed\\b|\\btimeout\\b|\\btimed out\\b|"
                    + "exception|last error)");

    @Override
    public Response toResponse(AiException exception) {
        LOG.log(Level.WARNING, "AI request failed [requestId=" + RequestIdFilter.current() + "]", exception);
        String safe = safeMessage(exception.getMessage());
        if (safe != null) {
            return ApiErrors.json(Response.Status.BAD_REQUEST.getStatusCode(),
                    safe, safe, false);
        }
        return ApiErrors.json(Response.Status.BAD_REQUEST.getStatusCode(),
                "ai_request_failed",
                "The AI request couldn't be completed. Please try again or choose a different model.",
                false);
    }

    static String safeMessage(String message) {
        if (message == null) return null;
        String trimmed = message.trim();
        if (trimmed.isEmpty() || trimmed.length() > MAX_SAFE_MESSAGE) return null;
        if (UNSAFE.matcher(trimmed).find()) return null;
        return trimmed;
    }
}
