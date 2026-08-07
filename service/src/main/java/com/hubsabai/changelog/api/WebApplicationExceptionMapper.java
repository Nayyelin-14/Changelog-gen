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
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Downstream Azure DevOps REST client calls (bad project/repo/branch name, revoked PAT, etc.)
 * throw {@link WebApplicationException} carrying the upstream status code. Without this mapper
 * they fall through to Quarkus's default handler and leak a raw Java stack trace to API clients
 * instead of a usable error.
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
        String path = uriInfo != null ? uriInfo.getPath() : null;

        // RESTEasy throws its own WebApplicationException (NotFoundException) for any request
        // that matches no route at all — no resource method gets matched in that case.
        //
        // A frontend route like /dev/projects/xxx ends up here too because RESTEasy's Vert.x
        // handler processes every incoming request (even with quarkus.rest.path=/api it still
        // catches all paths and only then checks route matching). Returning {"error":"Not found."}
        // for those would prevent Quinoa's SPA routing from ever serving index.html — the user
        // gets a raw JSON error on every page refresh.
        //
        // The path-prefix check is safe here because this branch (no matched resource method)
        // never fires for a real API endpoint failure — those always have a matched method.
        if (resourceInfo == null || resourceInfo.getResourceMethod() == null) {
            if (path != null && !path.startsWith("/api") && !path.startsWith("api/")) {
                // Serve the SPA shell so React can parse the URL and render the right page.
                // Quinoa embeds the built frontend under META-INF/resources/index.html.
                String html = readIndexHtml();
                if (html != null) {
                    return Response.ok(html, MediaType.TEXT_HTML_TYPE).build();
                }
            }
            return Response.status(Response.Status.NOT_FOUND)
                    .type(MediaType.APPLICATION_JSON)
                    .entity(Map.of("error", "Not found."))
                    .build();
        }

        int upstreamStatus = exception.getResponse() != null ? exception.getResponse().getStatus() : 502;
        LOG.log(Level.WARNING, "Azure DevOps call failed with HTTP " + upstreamStatus + " for our API path: " + (path != null ? path : "unknown"));

        if (upstreamStatus == 404) {
            return Response.status(Response.Status.NOT_FOUND)
                    .type(MediaType.APPLICATION_JSON)
                    .entity(Map.of("error", "Project, repository, or branch not found — check the names and try again."))
                    .build();
        }

        return Response.status(Response.Status.BAD_GATEWAY)
                .type(MediaType.APPLICATION_JSON)
                .entity(Map.of("error", "Azure DevOps request failed (HTTP " + upstreamStatus + ")."))
                .build();
    }

    /** Reads the Quinoa-built SPA shell from the classpath. Quinoa copies the built frontend
     * (web-view/dist/) into META-INF/resources/ at package time, so index.html is on the classpath
     * inside the fast-jar. Returns null if the file isn't found (e.g. the JAR was built without
     * Quinoa, or something deleted it — stick to the API 404 rather than crashing). */
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
