package com.hybrid.blockchain.privacy;

import com.hybrid.blockchain.Crypto;
import org.junit.jupiter.api.*;
import static org.assertj.core.api.Assertions.*;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/**
 * Unit tests for Zero-Knowledge Proof primitives.
 * Covers Range Proofs, Ownership Proofs, and Equality/Threshold Proofs.
 *
 * <h2>ZK1.x — Baseline soundness (original)</h2>
 * <h2>ZK2.x — Expanded forged / tampered proof matrix</h2>
 */
@Tag("privacy")
public class ZKProofSystemCompleteTest {

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
    @DisplayName("ZK1.13 — Randomness uniqueness")
    void testRandomness() {
        byte[] r1 = ZKProofSystem.generateRandomness();
        byte[] r2 = ZKProofSystem.generateRandomness();
        assertThat(r1).isNotEqualTo(r2);
    }

    // ══════════════════════════════════════════════════════════════════════════
    // ZK2.x — Expanded forged / tampered proof matrix
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("ZK2.5 — OwnershipProof: flip one bit in 's' scalar → verify() false")
    void testOwnershipProofScalarFlip() {
        ZKProofSystem zk = new ZKProofSystem();
        byte[] privateKey = Crypto.hash("flip-seed".getBytes());
        byte[] publicKey  = Crypto.derivePublicKey(new java.math.BigInteger(1, privateKey));
        byte[] challenge  = "CHALLENGE_ZK25".getBytes();
        String did        = "did:hybrid:test-zk25";

        byte[] proof = zk.createOwnershipProof(did, new java.math.BigInteger(1, privateKey),
                publicKey, challenge);
        // The 's' scalar occupies bytes 65–96; flip a bit in it
        proof[70] ^= 0x01;

        assertThat(zk.verifyOwnershipProof(did, publicKey, proof, challenge)).isFalse();
    }

    @Test
    @DisplayName("ZK2.6 — OwnershipProof: replay valid proof with wrong public key → verify() false")
    void testOwnershipProofWrongPublicKeyReplay() {
        byte[] priv1 = Crypto.hash("key1".getBytes());
        byte[] pub1  = Crypto.derivePublicKey(new java.math.BigInteger(1, priv1));
        byte[] pub2  = Crypto.derivePublicKey(new java.math.BigInteger(1, Crypto.hash("key2".getBytes())));

        ZKProofSystem.OwnershipProof proof = ZKProofSystem.OwnershipProof.create(priv1, pub1);

        assertThat(proof.verify(pub1)).isTrue();   // sanity: own key
        assertThat(proof.verify(pub2)).isFalse();  // attack: replayed to different key
    }

    @Test
    @DisplayName("ZK2.7 — EqualityProof: corrupt 'R' point bytes 1–4 → verify() false")
    void testEqualityProofCorruptedRPoint() {
        ZKProofSystem zk = new ZKProofSystem();
        java.math.BigInteger r1 = ZKProofSystem.randomScalar();
        java.math.BigInteger r2 = ZKProofSystem.randomScalar();
        byte[] C1 = zk.createCommitment(200, r1);
        byte[] C2 = zk.createCommitment(200, r2);

        // Build proof bytes directly
        java.math.BigInteger deltaR = r1.subtract(r2).mod(ZKProofSystem.N);
        java.math.BigInteger k = ZKProofSystem.randomScalar();
        ZKProofSystem.Point R = ZKProofSystem.mul(ZKProofSystem.H(), k);
        java.math.BigInteger e = ZKProofSystem.hashToScalar(C1, C2, ZKProofSystem.pointBytes(R));
        java.math.BigInteger s = k.subtract(deltaR.multiply(e)).mod(ZKProofSystem.N);

        byte[] proofData = new byte[65 + 32];
        System.arraycopy(ZKProofSystem.pointBytes(R), 0, proofData, 0, 65);
        System.arraycopy(ZKProofSystem.toBytes32(s), 0, proofData, 65, 32);

        // Corrupt bytes 1–4 of R (x-coordinate start)
        proofData[1] ^= 0xFF;
        proofData[2] ^= 0xFF;
        proofData[3] ^= 0xFF;
        proofData[4] ^= 0xFF;

        ZKProofSystem.EqualityProof corrupted = new ZKProofSystem.EqualityProof(C1, C2, proofData);
        assertThat(corrupted.verify()).isFalse();
    }

    @Test
    @DisplayName("ZK2.8 — EqualityProof.create(100,200) throws IAE (values not equal)")
    void testEqualityProofNonEqualValuesThrow() {
        assertThatThrownBy(() -> ZKProofSystem.EqualityProof.create(100, 200))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("ZK2.11 — OwnershipProof: all-zero proof bytes (97 bytes) → verify() false")
    void testOwnershipProofAllZero() {
        byte[] priv   = Crypto.hash("zk211".getBytes());
        byte[] pub    = Crypto.derivePublicKey(new java.math.BigInteger(1, priv));
        byte[] zeroes = new byte[97]; // correct length, all zero

        ZKProofSystem zk = new ZKProofSystem();
        assertThat(zk.verifyOwnershipProof("did:hybrid:test", pub, zeroes,
                "CHALLENGE".getBytes())).isFalse();
    }

    @Test
    @DisplayName("ZK2.12 — Randomness: 100 scalars produce no collisions (collision resistance)")
    void testRandomnessCollisionResistance() {
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < 100; i++) {
            byte[] r = ZKProofSystem.generateRandomness();
            String hex = Crypto.bytesToHex(r);
            assertThat(seen.add(hex))
                    .as("Scalar #%d must be unique; collision detected: %s", i, hex)
                    .isTrue();
        }
    }
}
