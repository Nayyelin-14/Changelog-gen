package com.hubsabai.changelog.auth;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.client.Client;
import jakarta.ws.rs.client.ClientBuilder;
import jakarta.ws.rs.client.Entity;
import jakarta.ws.rs.core.Form;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.Base64;
import java.util.logging.Logger;

/**
 * Talks to GitHub's OAuth endpoints for the authorization-code flow.
 *
 * <p>OAuth token exchange happens directly against {@code github.com/login/oauth} (NOT through the
 * {@code GitHubOrgRestClient}, which would stamp the user's token on these calls). The exchange
 * carries the app's {@code client_secret}, which is stored server-side and never leaves the VM.
 *
 * <p>Tokens are returned to the browser only inside the HttpOnly session cookie; this service hands
 * the raw tokens to {@link SessionService} immediately for encryption at rest.
 */
@ApplicationScoped
public class GitHubOAuthService {

    private static final Logger LOG = Logger.getLogger(GitHubOAuthService.class.getName());
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final String clientId;
    private final String clientSecret;
    private final String redirectUri;
    private final String githubBaseUrl;
    private final String apiBaseUrl;
    private final Client client = ClientBuilder.newClient();

    public GitHubOAuthService(
            @ConfigProperty(name = "github.oauth.client-id", defaultValue = "") String clientId,
            @ConfigProperty(name = "github.oauth.client-secret", defaultValue = "") String clientSecret,
            @ConfigProperty(name = "github.oauth.redirect-uri", defaultValue = "") String redirectUri,
            @ConfigProperty(name = "github.oauth.github-base-url", defaultValue = "https://github.com") String githubBaseUrl,
            @ConfigProperty(name = "github.oauth.api-base-url", defaultValue = "https://api.github.com") String apiBaseUrl) {
        this.clientId = clientId;
        this.clientSecret = clientSecret;
        this.redirectUri = redirectUri;
        this.githubBaseUrl = stripTrailingSlash(githubBaseUrl);
        this.apiBaseUrl = stripTrailingSlash(apiBaseUrl);
    }

    /** Sign-in is only offered once the operator registered an OAuth App AND set a session secret. */
    public boolean enabled() {
        return clientId != null && !clientId.isBlank()
                && clientSecret != null && !clientSecret.isBlank()
                && redirectUri != null && !redirectUri.isBlank();
    }

    public String authorizeUrl(String state) {
        return authorizeBase()
                + "?client_id=" + enc(clientId)
                + "&redirect_uri=" + enc(redirectUri)
                + "&scope=repo"
                + "&state=" + enc(state);
    }

    /** Exchanges an authorization code for tokens. {@code expiresAt} is null for non-expiring
     * (classic OAuth App) tokens — the default unless the app opted into expiration. */
    public TokenResponse exchangeCode(String code) {
        Form form = new Form()
                .param("client_id", clientId)
                .param("client_secret", clientSecret)
                .param("code", code)
                .param("redirect_uri", redirectUri);
        return parseTokenResponse(postToken(form));
    }

    /** Refreshes an expired access token. */
    public TokenResponse refresh(String refreshToken) {
        Form form = new Form()
                .param("client_id", clientId)
                .param("client_secret", clientSecret)
                .param("grant_type", "refresh_token")
                .param("refresh_token", refreshToken);
        return parseTokenResponse(postToken(form));
    }

    public record TokenResponse(String accessToken, String refreshToken, OffsetDateTime expiresAt, String scope) {}

    /** Best-effort revoke on GitHub's side, so the authorization disappears from the user's own
     * GitHub settings too. GitHub's revocation endpoint authenticates as the OAuth App
     * (Basic client_id:client_secret) and names the access token to kill. */
    public void revokeAccessTokenBestEffort(String accessToken) {
        try {
            Response resp = client.target(apiBaseUrl + "/applications/" + clientId + "/token")
                    .queryParam("access_token", accessToken)
                    .request(MediaType.APPLICATION_JSON_TYPE)
                    .header("Authorization", "Basic " + basicAuth(clientId, clientSecret))
                    .accept(MediaType.WILDCARD)
                    .delete();
            if (resp.getStatus() >= 300) {
                LOG.warning("GitHub token revocation returned HTTP " + resp.getStatus());
            }
            resp.close();
        } catch (Exception e) {
            // Revocation is hygiene, not correctness — a failed call never blocks account deletion.
            LOG.warning("GitHub token revocation failed (best-effort): " + e.getMessage());
        }
    }

    /** The acting GitHub identity for a user token — used at sign-in to create/locate the user. */
    public GithubUserInfo fetchUser(String accessToken) {
        try (Response resp = client.target(apiBaseUrl + "/user")
                .request(MediaType.APPLICATION_JSON)
                .header("Authorization", "Bearer " + accessToken)
                .get()) {
            if (resp.getStatus() != 200) {
                throw new IllegalStateException("GitHub /user returned HTTP " + resp.getStatus());
            }
            JsonNode node = MAPPER.readTree(resp.readEntity(String.class));
            return new GithubUserInfo(
                    node.path("id").asLong(),
                    node.path("login").asText(null),
                    node.path("avatar_url").asText(null));
        } catch (java.io.IOException e) {
            throw new IllegalStateException("Failed to read GitHub /user response", e);
        }
    }

    public record GithubUserInfo(long id, String login, String avatarUrl) {}

    // --- internals ---

    private String postToken(Form form) {
        try (Response resp = client.target(tokenUrl())
                .request(MediaType.APPLICATION_JSON_TYPE)
                .accept(MediaType.APPLICATION_JSON)
                .post(Entity.form(form))) {
            if (resp.getStatus() != 200) {
                throw new IllegalStateException("GitHub token exchange returned HTTP " + resp.getStatus());
            }
            return resp.readEntity(String.class);
        }
    }

    private TokenResponse parseTokenResponse(String json) {
        try {
            JsonNode node = MAPPER.readTree(json);
            String accessToken = node.path("access_token").asText(null);
            if (accessToken == null || accessToken.isBlank()) {
                throw new IllegalStateException("GitHub token exchange did not return an access_token: " + json);
            }
            String refreshToken = node.path("refresh_token").asText(null);
            long expiresIn = node.path("expires_in").asLong(-1);
            OffsetDateTime expiresAt = expiresIn > 0 ? OffsetDateTime.now().plusSeconds(expiresIn) : null;
            return new TokenResponse(accessToken, refreshToken, expiresAt, node.path("scope").asText(null));
        } catch (java.io.IOException e) {
            throw new IllegalStateException("Failed to parse GitHub token response", e);
        }
    }

    private static String enc(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private static String stripTrailingSlash(String value) {
        return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
    }

    private String authorizeBase() {
        return githubBaseUrl + "/login/oauth/authorize";
    }

    private String tokenUrl() {
        return githubBaseUrl + "/login/oauth/access_token";
    }

    private static String basicAuth(String clientId, String clientSecret) {
        return Base64.getEncoder().encodeToString((clientId + ":" + clientSecret).getBytes(StandardCharsets.UTF_8));
    }
}