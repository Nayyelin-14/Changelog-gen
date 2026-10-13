package com.hubsabai.changelog.api;

import com.hubsabai.changelog.auth.CurrentUser;
import com.hubsabai.changelog.connector.github.GitHubOrgConnector;
import com.hubsabai.changelog.storage.ChangelogVersion;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.ForbiddenException;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.core.Response;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Ownership and capability decisions, in one place so every endpoint answers them the same way:
 * <ul>
 *   <li><b>401</b> — nobody signed in (normally caught earlier by {@code AuthenticatedFilter}).</li>
 *   <li><b>403</b> — signed in, but the thing belongs to somebody else, or the caller's GitHub
 *       account cannot see the repository it names.</li>
 *   <li><b>404</b> — nobody has ever created the thing.</li>
 * </ul>
 *
 * <p>Ownership is per internal user id (see {@code owner_user_id}). A row with a null owner belongs
 * to the service/CI, so any signed-in user may read it — but only if their own GitHub account can
 * actually reach that repository, which is checked with the <em>user's</em> token, not the service
 * account's. That check exists because the service token is not a source of truth about what a
 * given user may see.
 */
@ApplicationScoped
public class AccessGuard {

    /** (userId, project, repo) -> whether the user's token can reach that repo. Bounded, short-lived. */
    private static final long ACCESS_TTL_MILLIS = 60_000;
    private static final int ACCESS_CACHE_MAX = 1_000;

    @Inject
    CurrentUser currentUser;

    @Inject
    GitHubOrgConnector orgConnector;

    private final Map<String, AtomicLong> accessCheckedAt = new ConcurrentHashMap<>();

    /** The signed-in user's id, or 401 if there is no signed-in user. */
    public Long requireUserId() {
        return currentUser.id().orElseThrow(() -> unauthorized());
    }

    /**
     * The version row for {@code (project, repo, version)} that this caller may use, or a 403/404
     * explaining why they may not.
     *
     * <p>Uses the {@code "azure"} provider key because that is what every changelog_version /
     * generated_changelog row is written with today (both the GitHub and Azure resources go through
     * {@code ChangelogService.getOrCreateVersion}, which does not take a provider). Changing that
     * is a data migration, not a security fix.
     */
    public ChangelogVersion requireVersion(String project, String repo, String version) {
        requireUserId();
        ChangelogVersion mine = ChangelogVersion.findEntry(project, repo, version, currentUser.ownerUserId());
        if (mine != null) {
            return mine;
        }
        long total = ChangelogVersion.count(
                "provider = ?1 and project = ?2 and repo = ?3 and version = ?4",
                "azure", project, repo, version);
        if (total == 0) {
            throw new NotFoundException(Response.status(Response.Status.NOT_FOUND)
                    .type("application/json")
                    .entity(Map.of("error", "not_found", "detail", "No changelog for " + version + "."))
                    .build());
        }
        // Rows exist, just not this caller's — either the service's (still needs repo access) or
        // another user's (never readable).
        ChangelogVersion serviceRow = ChangelogVersion.findEntry(project, repo, version, null);
        if (serviceRow != null) {
            requireRepoAccess(project, repo);
            return serviceRow;
        }
        throw forbidden("That changelog belongs to another user.");
    }

    /** Fails with 403 unless the caller's own GitHub token can reach {@code project/repo}. */
    public void requireRepoAccess(String project, String repo) {
        requireUserId();
        Long userId = currentUser.id().orElseThrow(() -> unauthorized());
        if (canReach(userId, project, repo)) {
            return;
        }
        throw forbidden("Your GitHub account cannot access " + project + "/" + repo + ".");
    }

    private boolean canReach(Long userId, String project, String repo) {
        String key = userId + "\0" + project + "\0" + repo;
        AtomicLong last = accessCheckedAt.computeIfAbsent(key, k -> new AtomicLong(0));
        long now = System.currentTimeMillis();
        long checkedAt = last.get();
        if (now - checkedAt < ACCESS_TTL_MILLIS) {
            return true;
        }
        // GitHub is the source of truth here, evaluated with the user's token (the connector
        // stamps it). Its own 404/403 surfaces as a null branch, i.e. "cannot reach".
        boolean reachable = orgConnector.defaultBranch(project, repo) != null;
        if (reachable) {
            last.set(now);
        } else {
            accessCheckedAt.remove(key, last);
        }
        return reachable;
    }

    private static ForbiddenException forbidden(String detail) {
        return new ForbiddenException(Response.status(Response.Status.FORBIDDEN)
                .type("application/json")
                .entity(Map.of("error", "forbidden", "detail", detail))
                .build());
    }

    private static jakarta.ws.rs.WebApplicationException unauthorized() {
        return new jakarta.ws.rs.WebApplicationException(Response.status(Response.Status.UNAUTHORIZED)
                .type("application/json")
                .entity(Map.of("error", "not_authenticated"))
                .build());
    }

    /** Bounds the cache so a hostile caller can't grow it without limit. */
    void trimAccessCache() {
        if (accessCheckedAt.size() > ACCESS_CACHE_MAX) {
            accessCheckedAt.clear();
        }
    }
}
