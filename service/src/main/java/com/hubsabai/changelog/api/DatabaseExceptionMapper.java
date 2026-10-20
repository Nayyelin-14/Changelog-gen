package com.hubsabai.changelog.api;

import jakarta.persistence.PersistenceException;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;

import java.util.logging.Level;
import java.util.logging.Logger;

/** Keeps database outages out of the generic 500 path and out of the client payload. */
@Provider
public class DatabaseExceptionMapper implements ExceptionMapper<PersistenceException> {

    private static final Logger LOG = Logger.getLogger(DatabaseExceptionMapper.class.getName());

    @Override
    public Response toResponse(PersistenceException exception) {
        String requestId = RequestIdFilter.current();
        if (DatabaseFailures.isUnavailable(exception)) {
            LOG.log(Level.WARNING, "Database unavailable [requestId=" + requestId + "]", exception);
            return ApiErrors.serviceUnavailable();
        }
        LOG.log(Level.SEVERE, "Persistence failure [requestId=" + requestId + "]", exception);
        return ApiErrors.internalError();
    }
}
