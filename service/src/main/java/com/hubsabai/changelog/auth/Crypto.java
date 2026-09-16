package com.hubsabai.changelog.auth;

import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * At-rest crypto for OAuth tokens plus the random-token/hash helpers the session layer needs.
 *
 * <p>The AES-256-GCM key is derived from the operator-supplied {@code auth.session-secret} via
 * SHA-256 (one-way, so a leaked session secret still doesn't reveal an encryption key; and the
 * secret doubles as the source for the token digest salt). A fresh 12-byte IV is generated per
 * encryption, and the stored blob is {@code [iv(12) | ciphertext+tag]}.
 */
@ApplicationScoped
public class Crypto {

    private static final int GCM_IV_BYTES = 12;
    private static final int GCM_TAG_BITS = 128;
    private static final int KEY_BYTES = 32;
    private static final SecureRandom RANDOM = new SecureRandom();

    private final byte[] key;
    private final boolean enabled;

    public Crypto(@ConfigProperty(name = "auth.session-secret", defaultValue = "") String sessionSecret) {
        this.enabled = sessionSecret != null && !sessionSecret.isBlank();
        this.key = this.enabled ? deriveKey(sessionSecret) : new byte[KEY_BYTES];
    }

    /** OAuth access (and refresh) tokens are only minted when a session secret is configured. */
    public boolean enabled() {
        return enabled;
    }

    /** A fresh random token, Base64-url-encoded (no padding) — used for OAuth state and sessions. */
    public String randomToken(int bytes) {
        byte[] raw = new byte[bytes];
        RANDOM.nextBytes(raw);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
    }

    /** SHA-256 hex digest — the form under which a session token is stored. */
    public String sha256Hex(String value) {
        try {
            return hex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    public byte[] encrypt(String plaintext) {
        requireEnabled();
        try {
            byte[] iv = new byte[GCM_IV_BYTES];
            RANDOM.nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(GCM_TAG_BITS, iv));
            byte[] ciphertext = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            byte[] blob = new byte[GCM_IV_BYTES + ciphertext.length];
            System.arraycopy(iv, 0, blob, 0, GCM_IV_BYTES);
            System.arraycopy(ciphertext, 0, blob, GCM_IV_BYTES, ciphertext.length);
            return blob;
        } catch (Exception e) {
            throw new IllegalStateException("Failed to encrypt OAuth token", e);
        }
    }

    public String decrypt(byte[] blob) {
        requireEnabled();
        try {
            byte[] iv = java.util.Arrays.copyOfRange(blob, 0, GCM_IV_BYTES);
            byte[] ciphertext = java.util.Arrays.copyOfRange(blob, GCM_IV_BYTES, blob.length);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(GCM_TAG_BITS, iv));
            return new String(cipher.doFinal(ciphertext), StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to decrypt OAuth token", e);
        }
    }

    private void requireEnabled() {
        if (!enabled) {
            throw new IllegalStateException("auth.session-secret is not configured — GitHub sign-in is disabled.");
        }
    }

    private static byte[] deriveKey(String sessionSecret) {
        try {
            return MessageDigest.getInstance("SHA-256")
                    .digest((sessionSecret + ":github-oauth-token").getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private static String hex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }
}