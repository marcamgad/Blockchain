package com.hybrid.blockchain.security;

import com.hybrid.blockchain.Storage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;

/**
 * PUFchain-style tamper-evident device identity anchoring.
 */
public class PufAnchorRegistryTest {

    private Storage storage;
    private File dir;

    @BeforeEach
    void setup() throws Exception {
        dir = new File(System.getProperty("java.io.tmpdir"), "puf-anchor-" + UUID.randomUUID());
        dir.mkdirs();
        storage = new Storage(dir.getAbsolutePath(), "0123456789abcdef".getBytes(StandardCharsets.UTF_8));
    }

    @AfterEach
    void teardown() throws Exception {
        if (storage != null) storage.close();
    }

    @Test
    @DisplayName("Anchored device verifies with the same PUF response")
    void anchorThenVerify() {
        byte[] puf = PUFIdentityProvider.getSimulatedPUFResponse("drone-42");
        PufAnchorRegistry.anchorIdentity("drone-42", puf, storage);

        assertThat(PufAnchorRegistry.isAnchored("drone-42", storage)).isTrue();
        assertThat(PufAnchorRegistry.verifyIdentity("drone-42", puf, storage)).isTrue();
    }

    @Test
    @DisplayName("A cloned device with a different fingerprint fails verification")
    void clonedDeviceRejected() {
        byte[] genuine = PUFIdentityProvider.getSimulatedPUFResponse("drone-42");
        byte[] clone   = PUFIdentityProvider.getSimulatedPUFResponse("drone-42-CLONE");
        PufAnchorRegistry.anchorIdentity("drone-42", genuine, storage);

        assertThat(PufAnchorRegistry.verifyIdentity("drone-42", clone, storage)).isFalse();
    }

    @Test
    @DisplayName("Unprovisioned device does not verify")
    void unanchoredRejected() {
        byte[] puf = PUFIdentityProvider.getSimulatedPUFResponse("ghost");
        assertThat(PufAnchorRegistry.isAnchored("ghost", storage)).isFalse();
        assertThat(PufAnchorRegistry.verifyIdentity("ghost", puf, storage)).isFalse();
    }

    @Test
    @DisplayName("Re-anchoring with the same fingerprint is idempotent")
    void reAnchorSameIsIdempotent() {
        byte[] puf = PUFIdentityProvider.getSimulatedPUFResponse("sensor-7");
        String a1 = PufAnchorRegistry.anchorIdentity("sensor-7", puf, storage);
        String a2 = PufAnchorRegistry.anchorIdentity("sensor-7", puf, storage);
        assertThat(a1).isEqualTo(a2);
    }

    @Test
    @DisplayName("Re-anchoring a device to a different fingerprint is refused")
    void reAnchorConflictRefused() {
        byte[] first  = PUFIdentityProvider.getSimulatedPUFResponse("sensor-7");
        byte[] second = PUFIdentityProvider.getSimulatedPUFResponse("sensor-7-swapped");
        PufAnchorRegistry.anchorIdentity("sensor-7", first, storage);

        assertThatThrownBy(() -> PufAnchorRegistry.anchorIdentity("sensor-7", second, storage))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("The anchor is not the key-derivation hash (domain separation)")
    void anchorIsNotKeyMaterial() {
        byte[] puf = PUFIdentityProvider.getSimulatedPUFResponse("dev");
        byte[] anchor = PUFIdentityProvider.computeAnchor(puf);
        java.math.BigInteger key = PUFIdentityProvider.derivePrivateKey(puf);
        // The public anchor must not equal the private-key scalar's bytes.
        assertThat(new java.math.BigInteger(1, anchor)).isNotEqualTo(key);
    }
}
