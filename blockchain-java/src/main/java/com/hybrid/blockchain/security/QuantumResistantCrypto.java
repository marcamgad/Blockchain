package com.hybrid.blockchain.security;

import com.hybrid.blockchain.Crypto;
import org.bouncycastle.pqc.jcajce.provider.BouncyCastlePQCProvider;
import org.bouncycastle.pqc.jcajce.spec.DilithiumParameterSpec;

import java.security.*;
import java.security.spec.X509EncodedKeySpec;
import java.util.Arrays;

/**
 * Quantum-Resistant Cryptography Support
 * 
 * Implements post-quantum cryptographic algorithms to protect against
 * quantum computer attacks:
 * 
 * - CRYSTALS-Dilithium: Digital signatures (NIST PQC standard)
 * - Hybrid mode: Combines classical ECDSA with quantum-resistant Dilithium
 * 
 * Use cases:
 * - Long-term security for critical operations
 * - Future-proof device identities
 * - Protection against quantum threats
 * 
 * Note: This is a forward-looking implementation. Full quantum computers
 * don't exist yet, but preparing now ensures long-term security.
 */
public class QuantumResistantCrypto {

    static {
        // Register Bouncy Castle PQC provider
        if (Security.getProvider("BCPQC") == null) {
            Security.addProvider(new BouncyCastlePQCProvider());
        }
    }

    /**
     * Generate Dilithium key pair (quantum-resistant)
     * 
     * @param strength Security level (2, 3, or 5)
     * @return KeyPair with quantum-resistant keys
     */
    public static KeyPair generateDilithiumKeyPair(int strength) throws Exception {
        DilithiumParameterSpec spec;
        switch (strength) {
            case 2:
                spec = DilithiumParameterSpec.dilithium2;
                break;
            case 3:
                spec = DilithiumParameterSpec.dilithium3;
                break;
            case 5:
                spec = DilithiumParameterSpec.dilithium5;
                break;
            default:
                throw new IllegalArgumentException("Strength must be 2, 3, or 5");
        }

        KeyPairGenerator keyGen = KeyPairGenerator.getInstance("Dilithium", "BCPQC");
        keyGen.initialize(spec, new SecureRandom());
        return keyGen.generateKeyPair();
    }

    /**
     * Sign message with Dilithium (quantum-resistant)
     */
    public static byte[] signDilithium(byte[] message, PrivateKey privateKey) throws Exception {
        Signature signature = Signature.getInstance("Dilithium", "BCPQC");
        signature.initSign(privateKey);
        signature.update(message);
        return signature.sign();
    }

    /**
     * Verify Dilithium signature
     */
    public static boolean verifyDilithium(byte[] message, byte[] signature, PublicKey publicKey) throws Exception {
        Signature sig = Signature.getInstance("Dilithium", "BCPQC");
        sig.initVerify(publicKey);
        sig.update(message);
        return sig.verify(signature);
    }

    /**
     * Hybrid signature: Combines classical ECDSA with quantum-resistant Dilithium
     * 
     * This provides:
     * - Current security with ECDSA
     * - Future security with Dilithium
     * - Smooth migration path
     */
    public static class HybridSignature {
        private final byte[] ecdsaSignature;
        private final byte[] dilithiumSignature;

        public HybridSignature(byte[] ecdsaSignature, byte[] dilithiumSignature) {
            this.ecdsaSignature = ecdsaSignature;
            this.dilithiumSignature = dilithiumSignature;
        }

        /**
         * Create hybrid signature
         */
        public static HybridSignature sign(
                byte[] message,
                java.math.BigInteger ecdsaPrivateKey,
                PrivateKey dilithiumPrivateKey) throws Exception {
            // Classical ECDSA signature
            byte[] ecdsaSig = Crypto.sign(message, ecdsaPrivateKey);

            // Quantum-resistant Dilithium signature
            byte[] dilithiumSig = signDilithium(message, dilithiumPrivateKey);

            return new HybridSignature(ecdsaSig, dilithiumSig);
        }

