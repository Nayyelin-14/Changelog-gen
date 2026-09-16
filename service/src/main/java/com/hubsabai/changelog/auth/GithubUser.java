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
 * A user who signed in via GitHub OAuth. {@code accessToken}/{@code refreshToken} are the
 * encrypted-at-rest OAuth tokens (AES-GCM keyed from {@code auth.session-secret}) — the only
 * credential this service holds for a user. Password and email never reach this table. Deleting
 * the row erases the user's credentials and cascades to {@link AuthSession}; generated changelogs
 * live in unrelated tables and are deliberately untouched.
 */
@Entity
@Table(
    name = "github_user",
    uniqueConstraints = {
        @UniqueConstraint(name = "uq_github_user_github_id", columnNames = "github_id"),
        @UniqueConstraint(name = "uq_github_user_login", columnNames = "login")
    })
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

    @Column(name = "access_token", nullable = false)
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

    public static GithubUser findByLogin(String login) {
        return find("login", login).firstResult();
    }
}