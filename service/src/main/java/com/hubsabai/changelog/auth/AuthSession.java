package com.hubsabai.changelog.auth;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.OffsetDateTime;

/**
 * One server-side sign-in session. The 256-bit random session token is served to the browser as
 * an opaque HttpOnly cookie; only its SHA-256 digest is stored here, so a DB leak exposes no
 * usable token. Tied to a {@link GithubUser} by bare id (no ORM association, so deleting a user
 * stays a trivial cascade).
 */
@Entity
@Table(name = "auth_session")
public class AuthSession extends PanacheEntityBase {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;

    @Column(name = "session_token_hash", nullable = false, unique = true, length = 64)
    public String sessionTokenHash;

    @Column(name = "user_id", nullable = false)
    public Long userId;

    @Column(name = "created_at", nullable = false)
    public OffsetDateTime createdAt = OffsetDateTime.now();

    @Column(name = "last_seen_at", nullable = false)
    public OffsetDateTime lastSeenAt = OffsetDateTime.now();

    @Column(name = "expires_at", nullable = false)
    public OffsetDateTime expiresAt;

    public static AuthSession findByTokenHash(String tokenHash) {
        return find("sessionTokenHash", tokenHash).firstResult();
    }

    public static void deleteByUserId(Long userId) {
        delete("userId", userId);
    }
}