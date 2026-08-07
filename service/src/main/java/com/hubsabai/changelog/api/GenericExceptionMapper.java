package com.hubsabai.changelog.api;

import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;

import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Catch-all for anything not covered by a more specific mapper (e.g. {@link AiExceptionMapper},
 * {@link WebApplicationExceptionMapper}) — JAX-RS always picks the most specific applicable
 * mapper, so this only fires for genuinely unexpected failures. Logs the full stack trace
 * server-side but never sends it to the client.
 */
@Provider
public class GenericExceptionMapper implements ExceptionMapper<Exception> {

    private static final Logger LOG = Logger.getLogger(GenericExceptionMapper.class.getName());

    @Override
    public Response toResponse(Exception exception) {
        LOG.log(Level.SEVERE, "Unhandled exception", exception);
        return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                .type(MediaType.APPLICATION_JSON)
                .entity(Map.of("error", "Something went wrong on the server. Check the service logs for details."))
                .build();
    }
}
