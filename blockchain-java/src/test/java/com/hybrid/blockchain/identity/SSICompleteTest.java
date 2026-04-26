package com.hybrid.blockchain.identity;

import com.hybrid.blockchain.*;
import com.hybrid.blockchain.testutil.*;
import org.junit.jupiter.api.*;
import static org.assertj.core.api.Assertions.*;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * Integration tests for Self-Sovereign Identity (SSI) and DIDs.
 * Covers DID creation, document resolution, and Verifiable Credential integrity.
 */
@Tag("identity")
public class SSICompleteTest {

    private SSIManager ssiManager;

    @BeforeEach
    void setUp() throws Exception {
        ssiManager = new SSIManager();
    }

    @Test
    @DisplayName("SSI1.1-1.2 — DID creation and resolution")
    void testDidLifecycle() throws Exception {
        TestKeyPair keys = new TestKeyPair(1);
        
        String did = ssiManager.registerDID("device-1", keys.getPublicKey(), "owner-1");
        assertThat(did).startsWith("did:hybrid:");
        
        DecentralizedIdentifier doc = ssiManager.resolveDID(did);
        assertThat(doc).isNotNull();
        assertThat(doc.getDid()).isEqualTo(did);
        assertThat(doc.getPublicKey()).isEqualTo(keys.getPublicKey());
    }

    @Test
    @DisplayName("SSI1.3-1.5 — Verifiable Credentials")
    void testVerifiableCredentials() throws Exception {
        TestKeyPair issuer = new TestKeyPair(100);
        TestKeyPair subject = new TestKeyPair(101);
        
        String issuerDID = ssiManager.registerDID("issuer", issuer.getPublicKey(), issuer.getAddress());
        String subjectDID = ssiManager.registerDID("subject", subject.getPublicKey(), subject.getAddress());
        
        Map<String, Object> claims = new HashMap<>();
        claims.put("role", "admin");
        
        VerifiableCredential vc = ssiManager.issueCredential(
                issuerDID, subjectDID, claims, issuer.getPrivateKey(), issuer.getPublicKey());
        
        assertThat(vc.getIssuer()).isEqualTo(issuerDID);
        assertThat(vc.getCredentialSubject().getId()).isEqualTo(subjectDID);
        assertThat(vc.getCredentialSubject().getClaim("role")).isEqualTo("admin");
        
        // SSI1.4: Verify
        assertThat(vc.verify(issuer.getPublicKey())).isTrue();
        
        // SSI1.5: Tamper
        vc.getCredentialSubject().getClaims().put("role", "superuser");
        assertThat(vc.verify(issuer.getPublicKey())).as("Tampered claim should fail verification").isFalse();
    }

    @Test
    @DisplayName("SSI1.6 — Revocation")
    void testRevocation() throws Exception {
        TestKeyPair issuer = new TestKeyPair(1);
        String did = ssiManager.registerDID("revoked-device", issuer.getPublicKey(), issuer.getAddress());
        
        ssiManager.revokeDID(did, "compromised");
        assertThat(ssiManager.isDIDRevoked(did)).isTrue();
        assertThatThrownBy(() -> ssiManager.resolveDID(did))
            .isInstanceOf(SecurityException.class)
            .hasMessageContaining("revoked");
    }
}
