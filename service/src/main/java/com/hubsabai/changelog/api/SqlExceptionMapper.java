package com.hubsabai.changelog.api;

import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;

import java.sql.SQLException;
import java.util.logging.Level;
import java.util.logging.Logger;

/** Raw {@link SQLException}s (not wrapped by Hibernate) take the same safe path. */
@Provider
public class SqlExceptionMapper implements ExceptionMapper<SQLException> {

    private static final Logger LOG = Logger.getLogger(SqlExceptionMapper.class.getName());

    @Override
    public Response toResponse(SQLException exception) {
        String requestId = RequestIdFilter.current();
        if (DatabaseFailures.isUnavailable(exception)) {
            LOG.log(Level.WARNING, "Database unavailable [requestId=" + requestId + "]", exception);
            return ApiErrors.serviceUnavailable();
        }
        LOG.log(Level.SEVERE, "SQL failure [requestId=" + requestId + "]", exception);
        return ApiErrors.internalError();
    }
}
