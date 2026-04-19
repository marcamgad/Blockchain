package com.hybrid.blockchain;

import com.hybrid.blockchain.security.MultiSigManager;
import com.hybrid.blockchain.security.MultiSigManager.MultiSigWallet;
import com.hybrid.blockchain.security.MultiSigManager.Proposal;
import com.hybrid.blockchain.security.MultiSigManager.ProposalType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Tag("unit")
public class MultiSigManagerTest {

    private MultiSigManager multiSig;
    private final String WALLET_ID = "wallet-001";
    private TestKeyPair owner1;
    private TestKeyPair owner2;
    private TestKeyPair owner3;

    @BeforeEach
    public void setUp() {
        multiSig = new MultiSigManager();
        owner1 = new TestKeyPair(1);
        owner2 = new TestKeyPair(2);
        owner3 = new TestKeyPair(3);

        List<String> owners = Arrays.asList(owner1.getAddress(), owner2.getAddress(), owner3.getAddress());
        multiSig.createWallet(WALLET_ID, owners, 2); // 2-of-3 multisig
    }

    @Test
    @DisplayName("Invariant: Wallet creation establishes parameters correctly")
    public void testCreateWallet() {
        MultiSigWallet wallet = multiSig.getWallet(WALLET_ID);

        assertThat(wallet).isNotNull();
        assertThat(wallet.getTotalOwners()).isEqualTo(3);
        assertThat(wallet.getRequiredSignatures()).isEqualTo(2);
        assertThat(wallet.isOwner(owner1.getAddress())).isTrue();
        assertThat(wallet.isOwner("stranger")).isFalse();
    }

    @Test
    @DisplayName("Invariant: Proposal creation tracks data and state accurately")
    public void testCreateProposal() {
        Map<String, Object> data = new HashMap<>();
        data.put("deviceId", "sensor-001");
        long expiration = System.currentTimeMillis() + 60000;

        String proposalId = multiSig.createProposal(WALLET_ID, ProposalType.TRANSFER_OWNERSHIP, data, expiration);

        Proposal proposal = multiSig.getProposal(proposalId);
        assertThat(proposal.getWalletId()).isEqualTo(WALLET_ID);
        assertThat(proposal.getProposalType()).isEqualTo(ProposalType.TRANSFER_OWNERSHIP);
        assertThat(proposal.getData().get("deviceId")).isEqualTo("sensor-001");
        assertThat(proposal.isExecuted()).isFalse();
    }

    @Test
    @DisplayName("Security: Multiple distinct signatures required for execution")
    public void testMultipleSignatures() {
        Map<String, Object> data = new HashMap<>();
        String proposalId = multiSig.createProposal(WALLET_ID, ProposalType.EXECUTE_CONTRACT, data, System.currentTimeMillis() + 60000);

        multiSig.signProposal(proposalId, owner1.getAddress(), owner1.getPrivateKey(), owner1.getPublicKey());
        assertThat(multiSig.canExecute(proposalId)).isFalse();

        multiSig.signProposal(proposalId, owner2.getAddress(), owner2.getPrivateKey(), owner2.getPublicKey());
        
        Proposal proposal = multiSig.getProposal(proposalId);
        assertThat(proposal.getCurrentSignatures()).isEqualTo(2);
        assertThat(multiSig.canExecute(proposalId)).isTrue();
    }

    @Test
    @DisplayName("Invariant: Proposal can only be executed once")
    public void testExecuteProposal() {
        Map<String, Object> data = new HashMap<>();
        String proposalId = multiSig.createProposal(WALLET_ID, ProposalType.REVOKE_DEVICE, data, System.currentTimeMillis() + 60000);

        multiSig.signProposal(proposalId, owner1.getAddress(), owner1.getPrivateKey(), owner1.getPublicKey());
        multiSig.signProposal(proposalId, owner2.getAddress(), owner2.getPrivateKey(), owner2.getPublicKey());

        assertThat(multiSig.executeProposal(proposalId)).isTrue();
        
        Proposal proposal = multiSig.getProposal(proposalId);
        assertThat(proposal.isExecuted()).isTrue();

        // Cannot execute again
        assertThat(multiSig.executeProposal(proposalId)).isFalse();
    }

    @Test
    @DisplayName("Security: Unauthorized signer must be rejected")
    public void testUnauthorizedSigner() {
        Map<String, Object> data = new HashMap<>();
        String proposalId = multiSig.createProposal(WALLET_ID, ProposalType.TRANSFER_OWNERSHIP, data, System.currentTimeMillis() + 60000);

        TestKeyPair stranger = new TestKeyPair(999);

        assertThatThrownBy(() -> multiSig.signProposal(proposalId, stranger.getAddress(), stranger.getPrivateKey(), stranger.getPublicKey()))
            .isInstanceOf(SecurityException.class);
    }

    @Test
    @DisplayName("Security: Same owner cannot sign multiple times")
    public void testDuplicateSignature() {
        Map<String, Object> data = new HashMap<>();
        String proposalId = multiSig.createProposal(WALLET_ID, ProposalType.CHANGE_THRESHOLD, data, System.currentTimeMillis() + 60000);

        multiSig.signProposal(proposalId, owner1.getAddress(), owner1.getPrivateKey(), owner1.getPublicKey());

        assertThatThrownBy(() -> multiSig.signProposal(proposalId, owner1.getAddress(), owner1.getPrivateKey(), owner1.getPublicKey()))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("Already signed");
    }

    @Test
    @DisplayName("Invariant: Expired proposals cannot be signed")
    public void testExpiredProposal() {
        Map<String, Object> data = new HashMap<>();
        // Create an already-expired proposal deterministically (expiration in the past)
        long expiredTime = System.currentTimeMillis() - 1000; 
        
        String proposalId = multiSig.createProposal(WALLET_ID, ProposalType.EMERGENCY_STOP, data, expiredTime);

        Proposal proposal = multiSig.getProposal(proposalId);
        assertThat(proposal.isExpired()).isTrue();

        assertThatThrownBy(() -> multiSig.signProposal(proposalId, owner1.getAddress(), owner1.getPrivateKey(), owner1.getPublicKey()))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("expired");
    }
}
