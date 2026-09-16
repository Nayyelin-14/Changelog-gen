package com.hubsabai.changelog.auth;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.NewCookie;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.Optional;

/**
 * Server-side session lifecycle. A session is an opaque 256-bit token served to the browser as an
 * HttpOnly/Secure/SameSite=Lax cookie; only its SHA-256 digest is persisted in {@link AuthSession},
 * keyed to a {@link GithubUser}. Sessions live 30 days and slide their {@code last_seen_at} on use.
 *
 * <p>Deleting a session (or a user — cascades) is the only revocation the server needs: the cookie
 * is meaningless once its digest row is gone.
 */
@ApplicationScoped
public class SessionService {

    public static final String COOKIE_NAME = "cc_session";
    private static final Duration SESSION_MAX_AGE = Duration.ofDays(30);
    private static final Duration SLIDING_REFRESH_AFTER = Duration.ofHours(6);
    private static final int SESSION_TOKEN_BYTES = 32;

    @Inject
    Crypto crypto;

    @Inject
    jakarta.persistence.EntityManager entityManager;

    public record ResolvedSession(GithubUser user, String accessToken) {}

    /** Creates a session for a user and returns the plaintext cookie token + its max-age. */
    @Transactional
    public NewSession create(Long userId) {
        String token = crypto.randomToken(SESSION_TOKEN_BYTES);
        var session = new AuthSession();
        session.sessionTokenHash = crypto.sha256Hex(token);
        session.userId = userId;
        session.expiresAt = OffsetDateTime.now().plus(SESSION_MAX_AGE);
        session.persist();
        return new NewSession(token, (int) SESSION_MAX_AGE.toSeconds());
    }

    public record NewSession(String token, int maxAgeSeconds) {}

    /** Loads a session from the raw cookie value, sliding expiry; empty when invalid/expired. */
    @Transactional
    public Optional<ResolvedSession> resolve(String cookieValue) {
        if (cookieValue == null || cookieValue.isBlank()) {
            return Optional.empty();
        }
        AuthSession session = AuthSession.findByTokenHash(crypto.sha256Hex(cookieValue));
        if (session == null) {
            return Optional.empty();
        }
        if (session.expiresAt != null && session.expiresAt.isBefore(OffsetDateTime.now())) {
            session.delete();
            return Optional.empty();
        }
        touch(session);
        GithubUser user = GithubUser.findById(session.userId);
        if (user == null) {
            session.delete();
            return Optional.empty();
        }
        return Optional.of(new ResolvedSession(user, crypto.decrypt(user.accessToken)));
    }

    /** Best-effort sliding expiry — a bulk update so the request thread isn't held open flushing. */
    private void touch(AuthSession session) {
        if (session.lastSeenAt != null
                && session.lastSeenAt.plus(SLIDING_REFRESH_AFTER).isAfter(OffsetDateTime.now())) {
            return;
        }
        entityManager.createQuery(
                        "update AuthSession set lastSeenAt = :now where id = :id")
                .setParameter("now", OffsetDateTime.now())
                .setParameter("id", session.id)
                .executeUpdate();
    }

    @Transactional
    public void delete(String cookieValue) {
        if (cookieValue == null || cookieValue.isBlank()) {
            return;
        }
        AuthSession session = AuthSession.findByTokenHash(crypto.sha256Hex(cookieValue));
        if (session != null) {
            session.delete();
        }
    }

    @Transactional
    public void deleteAllForUser(Long userId) {
        AuthSession.deleteByUserId(userId);
    }

    /** The session cookie for a freshly created session. */
    public NewCookie sessionCookie(String token, int maxAgeSeconds, boolean secure) {
        return cookie(COOKIE_NAME, token, maxAgeSeconds, secure);
    }

    /** Clears the browser's session cookie (logout / account deletion). */
    public NewCookie clearSessionCookie(boolean secure) {
        return cookie(COOKIE_NAME, "", 0, secure);
    }

    /** HttpOnly + Secure + SameSite=Lax keeps the session token out of script and CSRF-plaintext. */
    private static NewCookie cookie(String name, String value, int maxAgeSeconds, boolean secure) {
        return new NewCookie(name, value, "/", null, 1, null, maxAgeSeconds, null, secure, true, NewCookie.SameSite.LAX);
    }

    /** Raw cookie value for {@code name}, if present. */
    public static Optional<String> readCookie(HttpHeaders headers, String name) {
        if (headers == null) {
            return Optional.empty();
        }
        String value = headers.getCookies().get(name) != null
                ? headers.getCookies().get(name).getValue()
                : null;
        return Optional.ofNullable(value);
    }
}