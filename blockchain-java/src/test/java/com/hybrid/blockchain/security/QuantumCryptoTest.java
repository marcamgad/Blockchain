package com.hybrid.blockchain.security;

import com.hybrid.blockchain.Crypto;
import org.junit.jupiter.api.*;
import java.security.KeyPair;
import static org.assertj.core.api.Assertions.*;

@Tag("Security")
public class QuantumCryptoTest {

    @Test
    @DisplayName("C3.1: Dilithium Sign and Verify")
    public void testDilithiumSignAndVerify() throws Exception {
        KeyPair kp = QuantumResistantCrypto.generateDilithiumKeyPair(3);
        byte[] msg = "IoT telemetry block".getBytes();
        byte[] sig = QuantumResistantCrypto.signDilithium(msg, kp.getPrivate());
        
        boolean valid = QuantumResistantCrypto.verifyDilithium(msg, sig, kp.getPublic());
        assertThat(valid).isTrue();
    }

    @Test
    @DisplayName("C3.2: Dilithium Tampered Message Rejected")
    public void testDilithiumTamperedMessageRejected() throws Exception {
        KeyPair kp = QuantumResistantCrypto.generateDilithiumKeyPair(3);
        byte[] msg = "IoT telemetry block".getBytes();
        byte[] sig = QuantumResistantCrypto.signDilithium(msg, kp.getPrivate());
        
        // Tamper with signature
        sig[0] ^= 0xFF;
        
        boolean valid = QuantumResistantCrypto.verifyDilithium(msg, sig, kp.getPublic());
        assertThat(valid).isFalse();
    }

    @Test
    @DisplayName("C3.3: Hybrid Signature Roundtrip")
    public void testHybridSignatureRoundtrip() throws Exception {
        byte[] msg = "IoT telemetry block".getBytes();
        
        // Keys
        java.math.BigInteger ecdsaPrivKey = new java.math.BigInteger("abc123456789", 16);
        byte[] ecdsaPubKey = Crypto.derivePublicKey(ecdsaPrivKey);
        KeyPair dilithiumKp = QuantumResistantCrypto.generateDilithiumKeyPair(3);

        // Sign
        QuantumResistantCrypto.HybridSignature sig = QuantumResistantCrypto.HybridSignature.sign(
                msg, ecdsaPrivKey, dilithiumKp.getPrivate());
        
        // Verify (Correct keys)
        boolean valid = sig.verify(msg, ecdsaPubKey, dilithiumKp.getPublic());
        assertThat(valid).isTrue();

        // Verify (Wrong ECDSA key)
        java.math.BigInteger wrongEcdsaPriv = new java.math.BigInteger("deadbeef", 16);
        byte[] wrongEcdsaPub = Crypto.derivePublicKey(wrongEcdsaPriv);
        boolean invalid = sig.verify(msg, wrongEcdsaPub, dilithiumKp.getPublic());
        assertThat(invalid).isFalse();
    }
}
