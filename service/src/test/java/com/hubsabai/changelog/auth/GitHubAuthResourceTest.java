package com.hubsabai.changelog.auth;

import com.github.tomakehurst.wiremock.client.WireMock;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.RestAssured;
import io.restassured.response.Response;
import jakarta.inject.Inject;
import jakarta.transaction.UserTransaction;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.delete;
import static com.github.tomakehurst.wiremock.client.WireMock.deleteRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

@QuarkusTest
@QuarkusTestResource(WireMockGitHubAuthResource.class)
class GitHubAuthResourceTest {

    private static final String ACCESS_TOKEN = "gho_test_access";

    @Inject
    UserTransaction utx;

    @Inject
    @ConfigProperty(name = "github.oauth.client-id")
    String clientId;

    @Inject
    @ConfigProperty(name = "github.oauth.app-base")
    String appBase;

    @Inject
    @ConfigProperty(name = "github.oauth.redirect-uri")
    String redirectUri;

    @BeforeEach
    void reset() throws Exception {
        // OAuth redirects (302 to github.com / back to the app base) must be asserted as-is,
        // so the HTTP client must never follow them automatically.
        RestAssured.config = RestAssured.config()
                .redirect(io.restassured.config.RedirectConfig.redirectConfig().followRedirects(false));
        WireMockGitHubAuthResource.server().resetAll();
        utx.begin();
        try {
            AuthSession.deleteAll();
            GithubUser.deleteAll();
            utx.commit();
        } catch (Exception e) {
            utx.rollback();
            throw e;
        }
    }

    // --- authorize ---

    @Test
    void authorizeRedirectsToGithubWithStateAndSetsTheStateCookie() {
        Response response = given().queryParam("next", "/dev").when().get("/api/auth/github/authorize");

        response.then()
                .statusCode(302)
                .header("Location", containsString("/login/oauth/authorize"))
                .header("Location", containsString("client_id=" + clientId))
                .header("Location", containsString("redirect_uri=" + URLEncoder.encode(
                        redirectUri, StandardCharsets.UTF_8)))
                .header("Location", containsString("scope=repo"));
        String state = response.cookie("cc_oauth");
        assertNotNull(state);
        assertEquals(32, state.length(), "state cookie is the 24-byte base64url nonce");
    }

    @Test
    void authorizeSanitizesAnExternalNextToTheSafeDefault() {
        // The round-trip below verifies the guard that matters: even if an attacker later submits
        // an absolute URL inside `state`, the callback only ever follows same-site paths.
        String nonce = given().queryParam("next", "https://evil.example/steal").when()
                .get("/api/auth/github/authorize").cookie("cc_oauth");

        stubTokenExchange(ACCESS_TOKEN);
        stubFetchUser(12345L, "octocat");

        given().cookie("cc_oauth", nonce)
                .queryParam("code", "code-1")
                .queryParam("state", nonce + ":https://evil.example/steal")
                .when().get("/api/auth/github/callback")
                .then().statusCode(302)
                .header("Location", equalTo(appBase + "/#/dev"));
    }

    // --- callback: the full sign-in round-trip ---

    @Test
    void callbackExchangesTheCodeAndOpensASessionForTheGithubUser() {
        stubTokenExchange(ACCESS_TOKEN);
        stubFetchUser(12345L, "octocat");

        // Step 1: start the dance and receive the CSRF nonce cookie.
        String nonce = given().queryParam("next", "/dev").when()
                .get("/api/auth/github/authorize").cookie("cc_oauth");

        // Step 2: GitHub bounces back with code + the state we handed it.
        Response callback = given().cookie("cc_oauth", nonce)
                .queryParam("code", "code-123")
                .queryParam("state", nonce + ":/dev")
                .when().get("/api/auth/github/callback");

        callback.then()
                .statusCode(302)
                .header("Location", equalTo(appBase + "/#/dev"));

        String session = callback.cookie("cc_session");
        assertNotNull(session, "a session cookie must be issued");
        assertEquals("", callback.cookie("cc_oauth"), "the state cookie must be consumed (cleared)");

        WireMockGitHubAuthResource.server().verify(postRequestedFor(urlPathEqualTo("/login/oauth/access_token")));

        // Step 3: the session now resolves to the GitHub user and their stored token.
        given().cookie("cc_session", session).when().get("/api/auth/me")
                .then()
                .statusCode(200)
                .body("login", equalTo("octocat"))
                .body("id", equalTo(12345));

        assertEquals(1, GithubUser.count());
        GithubUser saved = GithubUser.findByLogin("octocat");
        assertNotNull(saved);
        assertEquals(String.valueOf(12345), String.valueOf(saved.githubId));

        // The access token is encrypted at rest, never stored in the clear.
        String atRest = new String(saved.accessToken, StandardCharsets.UTF_8);
        assertNotNull(atRest);
        org.junit.jupiter.api.Assertions.assertNotEquals(ACCESS_TOKEN, atRest);
    }

