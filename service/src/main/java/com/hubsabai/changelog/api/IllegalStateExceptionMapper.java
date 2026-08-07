package com.hubsabai.changelog.api;

import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;

import java.util.Map;

/**
 * Covers AzureDevOpsOrgConnector's upstream-call failures (bad/expired PAT, Azure DevOps
 * returning HTML instead of JSON, etc.) — those carry a specific, actionable message that would
 * otherwise be discarded by GenericExceptionMapper's generic "check the logs" 500.
 */
@Provider
public class IllegalStateExceptionMapper implements ExceptionMapper<IllegalStateException> {

    @Override
    public Response toResponse(IllegalStateException exception) {
        return Response.status(Response.Status.BAD_GATEWAY)
                .type(MediaType.APPLICATION_JSON)
                .entity(Map.of("error", exception.getMessage() != null ? exception.getMessage() : "Upstream call failed"))
                .build();
    }
}
