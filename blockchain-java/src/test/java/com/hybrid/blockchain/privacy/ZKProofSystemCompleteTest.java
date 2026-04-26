package com.hybrid.blockchain.privacy;

import com.hybrid.blockchain.Crypto;
import org.junit.jupiter.api.*;
import static org.assertj.core.api.Assertions.*;

/**
 * Unit tests for Zero-Knowledge Proof primitives.
 * Covers Range Proofs, Ownership Proofs, and Equality/Threshold Proofs.
 */
@Tag("privacy")
public class ZKProofSystemCompleteTest {

    @Test
    @DisplayName("ZK1.1-1.3 — RangeProof boundary checks")
    void testRangeProofBoundaries() {
        long value = 50;
        long min = 0;
        long max = 100;
        
        // ZK1.1: Valid
        assertThatCode(() -> ZKProofSystem.RangeProof.create(value, min, max))
                .doesNotThrowAnyException();
        
        // ZK1.2: Below min
        assertThatThrownBy(() -> ZKProofSystem.RangeProof.create(-1, min, max))
                .isInstanceOf(IllegalArgumentException.class);
        
        // ZK1.3: Above max
        assertThatThrownBy(() -> ZKProofSystem.RangeProof.create(101, min, max))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("ZK1.4 — RangeProof.verify()")
    void testRangeProofVerify() {
        ZKProofSystem.RangeProof proof = ZKProofSystem.RangeProof.create(50, 0, 100);
        assertThat(proof.verify()).isTrue();
    }

    @Test
    @DisplayName("ZK1.5 — RangeProof.verify() failure on tampering")
    @Disabled("Requires Fix 1 — real ZKP commitment check implementation")
    void testRangeProofTamper() {
        ZKProofSystem.RangeProof proof = ZKProofSystem.RangeProof.create(50, 0, 100);
        // Tamper with the internal commitment if accessible, or public bytes
        assertThat(proof.verify()).isFalse();
    }

    @Test
    @DisplayName("ZK1.6-1.7 — OwnershipProof")
    void testOwnershipProof() {
        byte[] privateKey = Crypto.hash("seed".getBytes());
        byte[] publicKey = Crypto.derivePublicKey(new java.math.BigInteger(1, privateKey));
        
        ZKProofSystem.OwnershipProof proof = ZKProofSystem.OwnershipProof.create(privateKey, publicKey);
        assertThat(proof.verify(publicKey)).isTrue();
        
        // ZK1.7: wrong public key
        byte[] otherPub = Crypto.derivePublicKey(new java.math.BigInteger(1, Crypto.hash("other".getBytes())));
        assertThat(proof.verify(otherPub)).isFalse();
    }

    @Test
    @DisplayName("ZK1.8-1.9 — EqualityProof")
    void testEqualityProof() {
        ZKProofSystem.EqualityProof proof = ZKProofSystem.EqualityProof.create(100, 100);
        assertThat(proof.verify()).isTrue();
        
        // Non-equal values should fail creation OR verification
        assertThatThrownBy(() -> ZKProofSystem.EqualityProof.create(100, 101))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("ZK1.10-1.12 — ThresholdProof")
    void testThresholdProof() {
        // Above threshold
        ZKProofSystem.ThresholdProof above = ZKProofSystem.ThresholdProof.create(100, 50, true);
        assertThat(above.verify()).isTrue();
        
        // Below threshold
        ZKProofSystem.ThresholdProof below = ZKProofSystem.ThresholdProof.create(10, 50, false);
        assertThat(below.verify()).isTrue();
        
        // Wrong claim
        assertThatThrownBy(() -> ZKProofSystem.ThresholdProof.create(10, 50, true))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("ZK1.13 — Randomness uniqueness")
    void testRandomness() {
        byte[] r1 = ZKProofSystem.generateRandomness();
        byte[] r2 = ZKProofSystem.generateRandomness();
        assertThat(r1).isNotEqualTo(r2);
    }
}
