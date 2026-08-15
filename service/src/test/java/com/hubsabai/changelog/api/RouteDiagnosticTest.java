package com.hubsabai.changelog.api;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.basePath;
import static io.restassured.RestAssured.baseURI;
import static io.restassured.RestAssured.given;
import static io.restassured.RestAssured.port;

@QuarkusTest
class RouteDiagnosticTest {

    @Test
    void dumpRouting() {
        System.out.println("DIAG baseURI=" + baseURI + " port=" + port + " basePath=[" + basePath + "]");
        System.out.println("DIAG ---- GET /q/openapi ----");
        given().when().get("/q/openapi").then().log().all();
        System.out.println("DIAG ---- GET /api/ai/models ----");
        given().when().get("/api/ai/models").then().log().all();
        System.out.println("DIAG ---- GET /ai/models ----");
        given().when().get("/ai/models").then().log().all();
        System.out.println("DIAG ---- GET /q/health ----");
        given().when().get("/q/health").then().log().all();
    }
}