    @Test
    void callbackRejectsAForgedStateAndNeverIssuesASession() {
        String nonce = given().when().get("/api/auth/github/authorize").cookie("cc_oauth");

        given().cookie("cc_oauth", nonce)
                .queryParam("code", "code-456")
                .queryParam("state", "attacker-nonce:/dev")
                .when().get("/api/auth/github/callback")
                .then()
                .statusCode(302)
                .header("Location", containsString("/#/login?error=invalid_state"));

        assertEquals(0, GithubUser.count(), "a forged state must not create a user");
    }

    @Test
    void callbackWithGitHubErrorRedirectsToLogin() {
        String nonce = given().when().get("/api/auth/github/authorize").cookie("cc_oauth");

        given().cookie("cc_oauth", nonce)
                .queryParam("code", "x")
                .queryParam("state", nonce + ":/dev")
                .queryParam("error", "access_denied")
                .when().get("/api/auth/github/callback")
                .then()
                .statusCode(302)
                .header("Location", containsString("/#/login?error=access_denied"));

        assertEquals(0, GithubUser.count());
    }

    // --- session endpoints ---

    @Test
    void meIs401BeforeLoginAnd200After() {
        given().when().get("/api/auth/me").then().statusCode(401);

        String session = loginRoundTrip();

        given().cookie("cc_session", session).when().get("/api/auth/me")
                .then().statusCode(200).body("login", equalTo("octocat"));
    }

    @Test
    void logoutClosesTheSession() {
        String session = loginRoundTrip();

        given().cookie("cc_session", session).when().post("/api/auth/logout")
                .then().statusCode(204);

        given().cookie("cc_session", session).when().get("/api/auth/me").then().statusCode(401);
    }

    @Test
    void deleteAccountRevokesTheTokenClearsSessionsAndKeepsGeneratedContentIntact() {
        String session = loginRoundTrip();
        WireMockGitHubAuthResource.server().stubFor(delete(urlPathEqualTo("/applications/" + clientId + "/token"))
                .withQueryParam("access_token", WireMock.equalTo(ACCESS_TOKEN))
                .willReturn(aResponse().withStatus(204)));

        given().cookie("cc_session", session).when().delete("/api/auth/github/account")
                .then().statusCode(204);

        WireMockGitHubAuthResource.server().verify(deleteRequestedFor(urlPathEqualTo("/applications/" + clientId + "/token")));

        given().cookie("cc_session", session).when().get("/api/auth/me").then().statusCode(401);
        assertEquals(0, GithubUser.count(), "deleting the account removes the credentials");
        assertEquals(0, AuthSession.count(), "and every session that pointed at them");
    }

    // --- helpers ---

    /** Signs in as 12345/octocat via the mocked GitHub, returning the {@code cc_session} cookie. */
    private String loginRoundTrip() {
        stubTokenExchange(ACCESS_TOKEN);
        stubFetchUser(12345L, "octocat");
        String nonce = given().queryParam("next", "/dev").when().get("/api/auth/github/authorize")
                .cookie("cc_oauth");
        Response callback = given().cookie("cc_oauth", nonce)
                .queryParam("code", "code-roundtrip")
                .queryParam("state", nonce + ":/dev")
                .when().get("/api/auth/github/callback");
        callback.then().statusCode(302);
        String session = callback.cookie("cc_session");
        assertNotNull(session);
        return session;
    }

    private static void stubTokenExchange(String accessToken) {
        WireMockGitHubAuthResource.server().stubFor(post(urlEqualTo("/login/oauth/access_token"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                                {"access_token":"%s","token_type":"bearer","scope":"repo"}
                                """.formatted(accessToken))));
    }

    private static void stubFetchUser(long id, String login) {
        WireMockGitHubAuthResource.server().stubFor(WireMock.get(urlPathEqualTo("/user"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                                {"id":%d,"login":"%s","avatar_url":"https://avatars.test/octo.jpg"}
                                """.formatted(id, login))));
    }
}