package com.hybrid.blockchain.security;

import com.hybrid.blockchain.*;
import com.hybrid.blockchain.testutil.*;
import org.junit.jupiter.api.*;
import static org.assertj.core.api.Assertions.*;
import java.util.List;

/**
 * Unit and integration tests for Multi-Signature wallet and governance logic.
 */
@Tag("security")
public class MultiSigCompleteTest {

    private TestBlockchain tb;
    private MultiSigManager manager;

    @BeforeEach
    void setUp() throws Exception {
        tb = new TestBlockchain();
        manager = new MultiSigManager();
        manager.setBlockchain(tb.getBlockchain());
    }

    @AfterEach
    void tearDown() throws Exception {
        if (tb != null) tb.close();
    }

    @Test
    @DisplayName("MS1.1 — Create wallet")
    void testCreateWallet() {
        List<String> owners = List.of("A", "B", "C");
        String walletId = manager.createWallet(owners, 2);
        
        assertThat(walletId).isNotNull();
        MultiSigManager.Wallet w = manager.getWallet(walletId);
        assertThat(w.getOwners()).containsAll(owners);
        assertThat(w.getThreshold()).isEqualTo(2);
    }

    @Test
    @DisplayName("MS1.2-1.7 — Proposal lifecycle")
    void testProposalExecution() {
        TestKeyPair owner1 = new TestKeyPair(1);
        TestKeyPair owner2 = new TestKeyPair(2);
        TestKeyPair owner3 = new TestKeyPair(3);
        
        String walletId = manager.createWallet(List.of(owner1.getAddress(), owner2.getAddress(), owner3.getAddress()), 2);
        
        String propId = manager.proposeTransaction(walletId, "A_CALL", "data".getBytes());
        assertThat(propId).isNotNull();
        
        // 1st sign
        approve(propId, owner1);
        assertThat(manager.getProposal(propId).getSignatureCount()).isEqualTo(1);
        assertThat(manager.getProposal(propId).isExecuted()).isFalse();
        
        // MS1.5: double sign attempt from same owner is refused and does not increment
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> approve(propId, owner1))
                .isInstanceOf(IllegalStateException.class);
        assertThat(manager.getProposal(propId).getSignatureCount()).as("Should not increment for duplicate owner").isEqualTo(1);
        
        // 2nd sign (reaches threshold)
        approve(propId, owner2);
        assertThat(manager.getProposal(propId).isExecuted()).as("Should execute at 2/3 threshold").isTrue();
    }

    @Test
    @DisplayName("MS1.8 — ADD_VALIDATOR governance")
    void testAddValidatorProposal() throws Exception {
        TestKeyPair admin = new TestKeyPair(100);
        String walletId = manager.createWallet(List.of(admin.getAddress()), 1);
        
        TestKeyPair newValidator = new TestKeyPair(777);
        String metadata = "ADD_VALIDATOR:" + newValidator.getAddress() + ":" + HexUtils.encode(newValidator.getPublicKey());
        
        String propId = manager.proposeTransaction(walletId, "GOV", metadata.getBytes());
        approve(propId, admin);
        
        assertThat(tb.getBlockchain().getConsensus().getValidators()).anyMatch(v -> v.getId().equals(newValidator.getAddress()));
    }

    @Test
    @DisplayName("MS1.10 — [HC-086] PARAMETER_CHANGE does not change a network parameter on this node")
    void testParamChangeProposal() {
        // This test used to assert the opposite (TARGET_BLOCK_TIME_MS became 5000). The proposal is
        // node-local state, so applying it changed one node's parameter; parameters are final now
        // and change only through a protocol upgrade.
        TestKeyPair admin = new TestKeyPair(1);
        String walletId = manager.createWallet(List.of(admin.getAddress()), 1);
        long before = Config.TARGET_BLOCK_TIME_MS;

        String propId = manager.proposeTransaction(walletId, "GOV", "PARAMETER_CHANGE:TARGET_BLOCK_TIME_MS:5000".getBytes());
        approve(propId, admin);

        assertThat(Config.TARGET_BLOCK_TIME_MS).isEqualTo(before);
    }

    /** Externally produced approval, verified by MultiSigManager (address, public key, signature). */
    private void approve(String propId, TestKeyPair owner) {
        byte[] digest = manager.getProposal(propId).getSigningMessage();
        manager.signProposal(propId, owner.getAddress(), owner.getPublicKey(), Crypto.sign(digest, owner.getPrivateKey()));
    }

    @Test
    @DisplayName("MS1.11 — forged / foreign approvals are rejected (MSIG-1)")
    void testForgedApprovalsRejected() {
        TestKeyPair owner = new TestKeyPair(11), stranger = new TestKeyPair(12);
        String walletId = manager.createWallet(List.of(owner.getAddress()), 1);
        String propId = manager.proposeTransaction(walletId, "A_CALL", "x".getBytes());
        byte[] junk = new byte[64];
        new java.security.SecureRandom().nextBytes(junk);
        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                manager.signProposal(propId, owner.getAddress(), owner.getPublicKey(), junk))
                .isInstanceOf(SecurityException.class);                                  // garbage signature
        byte[] strangerSig = Crypto.sign(manager.getProposal(propId).getSigningMessage(), stranger.getPrivateKey());
        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                manager.signProposal(propId, owner.getAddress(), stranger.getPublicKey(), strangerSig))
                .isInstanceOf(SecurityException.class);                                  // key of someone else
        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                manager.signProposal(propId, owner.getAddress(), junk))
                .isInstanceOf(UnsupportedOperationException.class);                      // unverifiable overload
        assertThat(manager.getProposal(propId).isExecuted()).isFalse();
    }
}
