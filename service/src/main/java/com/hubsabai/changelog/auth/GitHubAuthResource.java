package com.hubsabai.changelog.auth;

import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.ForbiddenException;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.NewCookie;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.net.URI;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * GitHub OAuth entry points: {@code /auth/github/authorize}, {@code /auth/github/callback} plus the
 * session endpoints {@code /auth/me}, {@code /auth/logout} and {@code DELETE /auth/github/account}.
 *
 * <p>Signing in makes GitHub API calls (repo reads, PR/commit fetches, changelog pushes) run as the
 * <em>logged-in user's</em> account instead of the service account's — see
 * {@code GitHubOrgAuthFilter}. Deleting the account erases only the user's credentials (their row
 * and their sessions cascade); generated changelogs/history in the other tables are untouched.
 */
@Path("/auth")
@Produces(MediaType.APPLICATION_JSON)
public class GitHubAuthResource {

    /** Binds the {@code next} return path to the CSRF state — set at authorize, cleared at callback. */
    private static final String STATE_COOKIE = "cc_oauth";
    private static final int STATE_TTL_SECONDS = 600;
    private static final String DEFAULT_NEXT = "/dev";

    @Inject
    GitHubOAuthService oauth;

    @Inject
    SessionService sessions;

    @Inject
    Crypto crypto;

    @Inject
    CurrentUser currentUser;

    @Inject
    @ConfigProperty(name = "github.oauth.app-base", defaultValue = "")
    String appBase;

    @Inject
    @ConfigProperty(name = "auth.cookie.secure", defaultValue = "true")
    boolean secureCookies;

    /** Starts the authorization-code dance. {@code next} is the frontend route to land on after the
     * OAuth round-trip (must be a same-site relative path — see {@link #sanitizeNext}). */
    @GET
    @Path("/github/authorize")
    public Response authorize(@QueryParam("next") String next) {
        ensureEnabled();
        String maybeNext = next != null && !next.isBlank() ? next : DEFAULT_NEXT;
        String cleanNext = sanitizeNext(maybeNext);
        String nonce = crypto.randomToken(24);
        String state = nonce + ":" + cleanNext;
        return Response.status(Response.Status.FOUND)
                .location(URI.create(oauth.authorizeUrl(state)))
                .cookie(stateCookie(nonce, STATE_TTL_SECONDS))
                .build();
    }

    /** GitHub bounces back here with {@code code} + the {@code state} we handed out. Exchanges the
     * code, upserts the user (credentials encrypted at rest), opens a session cookie, and redirects
     * the browser back to {@code appBase/#/user}. */
    @GET
    @Path("/github/callback")
    @Transactional
    public Response callback(
            @Context HttpHeaders headers,
            @QueryParam("code") String code,
            @QueryParam("state") String state,
            @QueryParam("error") String error) {
        ensureEnabled();
        if (error != null) {
            return redirect(loginWithError("access_denied"));
        }
        if (code == null || state == null) {
            return redirect(loginWithError("invalid_request"));
        }

        String stateNonce = SessionService.readCookie(headers, STATE_COOKIE).orElse(null);
        if (stateNonce == null || !state.startsWith(stateNonce + ":")) {
            return redirect(loginWithError("invalid_state"));
        }
        String next = sanitizeNext(state.substring(state.indexOf(':') + 1));

        try {
            GitHubOAuthService.TokenResponse tokens = oauth.exchangeCode(code);
            GitHubOAuthService.GithubUserInfo info = oauth.fetchUser(tokens.accessToken());
            GithubUser user = upsertUser(info, tokens);
            var session = sessions.create(user.id);
            return Response.status(Response.Status.FOUND)
                    .location(URI.create(appBase() + "/#" + next))
                    .cookie(sessions.sessionCookie(session.token(), session.maxAgeSeconds(), secureCookies),
                            clearStateCookie())
                    .build();
        } catch (RuntimeException e) {
            return redirect(loginWithError("oauth_failed"));
        }
    }

