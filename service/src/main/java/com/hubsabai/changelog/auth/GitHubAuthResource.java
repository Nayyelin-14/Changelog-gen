package com.hubsabai.changelog.auth;

import com.hubsabai.changelog.api.ApiErrors;
import com.hubsabai.changelog.api.DatabaseFailures;
import com.hubsabai.changelog.api.RequestIdFilter;
import com.hubsabai.changelog.storage.OwnedDataCleanupService;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.ForbiddenException;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.container.ContainerRequestContext;
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
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * GitHub OAuth entry points plus the session endpoints {@code /auth/me}, {@code /auth/logout},
 * {@code POST /auth/github/disconnect} and {@code DELETE /auth/github/account}.
 *
 * <p>Signing in makes GitHub API calls (repo reads, PR/commit fetches, changelog pushes) run as the
 * <em>logged-in user's</em> account instead of the service account's — see
 * {@code GitHubOrgAuthFilter}.
 *
 * <p>Two distinct "stop using GitHub" actions, because they have very different consequences:
 * <ul>
 *   <li><b>Disconnect</b> ({@code POST /auth/github/disconnect}) — revokes the OAuth token with
 *       GitHub and clears it from the database. The account (and therefore every changelog the user
 *       created, which is keyed to the account's immutable id) is untouched, and the session stays
 *       alive so the user isn't bounced out of their own work. Signing in again with the same
 *       GitHub account lands on the same account, so nothing is lost in between.</li>
 *   <li><b>Delete account</b> ({@code DELETE /auth/github/account}) — revokes the token, ends every
 *       session, and removes the account <em>and</em> every row it owns.</li>
 * </ul>
 *
 * <p>Disconnect keeps the {@link GithubUser} row rather than deleting it on purpose. The row is what
 * the user's changelogs point at; deleting it would leave them owned by a user that no longer
 * exists, and would make a later sign-in mint a brand-new account with none of their history. The
 * credentials it existed to hold are gone either way.
 */
@Path("/auth")
@Produces(MediaType.APPLICATION_JSON)
public class GitHubAuthResource {

    private static final Logger LOG = Logger.getLogger(GitHubAuthResource.class.getName());

    /** Binds the {@code next} return path (and connect mode) to the CSRF state — set at authorize,
     * cleared at callback. */
    private static final String STATE_COOKIE = "cc_oauth";
    private static final int STATE_TTL_SECONDS = 600;
    private static final String DEFAULT_NEXT = "/dev";
    private static final String MODE_CONNECT = "connect";

    @Inject
    GitHubOAuthService oauth;

    @Inject
    SessionService sessions;

    @Inject
    Crypto crypto;

    @Inject
    CurrentUser currentUser;

    @Inject
    OwnedDataCleanupService ownedData;

    @Inject
    @ConfigProperty(name = "github.oauth.app-base", defaultValue = "")
    String configuredAppBase;

    @Inject
    @ConfigProperty(name = "quarkus.profile", defaultValue = "prod")
    String profile;

    @Inject
    @ConfigProperty(name = "auth.cookie.secure", defaultValue = "true")
    boolean configuredSecureCookies;

    @Context
    ContainerRequestContext requestContext;

    /** In dev profile, never set Secure flag — dev runs over plain HTTP. */
    private boolean secureCookies() {
        return profile.contains("dev") ? false : configuredSecureCookies;
    }

    /** Starts the authorization-code dance. {@code next} is the frontend route to land on after the
     * OAuth round-trip (must be a same-site relative path — see {@link #sanitizeNext}).
     *
     * <p>{@code mode=connect} means "attach the GitHub account I'm about to authorize to the app
     * account I'm already signed in as" instead of "sign me in as whoever this is". Without it a
     * signed-in user who re-ran the flow would silently be signed in as a different account. */
    @GET
    @Path("/github/authorize")
    public Response authorize(@QueryParam("next") String next, @QueryParam("mode") String mode) {
        if (!oauth.enabled()) {
            return redirect(loginWithError("service_unavailable"));
        }
        String maybeNext = next != null && !next.isBlank() ? next : DEFAULT_NEXT;
        String cleanNext = sanitizeNext(maybeNext);
        boolean connect = MODE_CONNECT.equalsIgnoreCase(mode);
        String nonce = crypto.randomToken(24);
        String state = nonce + ":" + cleanNext + (connect ? ":" + MODE_CONNECT : "");
        return Response.status(Response.Status.FOUND)
                .location(URI.create(oauth.authorizeUrl(state)))
                .cookie(stateCookie(nonce, STATE_TTL_SECONDS))
                .build();
    }

    /** GitHub bounces back here with {@code code} + the {@code state} we handed out. Exchanges the
     * code, upserts the user (credentials encrypted at rest), opens a session cookie, and redirects
     * the browser back to {@code appBase/#/next}. */
    @GET
    @Path("/github/callback")
    @Transactional
    public Response callback(
            @Context HttpHeaders headers,
            @QueryParam("code") String code,
            @QueryParam("state") String state,
            @QueryParam("error") String error) {
        if (!oauth.enabled()) {
            return redirect(loginWithError("service_unavailable"));
        }
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
        String rest = state.substring(state.indexOf(':') + 1);
        int modeAt = rest.indexOf(':');
        String next = sanitizeNext(modeAt < 0 ? rest : rest.substring(0, modeAt));
        boolean connect = modeAt >= 0 && MODE_CONNECT.equals(rest.substring(modeAt + 1));

        try {
            GitHubOAuthService.TokenResponse tokens = oauth.exchangeCode(code);
            GitHubOAuthService.GithubUserInfo info = oauth.fetchUser(tokens.accessToken());
            if (info.id() <= 0 || info.login() == null || info.login().isBlank()) {
                // GitHub must tell us who this is; without a stable id we cannot key anything to them.
                return redirect(loginWithError("oauth_failed"));
            }
            if (connect) {
                return connectExistingSession(info, tokens, next);
            }
            GithubUser user = upsertUser(info, tokens);
            var session = sessions.create(user.id);
            return Response.status(Response.Status.FOUND)
                    .location(URI.create(appBase() + "/#" + next))
                    .cookie(sessions.sessionCookie(session.token(), session.maxAgeSeconds(), secureCookies()),
                            clearStateCookie())
                    .build();
        } catch (RuntimeException e) {
            String requestId = RequestIdFilter.current();
            if (DatabaseFailures.isUnavailable(e)) {
                LOG.log(Level.WARNING, "GitHub callback failed: database unavailable [requestId="
                        + requestId + "]", e);
                return redirect(loginWithError("service_unavailable"));
            }
            LOG.log(Level.WARNING, "GitHub callback failed [requestId=" + requestId + "]", e);
            return redirect(loginWithError("oauth_failed"));
        }
    }

    /** The signed-in user's identity, or 401. The login page and gates drive off this. */
    @GET
    @Path("/me")
    public Response me() {
        if (requestContext != null
                && Boolean.TRUE.equals(requestContext.getProperty(AuthSessionFilter.AUTH_UNAVAILABLE))) {
            return ApiErrors.serviceUnavailable();
        }
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
        return Response.noContent().cookie(sessions.clearSessionCookie(secureCookies())).build();
    }

    /**
     * Disconnects GitHub: revokes the OAuth token with GitHub and erases it from the database.
     * The account and every changelog it owns are deliberately left alone — see the class javadoc.
     * The session stays valid, so the user remains signed in (just without GitHub capabilities).
     */
    @POST
    @Path("/github/disconnect")
    @Transactional
    public Response disconnect() {
        GithubUser user = attached(requireUser());
        revokeQuietly(user);
        user.accessToken = null;
        user.refreshToken = null;
        user.tokenExpiresAt = null;
        user.updatedAt = OffsetDateTime.now();
        user.persist();
        return Response.noContent().build();
    }

    /**
     * Deletes the account: revokes the GitHub token, ends every session, and removes the account
     * row plus every row it owns. Service/CI-owned rows are never touched.
     */
    @DELETE
    @Path("/github/account")
    @Transactional
    public Response deleteAccount() {
        GithubUser user = attached(requireUser());
        revokeQuietly(user);
        sessions.deleteAllForUser(user.id);
        ownedData.deleteAllOwnedBy(user.id);
        user.delete();
        currentUser.clear();
        return Response.noContent()
                .cookie(sessions.clearSessionCookie(secureCookies()), clearStateCookie())
                .build();
    }

    // --- internals ---

    /** {@code mode=connect}: move the app account I'm already signed in as onto a new GitHub
     * identity, refusing if that identity already belongs to a different app account. */
    private Response connectExistingSession(GitHubOAuthService.GithubUserInfo info,
                                            GitHubOAuthService.TokenResponse tokens,
                                            String next) {
        GithubUser signedIn = currentUser.get().orElse(null);
        if (signedIn == null) {
            return redirect(loginWithError("not_authenticated"));
        }
        // The session resolver loaded this row in its own transaction; re-load it here so the
        // update below is on an attached entity rather than a detached one.
        GithubUser current = attached(signedIn);
        GithubUser ownerOfIdentity = GithubUser.findByGithubId(info.id());
        if (ownerOfIdentity != null && !ownerOfIdentity.id.equals(current.id)) {
            // That GitHub account is already a different account here. Merging the two would move
            // one person's changelogs under the other's id — refuse and make them sign in instead.
            return redirect(loginWithError("github_linked_elsewhere"));
        }
        current.githubId = info.id();
        current.login = info.login();
        current.avatarUrl = info.avatarUrl();
        current.accessToken = crypto.encrypt(tokens.accessToken());
        current.refreshToken = tokens.refreshToken() != null ? crypto.encrypt(tokens.refreshToken()) : null;
        current.tokenExpiresAt = tokens.expiresAt();
        current.updatedAt = OffsetDateTime.now();
        current.persist();
        // Keep the existing session — the app account did not change, only its GitHub identity did.
        return Response.status(Response.Status.FOUND)
                .location(URI.create(appBase() + "/#" + next))
                .cookie(clearStateCookie())
                .build();
    }

    private GithubUser upsertUser(GitHubOAuthService.GithubUserInfo info,
                                  GitHubOAuthService.TokenResponse tokens) {
        // Keyed on github_id — the one GitHub identifier that survives a username change. `login` is
        // a display snapshot, refreshed here on every sign-in so a rename is picked up immediately
        // and never used to look anyone up.
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

    /** Re-loads {@code user} in the current transaction — {@link CurrentUser} holds a row loaded
     * by the session filter's own (already completed) transaction. */
    private static GithubUser attached(GithubUser user) {
        GithubUser reloaded = GithubUser.findById(user.id);
        return reloaded != null ? reloaded : user;
    }

    private GithubUser requireUser() {
        GithubUser user = currentUser.get().orElse(null);
        if (user == null) {
            throw new ForbiddenException(Response.status(Response.Status.UNAUTHORIZED)
                    .type(MediaType.APPLICATION_JSON)
                    .entity(Map.of("error", "not_authenticated"))
                    .build());
        }
        return user;
    }

    /** Best-effort: a GitHub-side revocation failure must never block the local action. */
    private void revokeQuietly(GithubUser user) {
        if (user.accessToken == null) {
            return;
        }
        try {
            oauth.revokeAccessTokenBestEffort(crypto.decrypt(user.accessToken));
        } catch (RuntimeException ignored) {
            // Best-effort by design.
        }
    }

    private NewCookie stateCookie(String nonce, int maxAgeSeconds) {
        return cookie(STATE_COOKIE, nonce, maxAgeSeconds);
    }

    private NewCookie clearStateCookie() {
        return cookie(STATE_COOKIE, "", 0);
    }

    private NewCookie cookie(String name, String value, int maxAgeSeconds) {
        return new NewCookie(name, value, "/", null, 1, null, maxAgeSeconds, null, secureCookies(), true, NewCookie.SameSite.LAX);
    }

    private Map<String, Object> mePayload(GithubUser u) {
        Map<String, Object> payload = new LinkedHashMap<>();
        // The internal id is what the UI should key anything user-scoped on; the GitHub id is
        // exposed separately because it is an external identifier, not the ownership key.
        payload.put("id", u.id);
        payload.put("githubId", u.githubId);
        payload.put("login", u.login);
        payload.put("avatarUrl", u.avatarUrl);
        payload.put("githubConnected", u.accessToken != null);
        return payload;
    }

    private String loginWithError(String slug) {
        return appBase() + "/#/login?error=" + slug;
    }

    private String appBase() {
        if (profile.contains("dev")) {
            return "http://localhost:5173";
        }
        return configuredAppBase == null || configuredAppBase.isBlank() ? "" : configuredAppBase;
    }

    /** Relative, single-slash, no-scheme return paths only — prevents open-redirect via {@code next}. */
    private static String sanitizeNext(String next) {
        if (next == null || next.isBlank()) return DEFAULT_NEXT;
        if (!next.startsWith("/") || next.startsWith("//")) return DEFAULT_NEXT;
        if (next.contains(":") || next.contains("#")) return DEFAULT_NEXT;
        return next;
    }

    private Response redirect(String location) {
        // Always drop the one-shot state cookie, on every failure path too: leaving it set would
        // let a later callback reuse a stale nonce, and it holds no value once the flow is over.
        return Response.status(Response.Status.FOUND)
                .location(URI.create(location))
                .cookie(clearStateCookie())
                .build();
    }
}
