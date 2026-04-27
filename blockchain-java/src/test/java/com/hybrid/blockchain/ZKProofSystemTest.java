package com.hybrid.blockchain;

import com.hybrid.blockchain.privacy.ZKProofSystem;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for Zero-Knowledge Proof System
 */
public class ZKProofSystemTest {

    @Test
    public void testRangeProof() {
        long temperature = 25;
        long min = 20;
        long max = 30;
        BigInteger randomness = ZKProofSystem.randomScalar();

        ZKProofSystem zkp = new ZKProofSystem();
        
        byte[] commitment = zkp.createCommitment(temperature, randomness);
        byte[] proof = zkp.createRangeProofData(temperature, min, max, randomness);

        assertTrue(zkp.verifyRangeProof(commitment, proof, min, max));
    }

    @Test
    public void testRangeProofOutOfRange() {
        long temperature = 35; // Outside range
        long min = 20;
        long max = 30;
        BigInteger randomness = ZKProofSystem.randomScalar();

        ZKProofSystem zkp = new ZKProofSystem();
        
        assertThrows(IllegalArgumentException.class, () -> {
            zkp.createRangeProofData(temperature, min, max, randomness);
        });
    }

    private byte[] getUncompressedPublicKey(BigInteger privateKey) {
        return org.bouncycastle.crypto.ec.CustomNamedCurves.getByName("secp256k1").getG().multiply(privateKey).getEncoded(false);
    }

    @Test
    public void testOwnershipProof() {
        BigInteger privateKey = new BigInteger("ABCDEF1234567890ABCDEF1234567890ABCDEF1234567890ABCDEF123456", 16);
        byte[] publicKey = getUncompressedPublicKey(privateKey);
        String deviceDID = "did:hybrid:sensor-001";
        byte[] challenge = new byte[]{1,2,3,4};

        ZKProofSystem zkp = new ZKProofSystem();
        byte[] proof = zkp.createOwnershipProof(deviceDID, privateKey, publicKey, challenge);

        assertTrue(zkp.verifyOwnershipProof(deviceDID, publicKey, proof, challenge));
    }

    @Test
    public void testOwnershipProofWrongKey() {
        BigInteger privateKey1 = new BigInteger("1111111111111111111111111111111111111111111111111111111111111111", 16);
        BigInteger privateKey2 = new BigInteger("2222222222222222222222222222222222222222222222222222222222222222", 16);
        byte[] publicKey1 = getUncompressedPublicKey(privateKey1);
        String deviceDID = "did:hybrid:sensor-002";
        byte[] challenge = new byte[]{1,2,3,4};

        ZKProofSystem zkp = new ZKProofSystem();
        byte[] proof = zkp.createOwnershipProof(deviceDID, privateKey2, getUncompressedPublicKey(privateKey2), challenge); // Created with wrong key

        assertFalse(zkp.verifyOwnershipProof(deviceDID, publicKey1, proof, challenge));
    }

    @Test
    public void testOwnershipProofForgedChallengeRejected() {
        BigInteger privateKey = new BigInteger("ABCDEF1234567890ABCDEF1234567890ABCDEF1234567890ABCDEF123456", 16);
        byte[] publicKey = getUncompressedPublicKey(privateKey);
        String deviceDID = "did:hybrid:sensor-forge";
        byte[] challenge = new byte[]{9, 8, 7, 6};

        ZKProofSystem zkp = new ZKProofSystem();
        byte[] proof = zkp.createOwnershipProof(deviceDID, privateKey, publicKey, challenge);

        byte[] forgedChallenge = new byte[]{9, 8, 7, 5};
        assertFalse(zkp.verifyOwnershipProof(deviceDID, publicKey, proof, forgedChallenge));
    }

    @Test
    public void testThresholdProofEqual() {
        long temperature = 25;
        long threshold = 25;
        BigInteger randomness = ZKProofSystem.randomScalar();

        ZKProofSystem zkp = new ZKProofSystem();
        byte[] commitment = zkp.createCommitment(temperature, randomness);
        byte[] proof = zkp.createThresholdProof(temperature, randomness, threshold);

        assertTrue(zkp.verifyThresholdProof(commitment, proof, threshold));
    }

    @Test
    public void testThresholdProofInvalidClaim() {
        long temperature = 20;
        long threshold = 25;
        BigInteger randomness = ZKProofSystem.randomScalar();

        ZKProofSystem zkp = new ZKProofSystem();
        assertThrows(IllegalArgumentException.class, () -> {
            zkp.createThresholdProof(temperature, randomness, threshold);
        });
    }

    @Test
    public void testRandomnessGeneration() {
        BigInteger r1 = ZKProofSystem.randomScalar();
        BigInteger r2 = ZKProofSystem.randomScalar();
        
        assertNotEquals(r1, r2);
        assertTrue(r1.compareTo(BigInteger.ZERO) > 0);
        assertTrue(r2.compareTo(BigInteger.ZERO) > 0);
    }
}
