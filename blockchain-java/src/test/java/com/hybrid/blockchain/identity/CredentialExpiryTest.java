package com.hybrid.blockchain.identity;

import com.hybrid.blockchain.TestKeyPair;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** [HC-050] credential expiry and signing encoding. */
class CredentialExpiryTest {

    private static final TestKeyPair ISSUER = new TestKeyPair(8401);

    private static VerifiableCredential vc(Map<String, Object> claims) {
        return new VerifiableCredential("did:hybrid:issuer", "did:hybrid:subject", claims);
    }

    @Test
    @DisplayName("an expired credential is expired; a future one is not; no expiry never expires")
    void expiry() throws Exception {
        VerifiableCredential past = vc(Map.of("role", "sensor"));
        past.setExpiration(-1_000);
        VerifiableCredential future = vc(Map.of("role", "sensor"));
        future.setExpiration(60_000);
        VerifiableCredential none = vc(Map.of("role", "sensor"));
        assertThat(past.isExpired()).isTrue();
        assertThat(future.isExpired()).isFalse();
        assertThat(none.isExpired()).isFalse();
    }

    @Test
    @DisplayName("an unparseable expiry fails closed")
    void unparseableExpiryFailsClosed() throws Exception {
        VerifiableCredential c = vc(Map.of("role", "sensor"));
        java.lang.reflect.Field f = VerifiableCredential.class.getDeclaredField("expirationDate");
        f.setAccessible(true);
        f.set(c, "Sun Jan 01 00:00:00 CET 2040");   // legacy Date.toString() form
        assertThat(c.isExpired()).isTrue();
    }

    @Test
    @DisplayName("signatures verify with and without expiry, and for large credentials")
    void signingRoundTrip() {
        VerifiableCredential a = vc(Map.of("role", "sensor"));
        a.setExpiration(60_000);
        a.sign(ISSUER.getPrivateKey(), ISSUER.getPublicKey());
        assertThat(a.verify(ISSUER.getPublicKey())).isTrue();

        Map<String, Object> big = new HashMap<>();
        for (int i = 0; i < 200; i++) big.put("claim" + i, "x".repeat(50));   // > 4 KB to sign
        VerifiableCredential b = vc(big);
        b.sign(ISSUER.getPrivateKey(), ISSUER.getPublicKey());
        assertThat(b.verify(ISSUER.getPublicKey())).isTrue();
        assertThat(b.verify(new TestKeyPair(8402).getPublicKey())).isFalse();
    }
}
