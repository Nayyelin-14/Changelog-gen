package com.hubsabai.changelog.auth;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.transaction.UserTransaction;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
class SessionServiceTest {

    @Inject
    SessionService sessions;

    @Inject
    Crypto crypto;

    @Inject
    UserTransaction utx;

    @BeforeEach
    void cleanSlate() throws Exception {
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

    private long createUser(long githubId, String login, String token) {
        var user = new GithubUser();
        user.githubId = githubId;
        user.login = login;
        user.accessToken = crypto.encrypt(token);
        try {
            utx.begin();
            user.persist();
            utx.commit();
        } catch (Exception e) {
            try {
                utx.rollback();
            } catch (Exception rollback) {
                e.addSuppressed(rollback);
            }
            throw new RuntimeException(e);
        }
        return user.id;
    }

    @Test
    void createThenResolveReturnsTheUserAndItsDecryptedToken() {
        long userId = createUser(7L, "octo", "gho_secret");
        SessionService.NewSession newSession = sessions.create(userId);

        assertEquals(30 * 24 * 3600, newSession.maxAgeSeconds());

        Optional<SessionService.ResolvedSession> resolved = sessions.resolve(newSession.token());

        assertTrue(resolved.isPresent());
        assertEquals(userId, resolved.get().user().id);
        assertEquals("gho_secret", resolved.get().accessToken());
    }

    @Test
    void onlyTheDigestIsStoredNeverTheRawToken() {
        long userId = createUser(8L, "octo2", "gho_secret");
        String token = sessions.create(userId).token();

        assertNull(AuthSession.findByTokenHash(token), "a raw token must never appear as a stored hash");
        assertNotNull(AuthSession.findByTokenHash(crypto.sha256Hex(token)), "only the SHA-256 digest is stored");
    }

    @Test
    void resolvingAnUnknownOrBlankCookieIsEmpty() {
        assertTrue(sessions.resolve("garbage-token").isEmpty());
        assertTrue(sessions.resolve(null).isEmpty());
        assertTrue(sessions.resolve("  ").isEmpty());
    }

    @Test
    void deleteRemovesExactlyTheGivenSession() {
        long userId = createUser(9L, "octo3", "tok-a");
        String a = sessions.create(userId).token();
        String b = sessions.create(userId).token();

        sessions.delete(a);

        assertTrue(sessions.resolve(a).isEmpty());
        assertTrue(sessions.resolve(b).isPresent());
    }

    @Test
    void deleteAllForUserKillsEverySessionForThatUser() {
        long alice = createUser(10L, "alice", "tok");
        long bob = createUser(11L, "bob", "tok");
        String aliceSession = sessions.create(alice).token();
        String anotherAliceSession = sessions.create(alice).token();
        String bobSession = sessions.create(bob).token();

        sessions.deleteAllForUser(alice);

        assertTrue(sessions.resolve(aliceSession).isEmpty());
        assertTrue(sessions.resolve(anotherAliceSession).isEmpty());
        assertTrue(sessions.resolve(bobSession).isPresent());
    }

    @Test
    void twoSessionsForOneUserYieldTwoDistinctTokens() {
        long userId = createUser(12L, "octo4", "tok");
        assertNotEquals(sessions.create(userId).token(), sessions.create(userId).token());
    }

    @Test
    void sessionSurvivesUntilItsUserIsDeleted() {
        long userId = createUser(13L, "octo5", "tok");
        String token = sessions.create(userId).token();
        assertTrue(sessions.resolve(token).isPresent());

        GithubUser user = GithubUser.findById(userId);
        try {
            utx.begin();
            user.delete();
            utx.commit();
        } catch (Exception e) {
            try {
                utx.rollback();
            } catch (Exception rollback) {
                e.addSuppressed(rollback);
            }
            throw new RuntimeException(e);
        }

        assertTrue(sessions.resolve(token).isEmpty(), "deleting the user must invalidate their sessions");
    }
}