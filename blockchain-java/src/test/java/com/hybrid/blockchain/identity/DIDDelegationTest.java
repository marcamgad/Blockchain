package com.hybrid.blockchain.identity;

import com.hybrid.blockchain.Crypto;
import org.junit.jupiter.api.*;
import java.math.BigInteger;
import static org.assertj.core.api.Assertions.*;

@Tag("severe")
public class DIDDelegationTest {

    private SSIManager ssi;
    private BigInteger masterKey;
    private String masterDid;
    private String delegateDid;

    @BeforeEach
    void setUp() {
        ssi = new SSIManager();
        masterKey = BigInteger.valueOf(1001);
        byte[] masterPub = Crypto.derivePublicKey(masterKey);
        masterDid = ssi.registerDID("master_device", masterPub, "owner1");
        
        byte[] delegatePub = Crypto.derivePublicKey(BigInteger.valueOf(1002));
        delegateDid = ssi.registerDID("delegate_device", delegatePub, "owner1");
    }

    @Test
    @DisplayName("DEL.1: Should authorize delegate with master signature")
    void testDelegation() {
        byte[] msg = ("delegate:" + masterDid + ":" + delegateDid).getBytes();
        byte[] sig = Crypto.sign(msg, masterKey);
        
        ssi.addDelegate(masterDid, delegateDid, sig);
        
        assertThat(ssi.isDelegate(masterDid, delegateDid)).isTrue();
        assertThat(ssi.isDelegate(masterDid, "other_did")).isFalse();
    }

    @Test
    @DisplayName("DEL.2: Should reject delegation with invalid signature")
    void testInvalidDelegation() {
        byte[] msg = ("delegate:" + masterDid + ":" + delegateDid).getBytes();
        byte[] invalidSig = Crypto.sign(msg, BigInteger.valueOf(9999));
        
        assertThatThrownBy(() -> ssi.addDelegate(masterDid, delegateDid, invalidSig))
                .isInstanceOf(SecurityException.class);
    }
}
