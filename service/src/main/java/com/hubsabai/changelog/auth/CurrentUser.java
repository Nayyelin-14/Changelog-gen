package com.hubsabai.changelog.auth;

import jakarta.enterprise.context.RequestScoped;

import java.util.Optional;

/**
 * The request actor, populated before the resource method runs by {@link AuthSessionFilter} (signed
 * in user) or {@link PipelineAuthFilter} (pipeline/service caller). Request-scoped so the GitHub
 * REST client can stamp the user's own token onto its outgoing calls.
 *
 * <p>Three states, mutually exclusive:
 * <ul>
 *   <li><b>USER</b> — {@link #get()} is present; {@link #id()} is the internal, immutable app user id.</li>
 *   <li><b>SERVICE</b> — {@link #isService()}; the caller authenticated with a pipeline API key and
 *       has no user identity of its own.</li>
 *   <li><b>ANONYMOUS</b> — neither. On worker threads without a request context this bean cannot be
 *       injected, so callers must treat "not present" as ANONYMOUS and fail closed.</li>
 * </ul>
 */
@RequestScoped
public class CurrentUser {

    private GithubUser user;
    private String accessToken;
    private boolean service;

    public void set(GithubUser user, String accessToken) {
        this.user = user;
        this.accessToken = accessToken;
        this.service = false;
    }

    /** Marks this request as pipeline/service-authenticated (no user identity). */
    public void markService() {
        this.service = true;
    }

    public boolean isPresent() {
        return user != null;
    }

    public boolean isService() {
        return service;
    }

    public Optional<GithubUser> get() {
        return Optional.ofNullable(user);
    }

    /**
     * The internal, immutable app user id — the ownership key for all stored data. Deliberately not
     * the GitHub login (mutable) and not the GitHub numeric id (an external identifier, so also not
     * the thing to key ownership on).
     */
    public Optional<Long> id() {
        return user != null ? Optional.ofNullable(user.id) : Optional.empty();
    }

    /**
     * The {@code owner_user_id} to stamp on rows this request creates, or {@code null} for rows the
     * service/CI owns. Null-owned rows are the service's, not any one user's.
     */
    public Long ownerUserId() {
        return user != null ? user.id : null;
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
        this.service = false;
    }
}
