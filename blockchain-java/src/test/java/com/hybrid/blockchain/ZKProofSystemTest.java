package com.hybrid.blockchain;

import com.hybrid.blockchain.privacy.ZKProofSystem;
import com.hybrid.blockchain.privacy.ZKProofSystem.*;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for Zero-Knowledge Proof System
 */
public class ZKProofSystemTest {

    @Test
    public void testRangeProof() {
        // Create range proof for temperature reading
        long temperature = 25; // Actual value (secret)
        long min = 20;
        long max = 30;
        BigInteger randomness = ZKProofSystem.generateRandomness();

        // Create proof
        RangeProof proof = RangeProof.create(temperature, min, max, randomness);

        // Verify proof (without knowing actual temperature)
        assertTrue(proof.verify());
        assertEquals(min, proof.getMin());
        assertEquals(max, proof.getMax());
        assertNotNull(proof.getCommitment());
        assertNotNull(proof.getProof());
    }

    @Test
    public void testRangeProofOutOfRange() {
        // Try to create proof for value outside range
        long temperature = 35; // Outside range
        long min = 20;
        long max = 30;
        BigInteger randomness = ZKProofSystem.generateRandomness();

        assertThrows(IllegalArgumentException.class, () -> {
            RangeProof.create(temperature, min, max, randomness);
        });
    }

    @Test
    public void testOwnershipProof() {
        // Generate device keys
        BigInteger privateKey = new BigInteger("ABCDEF1234567890ABCDEF1234567890ABCDEF1234567890ABCDEF123456", 16);
        byte[] publicKey = Crypto.derivePublicKey(privateKey);

        String deviceDID = "did:iot:sensor-001";

        // Generate challenge
        byte[] challenge = OwnershipProof.generateChallenge();

        // Create ownership proof
        OwnershipProof proof = OwnershipProof.create(deviceDID, privateKey, challenge);

        // Verify proof
        assertTrue(proof.verify(deviceDID, publicKey));
        assertArrayEquals(challenge, proof.getChallenge());
    }

    @Test
    public void testOwnershipProofWrongKey() {
        BigInteger privateKey1 = new BigInteger("1111111111111111111111111111111111111111111111111111111111111111", 16);
        BigInteger privateKey2 = new BigInteger("2222222222222222222222222222222222222222222222222222222222222222", 16);

        byte[] publicKey1 = Crypto.derivePublicKey(privateKey1);
        byte[] publicKey2 = Crypto.derivePublicKey(privateKey2);

        String deviceDID = "did:iot:sensor-002";
        byte[] challenge = OwnershipProof.generateChallenge();

        // Create proof with privateKey1
        OwnershipProof proof = OwnershipProof.create(deviceDID, privateKey1, challenge);

        // Verify with correct key
        assertTrue(proof.verify(deviceDID, publicKey1));

        // Verify with wrong key (should fail)
        assertFalse(proof.verify(deviceDID, publicKey2));
    }

    @Test
    public void testEqualityProof() {
        // Two sensors report same temperature
        long temperature = 22;
        BigInteger randomness1 = ZKProofSystem.generateRandomness();
        BigInteger randomness2 = ZKProofSystem.generateRandomness();

        // Create equality proof
        EqualityProof proof = EqualityProof.create(temperature, randomness1, randomness2);

        // Verify proof
        assertTrue(proof.verify());
        assertNotNull(proof.getCommitment1());
        assertNotNull(proof.getCommitment2());
    }

    @Test
    public void testThresholdProofAbove() {
        // Prove temperature is above threshold
        long temperature = 28;
        long threshold = 25;
        BigInteger randomness = ZKProofSystem.generateRandomness();

        // Create proof that temperature > 25
        ThresholdProof proof = ThresholdProof.create(temperature, threshold, true, randomness);

        // Verify proof
        assertTrue(proof.verify());
        assertEquals(threshold, proof.getThreshold());
        assertTrue(proof.isAbove());
    }

    @Test
    public void testThresholdProofBelow() {
        // Prove temperature is below threshold
        long temperature = 18;
        long threshold = 20;
        BigInteger randomness = ZKProofSystem.generateRandomness();

        // Create proof that temperature < 20
        ThresholdProof proof = ThresholdProof.create(temperature, threshold, false, randomness);

        // Verify proof
        assertTrue(proof.verify());
        assertEquals(threshold, proof.getThreshold());
        assertFalse(proof.isAbove());
    }

    @Test
    public void testThresholdProofInvalidClaim() {
        // Try to prove temperature > 25 when it's actually 20
        long temperature = 20;
        long threshold = 25;
        BigInteger randomness = ZKProofSystem.generateRandomness();

        assertThrows(IllegalArgumentException.class, () -> {
            ThresholdProof.create(temperature, threshold, true, randomness);
        });
    }

    @Test
    public void testRandomnessGeneration() {
        // Generate multiple random values
        BigInteger r1 = ZKProofSystem.generateRandomness();
        BigInteger r2 = ZKProofSystem.generateRandomness();
        BigInteger r3 = ZKProofSystem.generateRandomness();

        // Verify they're different
        assertNotEquals(r1, r2);
        assertNotEquals(r2, r3);
        assertNotEquals(r1, r3);

        // Verify they're positive
        assertTrue(r1.compareTo(BigInteger.ZERO) > 0);
        assertTrue(r2.compareTo(BigInteger.ZERO) > 0);
        assertTrue(r3.compareTo(BigInteger.ZERO) > 0);
    }
}
