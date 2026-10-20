package com.hubsabai.changelog.api;

import com.hubsabai.changelog.auth.TestSessionFixture;
import com.hubsabai.changelog.connector.github.WireMockGitHubOrgResource;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.specification.RequestSpecification;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.putRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;

@QuarkusTest
@QuarkusTestResource(WireMockGitHubOrgResource.class)
class GitHubResourceTest {

    @Inject
    TestSessionFixture sessions;

    private String session;

    @BeforeEach
    void setUp() {
        session = sessions.signIn();
        WireMockGitHubOrgResource.server().resetAll();
    }

    private RequestSpecification authenticated() {
        return given().cookie("cc_session", session);
    }

    @Test
    void pushReportsAlreadyUpToDateWithoutCreatingACommit() {
        String markdown = """
                # Releases

                ## v1.1.0 — 2026-07-01

                - fixed the thing
                - added a feature
                """;
        String encoded = Base64.getEncoder().encodeToString(markdown.getBytes(StandardCharsets.UTF_8));
        WireMockGitHubOrgResource.server().stubFor(get(urlPathEqualTo("/repos/test-owner/repo-noop/contents/CHANGELOG.md"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"content\":\"" + encoded + "\",\"encoding\":\"base64\"}")));
        WireMockGitHubOrgResource.server().stubFor(get(urlPathEqualTo("/repos/test-owner/repo-noop/commits"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                                [{"sha":"base123","commit":{"author":{"name":"a","date":"2026-07-01T00:00:00Z"},
                                  "committer":{"name":"a","date":"2026-07-01T00:00:00Z"},"message":"base"},"html_url":"u"}]""")));
        WireMockGitHubOrgResource.server().stubFor(get(urlPathEqualTo("/repos/test-owner/repo-noop/commits/base123"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                                {"sha":"base123","commit":{"author":{"name":"a","date":"2026-07-01T00:00:00Z"},
                                  "committer":{"name":"a","date":"2026-07-01T00:00:00Z"},"message":"base",
                                  "tree":{"sha":"treeparent"}},"tree":{"sha":"treeparent"},"html_url":"u"}""")));

        authenticated()
                .contentType("application/json")
                .body("""
                        {"version":"v1.1.0","branch":"main","audience":"developer",
                         "text":"- fixed the thing\\n- added a feature"}
                        """)
                .when().post("/api/github/projects/test-owner/repos/repo-noop/changelog-push")
                .then()
                .statusCode(200)
                .body("alreadyUpToDate", is(true))
                .body("commitUrl", nullValue());

        WireMockGitHubOrgResource.server().verify(0, postRequestedFor(urlPathEqualTo("/repos/test-owner/repo-noop/git/blobs")));
        WireMockGitHubOrgResource.server().verify(0, postRequestedFor(urlPathEqualTo("/repos/test-owner/repo-noop/git/trees")));
        WireMockGitHubOrgResource.server().verify(0, postRequestedFor(urlPathEqualTo("/repos/test-owner/repo-noop/git/commits")));
        WireMockGitHubOrgResource.server().verify(0, putRequestedFor(urlPathEqualTo("/repos/test-owner/repo-noop/git/refs/heads%2Fmain")));
    }
}
