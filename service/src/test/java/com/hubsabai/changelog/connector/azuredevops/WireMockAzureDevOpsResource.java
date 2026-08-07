package com.hubsabai.changelog.connector.azuredevops;

import com.github.tomakehurst.wiremock.WireMockServer;
import io.quarkus.test.common.QuarkusTestResourceLifecycleManager;

import java.util.Map;

/**
 * Boots a WireMock server in place of {@code dev.azure.com} for {@code @QuarkusTestResource}-annotated tests.
 * The running server is reachable via {@link #server()} — a static holder rather than field injection,
 * since Quarkus test resources start before the test instance exists.
 */
public class WireMockAzureDevOpsResource implements QuarkusTestResourceLifecycleManager {

    private static WireMockServer server;

    @Override
    public Map<String, String> start() {
        server = new WireMockServer(0);
        server.start();
        return Map.of(
                "azure-devops/mp-rest/url", server.baseUrl(),
                "azure.devops.org", "test-org",
                "azure.devops.pat", "test-pat"
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
