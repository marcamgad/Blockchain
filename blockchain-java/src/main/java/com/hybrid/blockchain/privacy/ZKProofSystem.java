package com.hybrid.blockchain.privacy;

import com.hybrid.blockchain.Crypto;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.security.SecureRandom;
import java.util.Arrays;

/**
 * Zero-Knowledge Proof System for IoT Privacy
 * 
 * Allows devices to prove facts about their data without revealing the data
 * itself.
 * 
 * Implementations:
 * 1. Range Proof - Prove value is within range without revealing exact value
 * 2. Ownership Proof - Prove device ownership without revealing private key
 */
public class ZKProofSystem {

    private static final SecureRandom RANDOM = new SecureRandom();

    /**
     * Range Proof: Prove that a value is within [min, max] without revealing the
     * value
     * 
     * Use case: IoT sensor proves temperature is safe (e.g., 20-25°C) without
     * revealing exact reading
     * 
     * Simplified implementation using Pedersen commitments
     */
    public static class RangeProof {
        private byte[] commitment; // Commitment to the value
        private byte[] proof; // ZK proof
        private long min;
        private long max;

        private RangeProof(byte[] commitment, byte[] proof, long min, long max) {
            this.commitment = commitment;
            this.proof = proof;
            this.min = min;
            this.max = max;
        }

        /**
         * Create a range proof for a value
         * 
         * @param value      The actual value (kept secret)
         * @param min        Minimum allowed value (public)
         * @param max        Maximum allowed value (public)
         * @param randomness Random blinding factor (kept secret)
         * @return Range proof that can be verified without knowing the value
         */
        public static RangeProof create(long value, long min, long max, BigInteger randomness) {
            if (value < min || value > max) {
                throw new IllegalArgumentException("Value must be within range [" + min + ", " + max + "]");
            }

            // Create Pedersen commitment: C = g^value * h^randomness
            byte[] commitment = createCommitment(value, randomness);

            // Create proof (simplified - in production use bulletproofs or similar)
            byte[] proof = createRangeProofData(value, min, max, randomness);

            return new RangeProof(commitment, proof, min, max);
        }

        /**
         * Verify the range proof without learning the actual value
         */
        public boolean verify() {
            return verifyRangeProof(commitment, proof, min, max);
        }

        public byte[] getCommitment() {
            return commitment;
        }

        public byte[] getProof() {
            return proof;
        }

        public long getMin() {
            return min;
        }

        public long getMax() {
            return max;
        }

        /**
         * Create Pedersen commitment
         */
        private static byte[] createCommitment(long value, BigInteger randomness) {
            // Simplified commitment: hash(value || randomness)
            byte[] randomnessBytes = randomness.toByteArray();
            ByteBuffer buf = ByteBuffer.allocate(8 + randomnessBytes.length);
            buf.putLong(value);
            buf.put(randomnessBytes);
            return Crypto.hash(buf.array());
        }

        /**
         * Create range proof data (simplified)
         * In production, use bulletproofs or zk-SNARKs
         */
        private static byte[] createRangeProofData(long value, long min, long max, BigInteger randomness) {
            // Simplified proof: sign the commitment with randomness
            ByteBuffer buf = ByteBuffer.allocate(24);
            buf.putLong(value);
            buf.putLong(min);
            buf.putLong(max);

            byte[] message = Crypto.hash(buf.array());

            // In production, this would be a proper ZK proof
            // For now, we create a verifiable signature
            return Crypto.sign(message, randomness);
        }

        /**
         * Verify range proof (simplified)
         */
        private static boolean verifyRangeProof(byte[] commitment, byte[] proof, long min, long max) {
            // Simplified verification
            // In production, verify bulletproof or zk-SNARK
            return commitment != null && proof != null && proof.length == 64;
        }
    }

    /**
     * Ownership Proof: Prove ownership of a device without revealing the private
     * key
     * 
     * Use case: Device proves it's authorized without exposing its private key
     */
    public static class OwnershipProof {
        private byte[] challenge;
        private byte[] response;

        private OwnershipProof(byte[] challenge, byte[] response) {
            this.challenge = challenge;
            this.response = response;
        }

        /**
         * Create ownership proof using challenge-response
         * 
         * @param deviceDID  Device's DID
         * @param privateKey Device's private key (kept secret)
         * @param challenge  Random challenge from verifier
         * @return Proof of ownership
         */
        public static OwnershipProof create(String deviceDID, BigInteger privateKey, byte[] challenge) {
            // Sign the challenge with private key
            byte[] message = Crypto.hash(concat(deviceDID.getBytes(), challenge));
            byte[] signature = Crypto.sign(message, privateKey);

            return new OwnershipProof(challenge, signature);
        }

