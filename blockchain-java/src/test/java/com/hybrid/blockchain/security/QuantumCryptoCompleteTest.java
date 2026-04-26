package com.hybrid.blockchain.security;

import com.hybrid.blockchain.Crypto;
import com.hybrid.blockchain.TestKeyPair;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.assertj.core.api.Assertions.*;
import java.security.*;

/**
 * Unit tests for Quantum Resistant Cryptography (Dilithium and Hybrid signatures).
 */
@Tag("security")
public class QuantumCryptoCompleteTest {

    @ParameterizedTest
    @ValueSource(ints = {2, 3, 5})
    @DisplayName("QC1.1 — Dilithium pair generation")
    void testDilithiumKeyGen(int strength) throws Exception {
        KeyPair kp = QuantumResistantCrypto.generateDilithiumKeyPair(strength);
        assertThat(kp.getPublic()).isNotNull();
        assertThat(kp.getPrivate()).isNotNull();
    }

    @Test
    @DisplayName("QC1.2-1.4 — Dilithium sign and verify")
    void testDilithiumSigning() throws Exception {
        KeyPair kp = QuantumResistantCrypto.generateDilithiumKeyPair(3);
        byte[] msg = "quantum-safe hello".getBytes();
        byte[] sig = QuantumResistantCrypto.signDilithium(msg, kp.getPrivate());
        
        assertThat(QuantumResistantCrypto.verifyDilithium(msg, sig, kp.getPublic())).isTrue();
        
        // QC1.3: Tamper msg
        assertThat(QuantumResistantCrypto.verifyDilithium("tampered".getBytes(), sig, kp.getPublic())).isFalse();
        // QC1.4: Wrong key
        PublicKey otherPub = QuantumResistantCrypto.generateDilithiumKeyPair(3).getPublic();
        assertThat(QuantumResistantCrypto.verifyDilithium(msg, sig, otherPub)).isFalse();
    }

    @Test
    @DisplayName("QC1.5-1.8 — Hybrid signatures")
    void testHybridSignatures() throws Exception {
        byte[] msg = "hybrid message".getBytes();
        TestKeyPair ecdsaKeys = new TestKeyPair(1);
        java.math.BigInteger ecdsaPriv = ecdsaKeys.getPrivateKey();
        byte[] ecdsaPub = ecdsaKeys.getPublicKey();
        KeyPair dkp = QuantumResistantCrypto.generateDilithiumKeyPair(3);
        
        QuantumResistantCrypto.HybridSignature sig = QuantumResistantCrypto.HybridSignature.sign(msg, ecdsaPriv, dkp.getPrivate());
        
        assertThat(sig.getEcdsaSignature()).isNotNull();
        assertThat(sig.getDilithiumSignature()).isNotNull();
        
        assertThat(sig.verify(msg, ecdsaPub, dkp.getPublic())).isTrue();
        
        // QC1.7-1.8: Tampering
        QuantumResistantCrypto.HybridSignature tampered = new QuantumResistantCrypto.HybridSignature(new byte[64], sig.getDilithiumSignature());
        assertThat(tampered.verify(msg, ecdsaPub, dkp.getPublic())).isFalse();
    }

    @Test
    @DisplayName("QC1.9 — Binary round-trip")
    void testHybridBinaryRoundTrip() {
        QuantumResistantCrypto.HybridSignature sig = new QuantumResistantCrypto.HybridSignature(new byte[64], new byte[2048]);
        byte[] bytes = sig.toBytes();
        QuantumResistantCrypto.HybridSignature restored = QuantumResistantCrypto.HybridSignature.fromBytes(bytes);
        assertThat(restored.getEcdsaSignature()).containsExactly(sig.getEcdsaSignature());
        assertThat(restored.getDilithiumSignature()).containsExactly(sig.getDilithiumSignature());
    }

    @Test
    @DisplayName("QC1.11-1.12 — Logic: needsQuantumUpgrade")
    void testUpgradeLogic() {
        long fiveYearsAgoMs = System.currentTimeMillis() - (5L * 365 * 24 * 60 * 60 * 1000) - 1000;
        assertThat(QuantumResistantCrypto.needsQuantumUpgrade(System.currentTimeMillis())).isFalse();
        assertThat(QuantumResistantCrypto.needsQuantumUpgrade(fiveYearsAgoMs)).isTrue();
    }

    @Test
    @DisplayName("QC1.13 — Transaction pipeline wiring (Stub)")
    @Disabled("Requires Fix 2 — quantum wiring into transaction validation pipeline")
    void testTransactionQuantumWiring() {
        // This test will verify that Blockchain.validateTransaction() actually calls verifyHybrid()
    }
}
