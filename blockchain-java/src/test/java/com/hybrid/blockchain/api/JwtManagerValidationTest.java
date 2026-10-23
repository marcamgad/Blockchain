package com.hybrid.blockchain.api;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Date;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * How {@link JwtManager#validateDetailed} classifies tokens. Added with the jjwt 0.11 → 0.13
 * upgrade, whose parser API and exception classes changed: nothing tested this mapping before.
 */
class JwtManagerValidationTest {

    private final JwtManager jwt = new JwtManager("test-secret-for-validation");

    private static String b64(String json) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(json.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    @DisplayName("valid, expired, revoked")
    void lifecycle() {
        String ok = jwt.issueToken("hbA");
        assertThat(jwt.validateDetailed(ok)).isEqualTo(JwtManager.ValidationResult.VALID);
        assertThat(jwt.validateToken(ok, "hbA")).isTrue();
        assertThat(jwt.validateToken(ok, "hbB")).as("subject must match").isFalse();

        String old = jwt.issueToken("hbA", "DEVICE", new Date(System.currentTimeMillis() - 1000));
        assertThat(jwt.validateDetailed(old)).isEqualTo(JwtManager.ValidationResult.EXPIRED);

        jwt.revoke(ok);
        assertThat(jwt.validateDetailed(ok)).isEqualTo(JwtManager.ValidationResult.REVOKED);
    }

    @Test
    @DisplayName("a token signed with another secret, or with a changed payload, is BAD_SIGNATURE")
    void badSignature() {
        String foreign = new JwtManager("another-secret").issueToken("hbA", "ADMIN");
        assertThat(jwt.validateDetailed(foreign)).isEqualTo(JwtManager.ValidationResult.BAD_SIGNATURE);
        assertThat(jwt.isAdmin(foreign)).isFalse();

        String[] parts = jwt.issueToken("hbA", "DEVICE").split("\\.");
        String payload = new String(Base64.getUrlDecoder().decode(parts[1]), StandardCharsets.UTF_8).replace("DEVICE", "ADMIN");
        String tampered = parts[0] + "." + b64(payload) + "." + parts[2];
        assertThat(jwt.validateDetailed(tampered)).isEqualTo(JwtManager.ValidationResult.BAD_SIGNATURE);
        assertThat(jwt.isAdmin(tampered)).isFalse();
    }

    @Test
    @DisplayName("an unsigned (alg: none) token or garbage never validates")
    void unsignedAndMalformed() {
        String none = b64("{\"alg\":\"none\"}") + "." + b64("{\"iss\":\"hybridchain\",\"sub\":\"hbA\",\"role\":\"ADMIN\"}") + ".";
        assertThat(jwt.validateDetailed(none)).isNotEqualTo(JwtManager.ValidationResult.VALID);
        assertThat(jwt.validateToken(none)).isFalse();
        assertThat(jwt.isAdmin(none)).isFalse();

        assertThat(jwt.validateDetailed("not.a.jwt")).isEqualTo(JwtManager.ValidationResult.MALFORMED);
        assertThat(jwt.validateDetailed("")).isEqualTo(JwtManager.ValidationResult.MALFORMED);
    }
}
