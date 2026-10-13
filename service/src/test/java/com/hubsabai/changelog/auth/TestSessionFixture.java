package com.hubsabai.changelog.auth;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;

import java.time.OffsetDateTime;

/**
 * Signs a test in as a real {@link GithubUser} with a real {@link AuthSession}, so tests exercise
 * the production auth path (the {@code cc_session} cookie, resolved by {@link AuthSessionFilter})
 * instead of stubbing around it. Anything that bypasses the cookie would stop covering the
 * default-deny filter and the ownership stamping that depends on it.
 */
@ApplicationScoped
public class TestSessionFixture {

    /** A distinct GitHub id per fixture user, well clear of any real account. */
    private static long nextGithubId = 910_000L;

    @Inject
    SessionService sessions;

    @Inject
    Crypto crypto;

    /** The {@code cc_session} cookie value for a signed-in user with a GitHub account attached. */
    @Transactional
    public String signIn() {
        return signIn("fixture-user-" + nextGithubId++);
    }

    @Transactional
    public String signIn(String login) {
        long githubId = nextGithubId++;
        GithubUser user = new GithubUser();
        user.githubId = githubId;
        user.login = login;
        user.avatarUrl = "https://avatars.test/" + login + ".jpg";
        user.accessToken = crypto.encrypt("gho_fixture_" + githubId);
        user.createdAt = OffsetDateTime.now();
        user.updatedAt = OffsetDateTime.now();
        user.persist();
        return sessions.create(user.id).token();
    }

    /** Signs in and also returns the account, so a test can assert on ownership of rows it creates. */
    @Transactional
    public SignedIn signInWithUser() {
        String login = "fixture-user-" + nextGithubId++;
        long githubId = nextGithubId++;
        GithubUser user = new GithubUser();
        user.githubId = githubId;
        user.login = login;
        user.accessToken = crypto.encrypt("gho_fixture_" + githubId);
        user.createdAt = OffsetDateTime.now();
        user.updatedAt = OffsetDateTime.now();
        user.persist();
        return new SignedIn(sessions.create(user.id).token(), user.id, githubId);
    }

    public record SignedIn(String cookie, Long userId, long githubId) {}
}
