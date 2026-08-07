package com.hubsabai.changelog.api;

import com.hubsabai.changelog.ai.AiException;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.container.ResourceInfo;
import jakarta.ws.rs.core.Response;
import java.lang.reflect.Method;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExceptionMappersTest {

    private final AiExceptionMapper aiMapper = new AiExceptionMapper();
    private final GenericExceptionMapper genericMapper = new GenericExceptionMapper();
    private final IllegalStateExceptionMapper illegalStateMapper = new IllegalStateExceptionMapper();

    /** Stands in for the real per-request injection of {@code @Context ResourceInfo} — every test
     * below represents a request that DID match one of our endpoints (its own downstream Azure
     * DevOps call is what failed), so the mapper must see a matched resource method, exactly like
     * it would in production. */
    private static WebApplicationExceptionMapper mapperForMatchedRoute() {
        WebApplicationExceptionMapper mapper = new WebApplicationExceptionMapper();
        Method someMethod = ExceptionMappersTest.class.getDeclaredMethods()[0];
        mapper.resourceInfo = new ResourceInfo() {
            @Override
            public Method getResourceMethod() {
                return someMethod;
            }

            @Override
            public Class<?> getResourceClass() {
                return ExceptionMappersTest.class;
            }
        };
        return mapper;
    }

    @Test
    void aiExceptionReturns400WithHint() {
        Response response = aiMapper.toResponse(new AiException("model xyz failed"));
        assertEquals(400, response.getStatus());
        String body = response.getEntity().toString();
        assertTrue(body.contains("model xyz failed"));
        assertTrue(body.contains("Try a different model"));
    }

    @Test
    void genericExceptionReturns500WithSafeMessage() {
        Response response = genericMapper.toResponse(new RuntimeException("underlying db failure"));
        assertEquals(500, response.getStatus());
        String body = response.getEntity().toString();
        assertTrue(body.contains("Something went wrong"));
    }

    @Test
    void illegalStateExceptionReturns502WithItsMessage() {
        Response response = illegalStateMapper.toResponse(new IllegalStateException("Azure DevOps returned HTML"));
        assertEquals(502, response.getStatus());
        String body = response.getEntity().toString();
        assertTrue(body.contains("Azure DevOps returned HTML"));
    }

    @Test
    void illegalStateExceptionReturns502WithFallbackWhenMessageIsNull() {
        Response response = illegalStateMapper.toResponse(new IllegalStateException());
        assertEquals(502, response.getStatus());
        String body = response.getEntity().toString();
        assertTrue(body.contains("Upstream call failed"));
    }

    @Test
    void webApplicationExceptionWith404ReturnsNotFoundMessage() {
        WebApplicationExceptionMapper mapper = mapperForMatchedRoute();
        Response upstream = Response.status(404).build();
        WebApplicationException ex = new WebApplicationException(upstream);
        Response response = mapper.toResponse(ex);
        assertEquals(404, response.getStatus());
        String body = response.getEntity().toString();
        assertTrue(body.contains("Project, repository, or branch not found"));
    }

    @Test
    void webApplicationExceptionWithOtherStatusReturnsBadGateway() {
        WebApplicationExceptionMapper mapper = mapperForMatchedRoute();
        Response upstream = Response.status(401).build();
        WebApplicationException ex = new WebApplicationException(upstream);
        Response response = mapper.toResponse(ex);
        assertEquals(502, response.getStatus());
        String body = response.getEntity().toString();
        assertTrue(body.contains("Azure DevOps request failed"));
    }

    @Test
    void webApplicationExceptionWithNullResponseDefaultsTo502() {
        WebApplicationExceptionMapper mapper = mapperForMatchedRoute();
        WebApplicationException ex = new WebApplicationException((Response) null);
        Response response = mapper.toResponse(ex);
        assertEquals(502, response.getStatus());
    }

    @Test
    void webApplicationExceptionOnUnmatchedRouteReturnsGenericNotFound() {
        // No resourceInfo set at all here — this is RESTEasy's own NotFoundException for a
        // request that matched none of our endpoints, the case this mapper must not confuse with
        // a matched endpoint whose own downstream call happened to fail with a 404.
        WebApplicationExceptionMapper mapper = new WebApplicationExceptionMapper();
        WebApplicationException ex = new WebApplicationException(Response.status(404).build());
        Response response = mapper.toResponse(ex);
        assertEquals(404, response.getStatus());
        String body = response.getEntity().toString();
        assertTrue(body.contains("Not found."));
        assertTrue(!body.contains("Project, repository, or branch"));
    }
}
