package com.hubsabai.changelog.auth;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.time.OffsetDateTime;

/**
 * A user who signed in via GitHub OAuth. One row == one application account; its {@link #id} is the
 * internal, immutable ownership key every changelog row points at. {@code accessToken}/
 * {@code refreshToken} are the encrypted-at-rest OAuth tokens (AES-GCM keyed from
 * {@code auth.session-secret}) — the only credential this service holds for a user. Password and
 * email never reach this table.
 *
 * <p>{@code github_id} is the sole identity key, because it is the one GitHub identifier that
 * survives a username change. {@code login} is a display snapshot only and deliberately carries
 * <b>no</b> unique constraint: GitHub usernames are mutable and can later be claimed by a different
 * person, so anything keyed on them could hand that person the original user's changelogs.
 *
 * <p>{@code access_token} is nullable because "disconnect GitHub" revokes and clears the token
 * while keeping the account (and therefore the ownership target of the user's data).
 */
@Entity
@Table(
    name = "github_user",
    uniqueConstraints = @UniqueConstraint(name = "uq_github_user_github_id", columnNames = "github_id"))
public class GithubUser extends PanacheEntityBase {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;

    /** The numeric id GitHub assigns the user — stable across renames, unlike {@link #login}. */
    @Column(name = "github_id", nullable = false)
    public Long githubId;

    @Column(nullable = false)
    public String login;

    @Column(name = "avatar_url")
    public String avatarUrl;

    /** Null once the user has disconnected GitHub. */
    @Column(name = "access_token")
    public byte[] accessToken;

    @Column(name = "refresh_token")
    public byte[] refreshToken;

    @Column(name = "token_expires_at")
    public OffsetDateTime tokenExpiresAt;

    @Column(name = "created_at", nullable = false)
    public OffsetDateTime createdAt = OffsetDateTime.now();

    @Column(name = "updated_at", nullable = false)
    public OffsetDateTime updatedAt = OffsetDateTime.now();

    public static GithubUser findByGithubId(Long githubId) {
        return find("githubId", githubId).firstResult();
    }
}