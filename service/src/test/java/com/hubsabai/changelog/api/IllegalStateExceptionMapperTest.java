package com.hubsabai.changelog.api;

import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pure unit test, no Quarkus context needed — this mapper has no dependencies to inject.
 * Guards against AzureDevOpsOrgConnector's PAT-troubleshooting message (an IllegalStateException)
 * silently falling through to GenericExceptionMapper's generic 500 again in the future.
 */
class IllegalStateExceptionMapperTest {

    @Test
    void mapsToBadGatewayWithTheOriginalMessage() {
        IllegalStateExceptionMapper mapper = new IllegalStateExceptionMapper();

        Response response = mapper.toResponse(new IllegalStateException("Azure DevOps call failed: HTTP 401"));

        assertEquals(502, response.getStatus());
        @SuppressWarnings("unchecked")
        var body = (java.util.Map<String, String>) response.getEntity();
        assertTrue(body.get("error").contains("Azure DevOps call failed"));
    }

    @Test
    void fallsBackToAGenericMessageWhenTheExceptionHasNone() {
        IllegalStateExceptionMapper mapper = new IllegalStateExceptionMapper();

        Response response = mapper.toResponse(new IllegalStateException());

        assertEquals(502, response.getStatus());
        @SuppressWarnings("unchecked")
        var body = (java.util.Map<String, String>) response.getEntity();
        assertEquals("Upstream call failed", body.get("error"));
    }
}
