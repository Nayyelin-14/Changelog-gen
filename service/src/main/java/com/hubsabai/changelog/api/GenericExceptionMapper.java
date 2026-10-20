package com.hubsabai.changelog.api;

import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;

import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Catch-all for anything not covered by a more specific mapper. The full stack trace stays in the
 * server log, correlated by request id; the client only gets a safe message.
 */
@Provider
public class GenericExceptionMapper implements ExceptionMapper<Exception> {

    private static final Logger LOG = Logger.getLogger(GenericExceptionMapper.class.getName());

    @Override
    public Response toResponse(Exception exception) {
        LOG.log(Level.SEVERE, "Unhandled exception [requestId=" + RequestIdFilter.current() + "]", exception);
        return ApiErrors.internalError();
    }
}
