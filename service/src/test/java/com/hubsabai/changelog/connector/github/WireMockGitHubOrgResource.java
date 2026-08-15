package com.hubsabai.changelog.connector.github;

import com.github.tomakehurst.wiremock.WireMockServer;
import io.quarkus.test.common.QuarkusTestResourceLifecycleManager;

import java.util.Map;

/**
 * Boots a WireMock server in place of {@code api.github.com} for {@code @QuarkusTestResource}-
 * annotated tests. The GitHub REST client is configured with {@code github/mp-rest/url} (which
 * overrides the {@code baseUri} on {@link GitHubOrgRestClient}), so stubbing at repo paths like
 * {@code /repos/test-owner/...} exercises the connector against its own base URL the same way the
 * Azure test swaps dev.azure.com.
 */
public class WireMockGitHubOrgResource implements QuarkusTestResourceLifecycleManager {

    private static WireMockServer server;

    @Override
    public Map<String, String> start() {
        server = new WireMockServer(0);
        server.start();
        return Map.of(
                "github/mp-rest/url", server.baseUrl(),
                "github.owner", "test-owner",
                "github.token", "test-token"
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