package com.hubsabai.changelog.api;

import io.quarkus.test.common.QuarkusTestResourceLifecycleManager;

import java.util.Map;

public class PipelineTestResource implements QuarkusTestResourceLifecycleManager {

    @Override
    public Map<String, String> start() {
        return Map.of("pipeline.api-keys", "test-only-fixture-key");
    }

    @Override
    public void stop() {
    }
}
