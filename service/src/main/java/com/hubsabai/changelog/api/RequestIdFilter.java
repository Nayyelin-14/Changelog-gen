package com.hubsabai.changelog.api;

import jakarta.annotation.Priority;
import jakarta.ws.rs.Priorities;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.container.ContainerResponseContext;
import jakarta.ws.rs.container.ContainerResponseFilter;
import jakarta.ws.rs.ext.Provider;

import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Correlates a client-visible failure with the server log without exposing the failure itself.
 * Accepts a caller-supplied ID only when it is short and safe; otherwise generates one.
 */
@Provider
@Priority(Priorities.AUTHENTICATION - 100)
public class RequestIdFilter implements ContainerRequestFilter, ContainerResponseFilter {

    public static final String HEADER = "X-Request-ID";

    private static final Pattern SAFE_ID = Pattern.compile("[A-Za-z0-9._-]{8,64}");
    private static final ThreadLocal<String> CURRENT = new ThreadLocal<>();

    public static String current() {
        return CURRENT.get();
    }

    @Override
    public void filter(ContainerRequestContext requestContext) {
        String supplied = requestContext.getHeaderString(HEADER);
        String id = supplied != null && SAFE_ID.matcher(supplied).matches()
                ? supplied
                : UUID.randomUUID().toString();
        CURRENT.set(id);
        requestContext.setProperty(HEADER, id);
        requestContext.getHeaders().putSingle(HEADER, id);
    }

    @Override
    public void filter(ContainerRequestContext requestContext, ContainerResponseContext responseContext) {
        String id = CURRENT.get();
        if (id != null) {
            responseContext.getHeaders().putSingle(HEADER, id);
        }
        CURRENT.remove();
    }
}
