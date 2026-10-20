package com.hubsabai.changelog.api;

import com.hubsabai.changelog.ai.AiException;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.container.ResourceInfo;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriInfo;
import java.lang.reflect.Method;
import java.net.URI;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

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
    void aiExceptionReturns400WithSafeMessage() {
        Response response = aiMapper.toResponse(new AiException("provider secret leaked"));
        assertEquals(400, response.getStatus());
        String body = response.getEntity().toString();
        assertTrue(body.contains("AI request couldn't be completed"));
        assertTrue(!body.contains("provider secret leaked"));
    }

    @Test
    void aiExceptionKeepsShortValidationMessages() {
        Response response = aiMapper.toResponse(new AiException("A version is required."));
        assertEquals(400, response.getStatus());
        String body = response.getEntity().toString();
        assertTrue(body.contains("A version is required."));
    }

    @Test
    void aiExceptionHidesProviderDiagnostics() {
        Response response = aiMapper.toResponse(
                new AiException("Chat call failed (model xyz): HTTP 429 — token=abc"));
        assertEquals(400, response.getStatus());
        String body = response.getEntity().toString();
        assertTrue(body.contains("AI request couldn't be completed"));
        assertTrue(!body.contains("HTTP 429"));
        assertTrue(!body.contains("token=abc"));
    }

    @Test
    void genericExceptionReturns500WithSafeMessage() {
        Response response = genericMapper.toResponse(new RuntimeException("underlying db failure"));
        assertEquals(500, response.getStatus());
        String body = response.getEntity().toString();
        assertTrue(body.contains("Something went wrong"));
    }

    @Test
    void illegalStateExceptionReturns502WithoutProviderDetail() {
        Response response = illegalStateMapper.toResponse(
                new IllegalStateException("Azure DevOps returned HTML with token=abc"));
        assertEquals(502, response.getStatus());
        String body = response.getEntity().toString();
        assertTrue(body.contains("external service is temporarily unavailable"));
        assertTrue(!body.contains("token=abc"));
        assertTrue(!body.contains("Azure DevOps returned HTML"));
    }

    @Test
    void illegalStateExceptionWithNoMessageStillReturnsSafe502() {
        Response response = illegalStateMapper.toResponse(new IllegalStateException());
        assertEquals(502, response.getStatus());
        String body = response.getEntity().toString();
        assertTrue(body.contains("external service is temporarily unavailable"));
    }

    @Test
    void webApplicationExceptionWith404ReturnsNotFoundMessage() {
        WebApplicationExceptionMapper mapper = mapperForMatchedRoute();
        Response upstream = Response.status(404).build();
        WebApplicationException ex = new WebApplicationException(upstream);
        Response response = mapper.toResponse(ex);
        assertEquals(404, response.getStatus());
        String body = response.getEntity().toString();
        assertTrue(body.contains("couldn't find"));
    }

    @Test
    void webApplicationExceptionWith401ReturnsSafeReconnectMessage() {
        WebApplicationExceptionMapper mapper = mapperForMatchedRoute();
        Response upstream = Response.status(401).build();
        WebApplicationException ex = new WebApplicationException(upstream);
        Response response = mapper.toResponse(ex);
        assertEquals(502, response.getStatus());
        String body = response.getEntity().toString();
        assertTrue(body.contains("connection needs attention"));
        assertTrue(!body.contains("PAT"));
        assertTrue(!body.contains("expired, revoked"));
    }

    @Test
    void webApplicationExceptionWithOtherStatusReturnsBadGateway() {
        WebApplicationExceptionMapper mapper = mapperForMatchedRoute();
        Response upstream = Response.status(500).build();
        WebApplicationException ex = new WebApplicationException(upstream);
        Response response = mapper.toResponse(ex);
        assertEquals(502, response.getStatus());
        String body = response.getEntity().toString();
        assertTrue(body.contains("external service is temporarily unavailable"));
    }

    @Test
    void webApplicationExceptionPassesThroughExplicitSafeEnvelope() {
        WebApplicationExceptionMapper mapper = mapperForMatchedRoute();
        WebApplicationException ex = new WebApplicationException(ApiErrors.serviceUnavailable());
        Response response = mapper.toResponse(ex);
        assertEquals(503, response.getStatus());
        String body = response.getEntity().toString();
        assertTrue(body.contains("service_unavailable"));
        assertTrue(body.contains("temporarily unavailable"));
    }

    @Test
    void webApplicationExceptionWithNullResponseDefaultsTo502() {
        WebApplicationExceptionMapper mapper = mapperForMatchedRoute();
        WebApplicationException ex = new WebApplicationException((Response) null);
        Response response = mapper.toResponse(ex);
        assertEquals(502, response.getStatus());
    }

    @Test
    void unmatchedApiRouteReturnsJsonNotFoundInsteadOfTheSpaShell() {
        WebApplicationExceptionMapper mapper = new WebApplicationExceptionMapper();
        UriInfo uriInfo = mock(UriInfo.class);
        when(uriInfo.getPath()).thenReturn("definitely-not-a-route");
        when(uriInfo.getRequestUri()).thenReturn(URI.create("http://localhost/api/definitely-not-a-route"));
        mapper.uriInfo = uriInfo;

        Response response = mapper.toResponse(new WebApplicationException(Response.status(404).build()));
        assertEquals(404, response.getStatus());
        assertTrue(response.getEntity().toString().contains("couldn't find"));
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
        assertTrue(body.contains("couldn't find"));
        assertTrue(!body.contains("Project, repository, or branch"));
    }
}
