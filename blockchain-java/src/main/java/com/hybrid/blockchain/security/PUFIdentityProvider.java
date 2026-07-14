package com.hybrid.blockchain.security;

import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.security.MessageDigest;

/**
 * Physically Unclonable Function (PUF) Identity Provider.
 *
 * <p>Implements PUF-based key derivation for HybridChain.
 * A PUF response (silicon fingerprint) is used as a high-entropy seed to
 * deterministically derive an EC private key.
 *
 * @paper RWA-BFT Sensors 2025, DOI:10.3390/s25020413
 */
public class PUFIdentityProvider {

    private static final BigInteger N = new BigInteger(
            "FFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFEBAAEDCE6AF48A03BBFD25E8CD0364141", 16);

    /**
     * Derives a deterministic EC private key from a raw PUF response.
     *
     * @param pufResponse the silicon fingerprint response (high entropy)
     * @return the derived EC private key scalar
     */
    public static BigInteger derivePrivateKey(byte[] pufResponse) {
        if (pufResponse == null || pufResponse.length < 32) {
            throw new IllegalArgumentException("PUF response must be at least 256 bits");
        }
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            md.update("PUF_KEY_DERIVATION_TAG_V1".getBytes());
            byte[] hash = md.digest(pufResponse);
            
            // Map the hash into the secp256k1 scalar field [1, n-1]
            BigInteger d = new BigInteger(1, hash).mod(N.subtract(BigInteger.ONE)).add(BigInteger.ONE);
            return d;
        } catch (Exception e) {
            throw new RuntimeException("Key derivation failed", e);
        }
    }

    /**
     * Computes a one-way identity anchor from a PUF response, for on-chain anchoring.
     *
     * <p>Uses a domain tag <b>distinct</b> from {@link #derivePrivateKey} so the public
     * anchor can never be used to reconstruct (or brute-check against) the derived private
     * key. Anchoring {@code computeAnchor(response)} on the ledger at provisioning makes a
     * device's identity tamper-evident: a cloned or spoofed device presenting a different
     * silicon fingerprint will produce a different anchor and fail verification.
     *
     * @param pufResponse the silicon fingerprint response
     * @return a 32-byte anchor commitment
     */
    public static byte[] computeAnchor(byte[] pufResponse) {
        if (pufResponse == null || pufResponse.length < 32) {
            throw new IllegalArgumentException("PUF response must be at least 256 bits");
        }
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            md.update("PUF_ANCHOR_TAG_V1".getBytes());
            return md.digest(pufResponse);
        } catch (Exception e) {
            throw new RuntimeException("Anchor computation failed", e);
        }
    }

    /**
     * Simulated PUF response for a given device hardware ID.
     * In production, this would be a hardware call to the SRAM-PUF/Ring-Oscillator.
     */
    public static byte[] getSimulatedPUFResponse(String deviceHardwareId) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            md.update("PUF_SIMULATION_SALT".getBytes());
            md.update(deviceHardwareId.getBytes());
            return md.digest();
        } catch (Exception e) {
            return new byte[32];
        }
    }
}