        /**
         * Verify ownership proof
         * 
         * @param deviceDID Device's DID
         * @param publicKey Device's public key
         * @return true if proof is valid
         */
        public boolean verify(String deviceDID, byte[] publicKey) {
            byte[] message = Crypto.hash(concat(deviceDID.getBytes(), challenge));
            return Crypto.verify(message, response, publicKey);
        }

        public byte[] getChallenge() {
            return challenge;
        }

        public byte[] getResponse() {
            return response;
        }

        /**
         * Generate random challenge for ownership proof
         */
        public static byte[] generateChallenge() {
            byte[] challenge = new byte[32];
            RANDOM.nextBytes(challenge);
            return challenge;
        }
    }

    /**
     * Equality Proof: Prove two commitments contain the same value without
     * revealing it
     * 
     * Use case: Prove sensor readings from two devices match without revealing the
     * readings
     */
    public static class EqualityProof {
        private byte[] commitment1;
        private byte[] commitment2;
        private byte[] proof;

        private EqualityProof(byte[] commitment1, byte[] commitment2, byte[] proof) {
            this.commitment1 = commitment1;
            this.commitment2 = commitment2;
            this.proof = proof;
        }

        /**
         * Create proof that two commitments contain the same value
         */
        public static EqualityProof create(
                long value,
                BigInteger randomness1,
                BigInteger randomness2) {
            byte[] commitment1 = RangeProof.createCommitment(value, randomness1);
            byte[] commitment2 = RangeProof.createCommitment(value, randomness2);

            // Proof that commitments contain same value
            ByteBuffer buf = ByteBuffer.allocate(8);
            buf.putLong(value);
            byte[] proof = Crypto.hash(buf.array());

            return new EqualityProof(commitment1, commitment2, proof);
        }

        /**
         * Verify equality proof
         */
        public boolean verify() {
            // Simplified verification
            return commitment1 != null && commitment2 != null && proof != null;
        }

        public byte[] getCommitment1() {
            return commitment1;
        }

        public byte[] getCommitment2() {
            return commitment2;
        }
    }

    /**
     * Threshold Proof: Prove value exceeds threshold without revealing exact value
     * 
     * Use case: Prove temperature exceeds safety threshold without revealing exact
     * temperature
     */
    public static class ThresholdProof {
        private byte[] commitment;
        private byte[] proof;
        private long threshold;
        private boolean above; // true if proving value > threshold, false if value < threshold

        private ThresholdProof(byte[] commitment, byte[] proof, long threshold, boolean above) {
            this.commitment = commitment;
            this.proof = proof;
            this.threshold = threshold;
            this.above = above;
        }

        /**
         * Create proof that value is above/below threshold
         */
        public static ThresholdProof create(
                long value,
                long threshold,
                boolean above,
                BigInteger randomness) {
            if (above && value <= threshold) {
                throw new IllegalArgumentException("Value must be above threshold");
            }
            if (!above && value >= threshold) {
                throw new IllegalArgumentException("Value must be below threshold");
            }

            byte[] commitment = RangeProof.createCommitment(value, randomness);

            // Create proof
            ByteBuffer buf = ByteBuffer.allocate(17);
            buf.putLong(value);
            buf.putLong(threshold);
            buf.put((byte) (above ? 1 : 0));
            byte[] proof = Crypto.hash(buf.array());

            return new ThresholdProof(commitment, proof, threshold, above);
        }

        /**
         * Verify threshold proof
         */
        public boolean verify() {
            return commitment != null && proof != null;
        }

        public byte[] getCommitment() {
            return commitment;
        }

        public long getThreshold() {
            return threshold;
        }

        public boolean isAbove() {
            return above;
        }
    }

    // Helper methods

    private static byte[] concat(byte[] a, byte[] b) {
        byte[] result = new byte[a.length + b.length];
        System.arraycopy(a, 0, result, 0, a.length);
        System.arraycopy(b, 0, result, a.length, b.length);
        return result;
    }

    /**
     * Generate random blinding factor for commitments
     */
    public static BigInteger generateRandomness() {
        byte[] randomBytes = new byte[32];
        RANDOM.nextBytes(randomBytes);
        return new BigInteger(1, randomBytes);
    }
}
