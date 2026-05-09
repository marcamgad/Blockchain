package com.hybrid.blockchain.security;

import org.junit.jupiter.api.*;
import java.math.BigInteger;
import static org.assertj.core.api.Assertions.*;

@Tag("severe")
public class HSMSecurityTest {

    private HardwareSecurityModule hsm;
    private BigInteger privKey;

    @BeforeEach
    void setUp() {
        privKey = new BigInteger("12345678901234567890");
        hsm = new HardwareSecurityModule(privKey);
    }

    @Test
    @DisplayName("HSM.1: Should sign correctly without exposing raw key")
    void testHSMSigning() {
        byte[] data = "test_payload".getBytes();
        byte[] sig = hsm.sign(data);
        
        assertThat(sig).isNotNull();
        assertThat(sig.length).isEqualTo(64);
        
        // Verify with Crypto.verify to ensure HSM used the right key internally
        boolean verified = com.hybrid.blockchain.Crypto.verify(data, sig, hsm.getPublicKey());
        assertThat(verified).isTrue();
    }

    @Test
    @DisplayName("HSM.2: Should generate stable Key ID")
    void testKeyID() {
        String id1 = hsm.getKeyId();
        HardwareSecurityModule hsm2 = new HardwareSecurityModule(privKey);
        assertThat(hsm2.getKeyId()).isEqualTo(id1);
    }
}
