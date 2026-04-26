package com.hybrid.blockchain;

import org.junit.jupiter.api.*;
import static org.assertj.core.api.Assertions.*;
import java.nio.charset.StandardCharsets;

/**
 * Unit tests for cryptographic primitives including SHA-256 hashing,
 * ECDSA signing/verification, and account address derivation.
 */
@Tag("unit")
public class CryptoCompleteTest {

    @Test
    @DisplayName("C1.1-1.2 — Deterministic and distinct hashing")
    void testHashing() {
        byte[] input1 = "hello".getBytes(StandardCharsets.UTF_8);
        byte[] input2 = "world".getBytes(StandardCharsets.UTF_8);
        
        byte[] hash1a = Crypto.hash(input1);
        byte[] hash1b = Crypto.hash(input1);
        byte[] hash2 = Crypto.hash(input2);
        
        assertThat(hash1a).containsExactly(hash1b);
        assertThat(hash1a).as("Different inputs must have different hashes").isNotEqualTo(hash2);
        assertThat(hash1a.length).isEqualTo(32); // SHA-256
    }

    @Test
    @DisplayName("C1.3-1.6 — ECDSA sign and verify")
    void testSigning() {
        TestKeyPair keys = new TestKeyPair(1);
        byte[] message = "secure transaction".getBytes(StandardCharsets.UTF_8);
        byte[] signature = Crypto.sign(message, keys.getPrivateKey());
        
        assertThat(Crypto.verify(message, signature, keys.getPublicKey()))
                .as("Signature should be valid for matching keys").isTrue();
        
        // C1.4: wrong public key
        TestKeyPair other = new TestKeyPair(2);
        assertThat(Crypto.verify(message, signature, other.getPublicKey()))
                .as("Signature should fail for wrong public key").isFalse();
                
        // C1.5: tampered message
        byte[] tampered = "secure transaction!".getBytes(StandardCharsets.UTF_8);
        assertThat(Crypto.verify(tampered, signature, keys.getPublicKey()))
                .as("Signature should fail for tampered message").isFalse();
                
        // C1.6: tampered signature
        signature[0] ^= 0xFF;
        assertThat(Crypto.verify(message, signature, keys.getPublicKey()))
                .as("Signature should fail for tampered signature bytes").isFalse();
    }

    @Test
    @DisplayName("C1.7-1.9 — Account derivation")
    void testAccountDerivation() {
        TestKeyPair keys = new TestKeyPair(1);
        
        // C1.7: Consistent public key
        byte[] pubKey = Crypto.derivePublicKey(keys.getPrivateKey());
        assertThat(pubKey).containsExactly(keys.getPublicKey());
        
        // C1.8: Consistent address
        String address = Crypto.deriveAddress(pubKey);
        assertThat(address).isEqualTo(keys.getAddress());
        assertThat(address).startsWith("hb");
        
        // C1.9: Distinct addresses
        TestKeyPair other = new TestKeyPair(777);
        assertThat(Crypto.deriveAddress(other.getPublicKey())).isNotEqualTo(address);
    }

    @Test
    @DisplayName("C1.10 — Hex utilities")
    void testHexUtils() {
        byte[] data = { 0x00, 0x01, (byte)0xFF, (byte)0xAB };
        String hex = HexUtils.encode(data);
        assertThat(hex).isEqualTo("0001ffab");
        
        byte[] restored = HexUtils.decode(hex);
        assertThat(restored).containsExactly(data);
    }

    @Test
    @DisplayName("C1.12-1.14 — Transaction signing and verification")
    void testTransactionCrypto() {
        TestKeyPair sender = new TestKeyPair(42);
        Transaction tx = new Transaction.Builder()
                .type(Transaction.Type.ACCOUNT)
                .from(sender.getAddress())
                .to("recipient")
                .amount(100L)
                .fee(10L)
                .nonce(1L)
                .sign(sender.getPrivateKey(), sender.getPublicKey());
                
        assertThat(tx.getSignature()).isNotNull();
        assertThat(tx.getPublicKey()).containsExactly(sender.getPublicKey());
        assertThat(tx.verify()).as("Transaction verification should succeed").isTrue();
        
        // C1.13: tampered amount
        Transaction tampered = new Transaction.Builder()
                .type(Transaction.Type.ACCOUNT)
                .from(sender.getAddress())
                .to("recipient")
                .amount(101L) // Tampered
                .fee(10L)
                .nonce(1L)
                .publicKey(tx.getPublicKey())
                .signature(tx.getSignature())
                .build();
        assertThat(tampered.verify()).as("Verification should fail for tampered message content").isFalse();
        
        // C1.14: wrong from address
        Transaction wrongFrom = new Transaction.Builder()
                .type(Transaction.Type.ACCOUNT)
                .from("0xHACK") // Does not match derived address from pubKey
                .to("recipient")
                .amount(100L)
                .fee(10L)
                .nonce(1L)
                .publicKey(tx.getPublicKey())
                .signature(tx.getSignature())
                .build();
        assertThat(wrongFrom.verify()).as("Verification should fail if 'from' does not match publicKey").isFalse();
    }
}
