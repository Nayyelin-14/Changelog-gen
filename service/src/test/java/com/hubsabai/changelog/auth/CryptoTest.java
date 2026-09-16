package com.hubsabai.changelog.auth;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
class CryptoTest {

    @Inject
    Crypto crypto;

    @Test
    void roundTripsAnOAuthToken() {
        String plain = "gho_" + crypto.randomToken(24);

        byte[] blob = crypto.encrypt(plain);

        assertTrue(blob.length > 12, "blob must carry a 12-byte IV plus ciphertext");
        assertNotEquals(plain, new String(blob, StandardCharsets.UTF_8), "at-rest value must not be plaintext");
        assertEquals(plain, crypto.decrypt(blob));
    }

    @Test
    void eachEncryptionUsesAFreshIv() {
        byte[] first = crypto.encrypt("same-token");
        byte[] second = crypto.encrypt("same-token");

        assertNotEquals(0, java.util.Arrays.compare(first, second), "a repeated token must not reuse the IV");
    }

    @Test
    void detectsTampering() {
        byte[] blob = crypto.encrypt("token");
        blob[blob.length - 1] ^= 0x01;

        assertThrows(IllegalStateException.class, () -> crypto.decrypt(blob), "GCM auth must reject a flipped byte");
    }

    @Test
    void randomTokensAreUniqueAndUrlSafe() {
        String a = crypto.randomToken(32);
        String b = crypto.randomToken(32);

        assertNotEquals(a, b);
        assertTrue(a.matches("^[A-Za-z0-9_-]{43}$"), "256-bit token as unpadded base64url");
    }

    @Test
    void sha256HexDigestIsStable() {
        assertEquals(crypto.sha256Hex("abc"), crypto.sha256Hex("abc"));
        assertNotEquals(crypto.sha256Hex("abc"), crypto.sha256Hex("abd"));
        assertEquals(64, crypto.sha256Hex("abc").length());
    }
}