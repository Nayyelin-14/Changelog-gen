package com.hubsabai.changelog.auth;

import com.github.tomakehurst.wiremock.WireMockServer;
import io.quarkus.test.common.QuarkusTestResourceLifecycleManager;

import java.util.Map;

/**
 * Boots a WireMock server as BOTH {@code github.com} (authorize / token exchange) and
 * {@code api.github.com} (user lookup, token revocation) for the OAuth endpoints, by pointing
 * {@code github.oauth.github-base-url}/{@code github.oauth.api-base-url} at it. Keeps the auth
 * endpoints fully testable without any real GitHub credentials.
 */
public class WireMockGitHubAuthResource implements QuarkusTestResourceLifecycleManager {

    private static WireMockServer server;

    @Override
    public Map<String, String> start() {
        server = new WireMockServer(0);
        server.start();
        return Map.of(
                "github.oauth.github-base-url", server.baseUrl(),
                "github.oauth.api-base-url", server.baseUrl()
        );
    }

    @Override
    public void stop() {
        if (server != null) {
            server.stop();
            server = null;
        }
    }

    public static WireMockServer server() {
        return server;
    }
}