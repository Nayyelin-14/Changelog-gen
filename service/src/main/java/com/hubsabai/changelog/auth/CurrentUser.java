package com.hubsabai.changelog.auth;

import jakarta.enterprise.context.RequestScoped;

import java.util.Optional;

/**
 * The signed-in user for the current HTTP request, populated by {@link AuthSessionFilter} from the
 * session cookie. Request-scoped so the GitHub REST client can stamp the user's own token onto its
 * outgoing calls; empty on anonymous requests (or worker threads without a request context).
 */
@RequestScoped
public class CurrentUser {

    private GithubUser user;
    private String accessToken;

    public void set(GithubUser user, String accessToken) {
        this.user = user;
        this.accessToken = accessToken;
    }

    public boolean isPresent() {
        return user != null;
    }

    public Optional<GithubUser> get() {
        return Optional.ofNullable(user);
    }

    /** The user's GitHub access token, decrypted for this request only. */
    public Optional<String> accessToken() {
        return Optional.ofNullable(accessToken);
    }

    public Optional<String> login() {
        return user != null ? Optional.ofNullable(user.login) : Optional.empty();
    }

    public void clear() {
        this.user = null;
        this.accessToken = null;
    }
}