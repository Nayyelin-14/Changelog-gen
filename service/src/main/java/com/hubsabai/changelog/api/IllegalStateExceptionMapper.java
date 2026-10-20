package com.hubsabai.changelog.api;

import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;

import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Covers upstream-call failures (bad/expired PAT, provider HTML instead of JSON, etc.). The
 * provider text routinely contains URLs and credentials, so it is logged but never returned.
 */
@Provider
public class IllegalStateExceptionMapper implements ExceptionMapper<IllegalStateException> {

    private static final Logger LOG = Logger.getLogger(IllegalStateExceptionMapper.class.getName());

    @Override
    public Response toResponse(IllegalStateException exception) {
        LOG.log(Level.WARNING, "Upstream call failed [requestId=" + RequestIdFilter.current() + "]", exception);
        return ApiErrors.upstreamUnavailable();
    }
}
