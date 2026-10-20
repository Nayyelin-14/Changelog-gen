package com.hubsabai.changelog.api;

import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;

import java.util.concurrent.TimeoutException;
import java.util.logging.Level;
import java.util.logging.Logger;

@Provider
public class TimeoutExceptionMapper implements ExceptionMapper<TimeoutException> {

    private static final Logger LOG = Logger.getLogger(TimeoutExceptionMapper.class.getName());

    @Override
    public Response toResponse(TimeoutException exception) {
        LOG.log(Level.WARNING, "Request timed out [requestId=" + RequestIdFilter.current() + "]", exception);
        return ApiErrors.timeout();
    }
}
