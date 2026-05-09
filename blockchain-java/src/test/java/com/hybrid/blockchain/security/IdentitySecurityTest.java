package com.hybrid.blockchain.security;

import com.hybrid.blockchain.Crypto;
import org.junit.jupiter.api.*;
import static org.assertj.core.api.Assertions.*;

@Tag("severe")
public class IdentitySecurityTest {

    @Test
    @DisplayName("DID.1: Should correctly derive DID from address")
    void testAddressToDID() {
        String address = "hb0123456789abcdef0123456789abcdef01234567";
        String did = IdentityUtils.addressToDID(address);
        assertThat(did).isEqualTo("did:hybrid:0x" + address);
    }

    @Test
    @DisplayName("DID.2: Should reject malformed DIDs")
    void testInvalidDIDs() {
        assertThat(IdentityUtils.isValidDID("did:bitcoin:123")).isFalse();
        assertThat(IdentityUtils.isValidDID("did:hybrid:0x")).isFalse();
        assertThat(IdentityUtils.isValidDID("did:hybrid:0x123")).isFalse(); // Too short
        assertThat(IdentityUtils.isValidDID("did:hybrid:0xGHIJKL")).isFalse(); // Non-hex
        assertThat(IdentityUtils.isValidDID(null)).isFalse();
    }

    @Test
    @DisplayName("DID.3: Should extract address correctly from valid DID")
    void testExtractAddress() {
        String address = "0x" + "a".repeat(40);
        String did = "did:hybrid:" + address;
        assertThat(IdentityUtils.didToAddress(did)).isEqualTo(address);
    }

    @Test
    @DisplayName("DID.4: Adversarial: Attempting to use a non-Hybrid DID scheme")
    void testAdversarialDIDScheme() {
        String fakeDid = "did:eth:0xabcdefabcdefabcdefabcdefabcdefabcdefabcdef";
        assertThat(IdentityUtils.isValidDID(fakeDid)).isFalse();
    }
}