        /**
         * Verify hybrid signature (both must be valid)
         */
        public boolean verify(byte[] message, byte[] ecdsaPublicKey, PublicKey dilithiumPublicKey) throws Exception {
            // Verify ECDSA
            boolean ecdsaValid = Crypto.verify(message, ecdsaSignature, ecdsaPublicKey);

            // Verify Dilithium
            boolean dilithiumValid = verifyDilithium(message, dilithiumSignature, dilithiumPublicKey);

            // Both must be valid
            return ecdsaValid && dilithiumValid;
        }

        /**
         * Serialize to bytes
         */
        public byte[] toBytes() {
            byte[] result = new byte[4 + ecdsaSignature.length + 4 + dilithiumSignature.length];
            int offset = 0;

            // ECDSA signature length + data
            result[offset++] = (byte) (ecdsaSignature.length >> 24);
            result[offset++] = (byte) (ecdsaSignature.length >> 16);
            result[offset++] = (byte) (ecdsaSignature.length >> 8);
            result[offset++] = (byte) ecdsaSignature.length;
            System.arraycopy(ecdsaSignature, 0, result, offset, ecdsaSignature.length);
            offset += ecdsaSignature.length;

            // Dilithium signature length + data
            result[offset++] = (byte) (dilithiumSignature.length >> 24);
            result[offset++] = (byte) (dilithiumSignature.length >> 16);
            result[offset++] = (byte) (dilithiumSignature.length >> 8);
            result[offset++] = (byte) dilithiumSignature.length;
            System.arraycopy(dilithiumSignature, 0, result, offset, dilithiumSignature.length);

            return result;
        }

        /**
         * Deserialize from bytes
         */
        public static HybridSignature fromBytes(byte[] data) {
            int offset = 0;

            // Read ECDSA signature
            int ecdsaLen = ((data[offset++] & 0xFF) << 24) |
                    ((data[offset++] & 0xFF) << 16) |
                    ((data[offset++] & 0xFF) << 8) |
                    (data[offset++] & 0xFF);
            byte[] ecdsaSig = Arrays.copyOfRange(data, offset, offset + ecdsaLen);
            offset += ecdsaLen;

            // Read Dilithium signature
            int dilithiumLen = ((data[offset++] & 0xFF) << 24) |
                    ((data[offset++] & 0xFF) << 16) |
                    ((data[offset++] & 0xFF) << 8) |
                    (data[offset++] & 0xFF);
            byte[] dilithiumSig = Arrays.copyOfRange(data, offset, offset + dilithiumLen);

            return new HybridSignature(ecdsaSig, dilithiumSig);
        }

        public byte[] getEcdsaSignature() {
            return ecdsaSignature;
        }

        public byte[] getDilithiumSignature() {
            return dilithiumSignature;
        }
    }

    /**
     * Quantum-resistant key derivation
     * 
     * Uses SHA3-512 (quantum-resistant hash) for key derivation
     */
    public static byte[] deriveQuantumResistantKey(byte[] seed, String context) throws Exception {
        MessageDigest sha3 = MessageDigest.getInstance("SHA3-512", "BC");
        sha3.update(context.getBytes());
        sha3.update(seed);
        return sha3.digest();
    }

    /**
     * Get security level description
     */
    public static String getSecurityLevel(int strength) {
        switch (strength) {
            case 2:
                return "Dilithium2: ~128-bit quantum security (equivalent to AES-128)";
            case 3:
                return "Dilithium3: ~192-bit quantum security (equivalent to AES-192)";
            case 5:
                return "Dilithium5: ~256-bit quantum security (equivalent to AES-256)";
            default:
                return "Unknown";
        }
    }

    /**
     * Migration helper: Check if quantum-resistant upgrade is needed
     */
    public static boolean needsQuantumUpgrade(long keyGenerationTime) {
        // If key is older than 5 years, recommend upgrade
        long fiveYears = 5L * 365 * 24 * 60 * 60 * 1000;
        return (System.currentTimeMillis() - keyGenerationTime) > fiveYears;
    }
}
