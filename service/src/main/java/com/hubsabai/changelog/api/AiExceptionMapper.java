package com.hubsabai.changelog.api;

import com.hubsabai.changelog.ai.AiException;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;

import java.util.Map;

@Provider
public class AiExceptionMapper implements ExceptionMapper<AiException> {

    @Override
    public Response toResponse(AiException exception) {
        return Response.status(Response.Status.BAD_REQUEST)
                .type(MediaType.APPLICATION_JSON)
                .entity(Map.of(
                        "error", exception.getMessage(),
                        "hint", "Try a different model from the list."
                ))
                .build();
    }
}
