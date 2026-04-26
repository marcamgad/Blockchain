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
        manager.signProposal(propId, owner1.getAddress(), Crypto.sign(propId.getBytes(), owner1.getPrivateKey()));
        assertThat(manager.getProposal(propId).getSignatureCount()).isEqualTo(1);
        assertThat(manager.getProposal(propId).isExecuted()).isFalse();
        
        // MS1.5: double sign attempt from same owner
        manager.signProposal(propId, owner1.getAddress(), Crypto.sign(propId.getBytes(), owner1.getPrivateKey()));
        assertThat(manager.getProposal(propId).getSignatureCount()).as("Should not increment for duplicate owner").isEqualTo(1);
        
        // 2nd sign (reaches threshold)
        manager.signProposal(propId, owner2.getAddress(), Crypto.sign(propId.getBytes(), owner2.getPrivateKey()));
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
        manager.signProposal(propId, admin.getAddress(), Crypto.sign(propId.getBytes(), admin.getPrivateKey()));
        
        assertThat(tb.getBlockchain().getConsensus().getValidators()).anyMatch(v -> v.getId().equals(newValidator.getAddress()));
    }

    @Test
    @DisplayName("MS1.10 — PARAMETER_CHANGE governance")
    void testParamChangeProposal() {
        TestKeyPair admin = new TestKeyPair(1);
        String walletId = manager.createWallet(List.of(admin.getAddress()), 1);
        
        long oldTime = Config.TARGET_BLOCK_TIME_MS;
        String metadata = "PARAMETER_CHANGE:TARGET_BLOCK_TIME_MS:5000";
        
        String propId = manager.proposeTransaction(walletId, "GOV", metadata.getBytes());
        manager.signProposal(propId, admin.getAddress(), Crypto.sign(propId.getBytes(), admin.getPrivateKey()));
        
        assertThat(Config.TARGET_BLOCK_TIME_MS).isEqualTo(5000L);
        Config.TARGET_BLOCK_TIME_MS = oldTime; // Restore
    }
}
