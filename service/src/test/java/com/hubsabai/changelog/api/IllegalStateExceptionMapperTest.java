package com.hubsabai.changelog.api;

import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Pure unit test, no Quarkus context needed — this mapper has no dependencies to inject.
 *
 * <p>Two things are guarded here: AzureDevOpsOrgConnector's PAT-troubleshooting message (an
 * IllegalStateException) must not fall through to GenericExceptionMapper's generic 500, and the
 * upstream text must not be echoed to the caller verbatim — it routinely carries URLs, provider
 * diagnostics and, on a bad token exchange, credentials.
 */
class IllegalStateExceptionMapperTest {

    private static final String GENERIC = "The upstream provider could not be reached.";

    private final IllegalStateExceptionMapper mapper = new IllegalStateExceptionMapper();

    @Test
    void mapsToBadGatewayAndKeepsACleanOperationalMessage() {
        Response response = mapper.toResponse(new IllegalStateException("Azure DevOps call failed: HTTP 401"));

        assertEquals(502, response.getStatus());
        assertEquals("Upstream call failed", error(response));
        assertEquals("Azure DevOps call failed: HTTP 401", detail(response));
    }

    @Test
    void fallsBackToAGenericDetailWhenTheExceptionHasNone() {
        Response response = mapper.toResponse(new IllegalStateException());

        assertEquals(502, response.getStatus());
        assertEquals("The upstream provider could not be reached.", detail(response));
    }

    @Test
    void dropsMessagesCarryingAUrl() {
        Response response = mapper.toResponse(
                new IllegalStateException("call to https://dev.azure.com/org/_apis/build failed"));

        assertEquals(502, response.getStatus());
        assertEquals(GENERIC, detail(response), "an upstream URL must not reach the caller");
    }

    @Test
    void dropsMessagesCarryingCredentialShapedText() {
        Response response = mapper.toResponse(
                new IllegalStateException("Authorization: Bearer ghp_secret_value"));

        assertEquals(502, response.getStatus());
        assertEquals(GENERIC, detail(response), "credential-shaped text must not reach the caller");
    }

    @Test
    void dropsOverlongMessagesRatherThanEchoingProviderDumps() {
        String dump = "x".repeat(IllegalStateExceptionMapper.MAX_DETAIL_LENGTH + 1);

        Response response = mapper.toResponse(new IllegalStateException(dump));

        assertEquals(502, response.getStatus());
        assertEquals(GENERIC, detail(response));
    }

    @Test
    void sanitizeIsTheSinglePlaceDecidingWhatPassesThrough() {
        assertEquals("No commits found", IllegalStateExceptionMapper.sanitize("No commits found"));
        assertNull(IllegalStateExceptionMapper.sanitize(null));
        assertNull(IllegalStateExceptionMapper.sanitize("   "));
        assertNull(IllegalStateExceptionMapper.sanitize("token=abc"));
        assertNull(IllegalStateExceptionMapper.sanitize("jdbc:postgresql://db:5432/app"));
    }

    private static String error(Response response) {
        @SuppressWarnings("unchecked")
        var body = (java.util.Map<String, String>) response.getEntity();
        return body.get("error");
    }

    private static String detail(Response response) {
        @SuppressWarnings("unchecked")
        var body = (java.util.Map<String, String>) response.getEntity();
        return body.get("detail");
    }
}
