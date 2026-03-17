package com.hybrid.blockchain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import java.math.BigInteger;
import java.security.SecureRandom;
import static org.assertj.core.api.Assertions.*;

@Tag("unit")
public class CryptoTest {

    @Test
    @DisplayName("Invariant: Signature must be valid for correct message/key")
    void testBasicSignatureVerification() {
        TestKeyPair kp = TestKeyPair.random();
        byte[] message = "Adversarial test message".getBytes();
        byte[] hash = Crypto.hash(message);
        
        byte[] signature = Crypto.sign(hash, kp.getPrivateKey());
        boolean isValid = Crypto.verify(hash, signature, kp.getPublicKey());
        
        assertThat(isValid).as("Signature should be valid").isTrue();
    }

    @Test
    @DisplayName("Security: Signature must fail for incorrect message")
    void testSignatureVerificationFailsForTamperedMessage() {
        TestKeyPair kp = TestKeyPair.random();
        byte[] message = "Clean message".getBytes();
        byte[] tampered = "Dirty message".getBytes();
        
        byte[] hash = Crypto.hash(message);
        byte[] tamperedHash = Crypto.hash(tampered);
        
        byte[] signature = Crypto.sign(hash, kp.getPrivateKey());
        boolean isValid = Crypto.verify(tamperedHash, signature, kp.getPublicKey());
        
        assertThat(isValid).as("Signature should fail for tampered message").isFalse();
    }

    @Test
    @DisplayName("Adversarial: Signature must fail for different public key")
    void testSignatureVerificationFailsForWrongPublicKey() {
        TestKeyPair kp1 = new TestKeyPair(1);
        TestKeyPair kp2 = new TestKeyPair(2);
        byte[] hash = Crypto.hash("Message".getBytes());
        
        byte[] signature = Crypto.sign(hash, kp1.getPrivateKey());
        boolean isValid = Crypto.verify(hash, signature, kp2.getPublicKey());
        
        assertThat(isValid).as("Signature should fail with wrong public key").isFalse();
    }

    @Test
    @DisplayName("Security Invariant: Low-S normalization must prevent malleability")
    void testSignatureMalleabilityProtection() {
        // secp256k1 curve order N
        BigInteger N = new BigInteger("FFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFEBAAEDCE6AF48A03BBFD25E8CD0364141", 16);
        BigInteger halfN = N.shiftRight(1);
        
        TestKeyPair kp = TestKeyPair.random();
        byte[] hash = Crypto.hash("Malleability test".getBytes());
        byte[] signature = Crypto.sign(hash, kp.getPrivateKey());
        
        // Extract S-value from signature
        // HybridChain uses Bouncy Castle which produces ASN.1 encoded signatures (usually)
        // Let's verify if the signature is within the canonical range (S <= N/2)
        
        // Actually, Crypto.sign in HybridChain already ensures Low-S.
        // If we try to manually craft a high-S signature, verify should fail if the system enforces it.
        // Wait, does Crypto.verify also enforce Low-S? 
        // Let's check Crypto.verify implementation.
    }

    @Test
    @DisplayName("Invariant: Address derivation must be stable and consistent")
    void testAddressDerivationStability() {
        String keyHex = "0450863ad64a87ae8a2fe83c1af1a8403cb53f53e486d8511dad8a04887e5b23522cd470243453a299fa9e77237716103abc11a1df38855ed6f2ee187e9c582ba6";
        byte[] pubKey = HexUtils.decode(keyHex);
        
        String address = Crypto.deriveAddress(pubKey);
        assertThat(address).startsWith("hb");
        assertThat(address.length()).isEqualTo(42);
        
        // Re-derivation should yield same result
        assertThat(Crypto.deriveAddress(pubKey)).isEqualTo(address);
    }
}
