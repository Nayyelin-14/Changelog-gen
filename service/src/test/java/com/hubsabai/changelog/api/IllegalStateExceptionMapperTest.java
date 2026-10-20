package com.hubsabai.changelog.api;

import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IllegalStateExceptionMapperTest {

    private final IllegalStateExceptionMapper mapper = new IllegalStateExceptionMapper();

    @Test
    void returnsSafeBadGatewayWithoutProviderText() {
        Response response = mapper.toResponse(
                new IllegalStateException("call to https://dev.azure.com/org/_apis/build failed: token=abc"));

        assertEquals(502, response.getStatus());
        @SuppressWarnings("unchecked")
        var body = (java.util.Map<String, Object>) response.getEntity();
        assertEquals("upstream_unavailable", body.get("error"));
        assertTrue(body.get("message").toString().contains("external service"));
        assertFalse(body.toString().contains("dev.azure.com"));
        assertFalse(body.toString().contains("token=abc"));
    }

    @Test
    void returnsSafeBadGatewayWhenMessageIsMissing() {
        Response response = mapper.toResponse(new IllegalStateException());
        assertEquals(502, response.getStatus());
        assertTrue(response.getEntity().toString().contains("external service"));
    }
}
