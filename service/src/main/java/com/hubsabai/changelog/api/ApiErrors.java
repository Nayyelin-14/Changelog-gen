package com.hubsabai.changelog.api;

import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Safe, client-facing error responses. Nothing here includes exception text, SQL, provider
 * diagnostics, or credential-shaped details — those stay in the server logs.
 */
public final class ApiErrors {

    public static final String INTERNAL_ERROR = "internal_error";
    public static final String SERVICE_UNAVAILABLE = "service_unavailable";
    public static final String UPSTREAM_UNAVAILABLE = "upstream_unavailable";
    public static final String TIMEOUT = "timeout";
    public static final String NOT_FOUND = "not_found";

    private static final String INTERNAL_MESSAGE = "Something went wrong on our side. Please try again.";
    private static final String UNAVAILABLE_MESSAGE =
            "The service is temporarily unavailable. Please try again in a moment.";
    private static final String UPSTREAM_MESSAGE =
            "An external service is temporarily unavailable. Please try again in a moment.";
    private static final String TIMEOUT_MESSAGE = "That took too long. Please try again.";

    private ApiErrors() {
    }

    public static Response internalError() {
        return json(Response.Status.INTERNAL_SERVER_ERROR.getStatusCode(),
                INTERNAL_ERROR, INTERNAL_MESSAGE, false);
    }

    public static Response serviceUnavailable() {
        return json(Response.Status.SERVICE_UNAVAILABLE.getStatusCode(),
                SERVICE_UNAVAILABLE, UNAVAILABLE_MESSAGE, true);
    }

    public static Response upstreamUnavailable() {
        return json(Response.Status.BAD_GATEWAY.getStatusCode(),
                UPSTREAM_UNAVAILABLE, UPSTREAM_MESSAGE, true);
    }

    public static Response timeout() {
        return json(Response.Status.GATEWAY_TIMEOUT.getStatusCode(),
                TIMEOUT, TIMEOUT_MESSAGE, true);
    }

    public static Response notFound() {
        return json(Response.Status.NOT_FOUND.getStatusCode(),
                NOT_FOUND, "We couldn't find what you were looking for.", false);
    }

    public static Response json(int status, String code, String message, boolean retryable) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", code);
        body.put("message", message);
        String requestId = RequestIdFilter.current();
        if (requestId != null) {
            body.put("requestId", requestId);
        }
        Response.ResponseBuilder builder = Response.status(status)
                .type(MediaType.APPLICATION_JSON)
                .entity(body);
        if (requestId != null) {
            builder.header(RequestIdFilter.HEADER, requestId);
        }
        if (retryable) {
            builder.header("Retry-After", "30");
        }
        return builder.build();
    }
}
