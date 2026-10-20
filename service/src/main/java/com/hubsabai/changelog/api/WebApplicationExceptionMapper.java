package com.hubsabai.changelog.api;

import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.container.ResourceInfo;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriInfo;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Downstream provider failures (bad project/repo/branch name, revoked PAT, etc.) arrive as
 * {@link WebApplicationException}. Provider text can carry URLs and credentials, so this mapper
 * logs the upstream status server-side and answers with a safe message.
 */
@Provider
public class WebApplicationExceptionMapper implements ExceptionMapper<WebApplicationException> {

    private static final Logger LOG = Logger.getLogger(WebApplicationExceptionMapper.class.getName());

    @Context
    UriInfo uriInfo;

    @Context
    ResourceInfo resourceInfo;

    @Override
    public Response toResponse(WebApplicationException exception) {
        Response original = exception.getResponse();
        // Explicitly-safe responses (ApiErrors) must pass through untouched. This keeps 503s
        // from ensureEnabled() and other intentional error envelopes from being re-labelled as
        // an upstream 502.
        if (original != null
                && original.getEntity() instanceof java.util.Map<?, ?> body
                && body.containsKey("error")
                && body.containsKey("message")) {
            return original;
        }
        String path = uriInfo != null ? uriInfo.getPath() : null;
        String requestPath = uriInfo != null ? uriInfo.getRequestUri().getPath() : path;
        boolean apiRequest = requestPath != null
                && (requestPath.equals("/api") || requestPath.startsWith("/api/"));

        // RESTEasy throws its own WebApplicationException (NotFoundException) for any request
        // that matches no route at all. A frontend route like /dev/projects/xxx ends up here too
        // because RESTEasy's Vert.x handler processes every incoming request; serving the SPA
        // shell lets Quinoa/React handle it instead of showing a raw JSON 404.
        if (resourceInfo == null || resourceInfo.getResourceMethod() == null) {
            if (!apiRequest) {
                String html = readIndexHtml();
                if (html != null) {
                    return Response.ok(html, MediaType.TEXT_HTML_TYPE).build();
                }
            }
            return ApiErrors.notFound();
        }

        int upstreamStatus = exception.getResponse() != null ? exception.getResponse().getStatus() : 502;
        boolean isGitHubPath = path != null && path.contains("/github");

        LOG.log(Level.WARNING, "Upstream call failed with HTTP " + upstreamStatus
                + " for API path " + (path != null ? path : "unknown")
                + " [requestId=" + RequestIdFilter.current() + "]");

        if (upstreamStatus == 404) {
            return ApiErrors.notFound();
        }

        if (isGitHubPath) {
            return ApiErrors.upstreamUnavailable();
        }

        // Azure DevOps sign-in redirect (302) and 401/403 all mean the stored credential is
        // missing, expired, revoked, or out of scope. Say what to do without naming the PAT.
        if (upstreamStatus == 302 || upstreamStatus == 401 || upstreamStatus == 403) {
            return ApiErrors.json(Response.Status.BAD_GATEWAY.getStatusCode(),
                    ApiErrors.UPSTREAM_UNAVAILABLE,
                    "The Azure DevOps connection needs attention. Please ask an administrator to reconnect it.",
                    false);
        }

        return ApiErrors.upstreamUnavailable();
    }

    /** Reads the Quinoa-built SPA shell from the classpath. */
    private static String readIndexHtml() {
        try (InputStream in = Thread.currentThread().getContextClassLoader()
                .getResourceAsStream("META-INF/resources/index.html")) {
            if (in == null) return null;
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            return null;
        }
    }
}