    /** The signed-in user's identity, or 401. The login page and gates drive off this. */
    @GET
    @Path("/me")
    public Response me() {
        return currentUser.get()
                .map(u -> (Response) Response.ok(mePayload(u)).build())
                .orElseGet(() -> Response.status(Response.Status.UNAUTHORIZED)
                        .entity(Map.of("error", "not_authenticated"))
                        .build());
    }

    /** Ends the session — removes the digest row; the cookie becomes meaningless. */
    @POST
    @Path("/logout")
    public Response logout(@Context HttpHeaders headers) {
        SessionService.readCookie(headers, SessionService.COOKIE_NAME)
                .ifPresent(sessions::delete);
        return Response.noContent().cookie(sessions.clearSessionCookie(secureCookies)).build();
    }

    /** Deletes the user's credentials (db row + all sessions cascade) and revokes the GitHub token
     * server-side. Generated changelogs/history are NOT touched. */
    @DELETE
    @Path("/github/account")
    @Transactional
    public Response deleteAccount() {
        GithubUser user = currentUser.get().orElse(null);
        if (user == null) {
            return Response.status(Response.Status.UNAUTHORIZED)
                    .entity(Map.of("error", "not_authenticated"))
                    .build();
        }
        try {
            oauth.revokeAccessTokenBestEffort(crypto.decrypt(user.accessToken));
        } catch (RuntimeException ignored) {
            // Revocation is best-effort — never let a transient failure block the deletion.
        }
        sessions.deleteAllForUser(user.id);
        user.delete();
        currentUser.clear();
        return Response.noContent()
                .cookie(sessions.clearSessionCookie(secureCookies), clearStateCookie())
                .build();
    }

    // --- internals ---

    private GithubUser upsertUser(GitHubOAuthService.GithubUserInfo info,
                                  GitHubOAuthService.TokenResponse tokens) {
        GithubUser user = GithubUser.findByGithubId(info.id());
        byte[] accessToken = crypto.encrypt(tokens.accessToken());
        byte[] refreshToken = tokens.refreshToken() != null ? crypto.encrypt(tokens.refreshToken()) : null;
        if (user == null) {
            user = new GithubUser();
            user.githubId = info.id();
            user.createdAt = OffsetDateTime.now();
        }
        user.login = info.login();
        user.avatarUrl = info.avatarUrl();
        user.accessToken = accessToken;
        user.refreshToken = refreshToken;
        user.tokenExpiresAt = tokens.expiresAt();
        user.updatedAt = OffsetDateTime.now();
        user.persist();
        return user;
    }

    private void ensureEnabled() {
        if (!oauth.enabled()) {
            throw new ForbiddenException(Response.status(Response.Status.SERVICE_UNAVAILABLE)
                    .entity(Map.of("error", "GitHub sign-in is not configured yet."))
                    .type(MediaType.APPLICATION_JSON)
                    .build());
        }
    }

    private NewCookie stateCookie(String nonce, int maxAgeSeconds) {
        return cookie(STATE_COOKIE, nonce, maxAgeSeconds);
    }

    private NewCookie clearStateCookie() {
        return cookie(STATE_COOKIE, "", 0);
    }

    private NewCookie cookie(String name, String value, int maxAgeSeconds) {
        return new NewCookie(name, value, "/", null, 1, null, maxAgeSeconds, null, secureCookies, true, NewCookie.SameSite.LAX);
    }

    private Map<String, Object> mePayload(GithubUser u) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("id", u.githubId);
        payload.put("login", u.login);
        payload.put("avatarUrl", u.avatarUrl);
        return payload;
    }

    private String loginWithError(String slug) {
        return appBase() + "/#/login?error=" + slug;
    }

    private String appBase() {
        return appBase == null || appBase.isBlank() ? "" : appBase;
    }

    /** Relative, single-slash, no-scheme return paths only — prevents open-redirect via {@code next}. */
    private static String sanitizeNext(String next) {
        if (next == null || next.isBlank()) return DEFAULT_NEXT;
        if (!next.startsWith("/") || next.startsWith("//")) return DEFAULT_NEXT;
        if (next.contains(":") || next.contains("#")) return DEFAULT_NEXT;
        return next;
    }

    private Response redirect(String location) {
        return Response.status(Response.Status.FOUND).location(URI.create(location)).build();
    }
}