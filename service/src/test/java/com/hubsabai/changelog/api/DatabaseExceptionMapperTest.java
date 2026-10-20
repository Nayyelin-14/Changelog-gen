package com.hubsabai.changelog.api;

import jakarta.persistence.PersistenceException;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.Test;

import java.sql.SQLException;
import java.util.concurrent.TimeoutException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DatabaseExceptionMapperTest {

    private final DatabaseExceptionMapper persistenceMapper = new DatabaseExceptionMapper();
    private final SqlExceptionMapper sqlMapper = new SqlExceptionMapper();

    @Test
    void quotaAndConnectionStatesBecomeSafe503() {
        Response quota = persistenceMapper.toResponse(
                new PersistenceException(new SQLException("quota exceeded", "53000")));
        assertEquals(503, quota.getStatus());
        assertEquals("service_unavailable", body(quota).get("error"));
        assertFalse(quota.getEntity().toString().contains("quota"));

        Response connection = sqlMapper.toResponse(new SQLException("connection refused", "08006"));
        assertEquals(503, connection.getStatus());
        assertFalse(connection.getEntity().toString().contains("connection refused"));
    }

    @Test
    void constraintViolationIsNotReportedAsAnOutage() {
        Response response = persistenceMapper.toResponse(
                new PersistenceException(new SQLException("duplicate key", "23505")));
        assertEquals(500, response.getStatus());
        assertEquals("internal_error", body(response).get("error"));
    }

    @Test
    void rawSqlExceptionUsesTheSameSafePath() {
        Response response = sqlMapper.toResponse(new SQLException("secret table name"));
        assertEquals(503, response.getStatus());
        assertFalse(response.getEntity().toString().contains("secret table name"));
    }

    @Test
    void timeoutReturnsGatewayTimeout() {
        Response response = new TimeoutExceptionMapper().toResponse(new TimeoutException("slow"));
        assertEquals(504, response.getStatus());
        assertEquals("timeout", body(response).get("error"));
        assertFalse(response.getEntity().toString().contains("slow"));
    }

    @Test
    void recognizesNestedConnectionFailures() {
        assertTrue(DatabaseFailures.isUnavailable(
                new PersistenceException(new IllegalStateException(new SQLException("x", "08001")))));
        assertFalse(DatabaseFailures.isUnavailable(
                new PersistenceException(new SQLException("x", "23505"))));
        assertTrue(DatabaseFailures.isUnavailable(new java.net.ConnectException("refused")));
    }

    @SuppressWarnings("unchecked")
    private static java.util.Map<String, Object> body(Response response) {
        return (java.util.Map<String, Object>) response.getEntity();
    }
}
