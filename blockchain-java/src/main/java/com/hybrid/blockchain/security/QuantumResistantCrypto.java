package com.hybrid.blockchain.security;

import com.hybrid.blockchain.Crypto;
import org.bouncycastle.jcajce.spec.MLDSAParameterSpec;
import org.bouncycastle.jce.provider.BouncyCastleProvider;

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

    /** JCA names of the post-quantum signature scheme (ML-DSA, FIPS 204). */
    public static final String ALGORITHM = "ML-DSA";
    public static final String PROVIDER = "BC";

    static {
        // Register Bouncy Castle PQC provider
        // ML-DSA (FIPS 204, the standardised Dilithium) lives in the main BC provider; the
        // pre-standard "Dilithium"/BCPQC classes were removed in Bouncy Castle 1.8x
        if (Security.getProvider(PROVIDER) == null) {
            Security.addProvider(new BouncyCastleProvider());
        }
    }

    /**
     * Generate Dilithium key pair (quantum-resistant)
     * 
     * @param strength Security level (2, 3, or 5)
     * @return KeyPair with quantum-resistant keys
     */
    public static KeyPair generateDilithiumKeyPair(int strength) throws Exception {
        MLDSAParameterSpec spec;
        switch (strength) {
            case 2:
                spec = MLDSAParameterSpec.ml_dsa_44;   // Dilithium2 -> ML-DSA-44
                break;
            case 3:
                spec = MLDSAParameterSpec.ml_dsa_65;   // Dilithium3 -> ML-DSA-65
                break;
            case 5:
                spec = MLDSAParameterSpec.ml_dsa_87;   // Dilithium5 -> ML-DSA-87
                break;
            default:
                throw new IllegalArgumentException("Strength must be 2, 3, or 5");
        }

        KeyPairGenerator keyGen = KeyPairGenerator.getInstance(ALGORITHM, PROVIDER);
        keyGen.initialize(spec, new SecureRandom());
        return keyGen.generateKeyPair();
    }

    /**
     * Sign message with Dilithium (quantum-resistant)
     */
    public static byte[] signDilithium(byte[] message, PrivateKey privateKey) throws Exception {
        Signature signature = Signature.getInstance(ALGORITHM, PROVIDER);
        signature.initSign(privateKey);
        signature.update(message);
        return signature.sign();
    }

    /**
     * Verify Dilithium signature
     */
    public static boolean verifyDilithium(byte[] message, byte[] signature, PublicKey publicKey) throws Exception {
        Signature sig = Signature.getInstance(ALGORITHM, PROVIDER);
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

        /** [HC-040] Digest the ECDSA half of a hybrid signature is computed over. */
        static byte[] ecdsaDigest(byte[] message) {
            return Crypto.hashWithDomain("HC-HYBRID-SIG-v1", message);
        }

        /**
         * Create hybrid signature
         */
        public static HybridSignature sign(
                byte[] message,
                java.math.BigInteger ecdsaPrivateKey,
                PrivateKey dilithiumPrivateKey) throws Exception {
            // Classical ECDSA signature
            // [HC-040] ECDSA signs a domain-separated digest of the WHOLE message (Dilithium below
            // signs the message itself); previously ECDSA silently covered only its first 32 bytes.
            byte[] ecdsaSig = Crypto.sign(ecdsaDigest(message), ecdsaPrivateKey);

            // Quantum-resistant Dilithium signature
            byte[] dilithiumSig = signDilithium(message, dilithiumPrivateKey);

            return new HybridSignature(ecdsaSig, dilithiumSig);
        }

        /**
         * Verify hybrid signature (both must be valid)
         */
        public boolean verify(byte[] message, byte[] ecdsaPublicKey, PublicKey dilithiumPublicKey) throws Exception {
            // Verify ECDSA
            boolean ecdsaValid = Crypto.verify(ecdsaDigest(message), ecdsaSignature, ecdsaPublicKey);

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
        sha3.update(context.getBytes(java.nio.charset.StandardCharsets.UTF_8));
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
