package com.hybrid.blockchain;

import com.hybrid.blockchain.security.MultiSigManager;
import com.hybrid.blockchain.security.MultiSigManager.MultiSigWallet;
import com.hybrid.blockchain.security.MultiSigManager.Proposal;
import com.hybrid.blockchain.security.MultiSigManager.ProposalType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for Multi-Signature Control System
 */
public class MultiSigManagerTest {

    private MultiSigManager multiSig;
    private String walletId;
    private List<String> owners;
    private Map<String, BigInteger> ownerKeys;
    private Map<String, byte[]> ownerPubKeys;

    @BeforeEach
    public void setUp() {
        multiSig = new MultiSigManager();
        walletId = "wallet-001";

        // Create 3 owners with keys
        owners = Arrays.asList("owner1", "owner2", "owner3");
        ownerKeys = new HashMap<>();
        ownerPubKeys = new HashMap<>();

        for (int i = 0; i < owners.size(); i++) {
            BigInteger privateKey = new BigInteger(String.valueOf(1000 + i));
            byte[] publicKey = Crypto.derivePublicKey(privateKey);
            ownerKeys.put(owners.get(i), privateKey);
            ownerPubKeys.put(owners.get(i), publicKey);
        }

        // Create 2-of-3 multisig wallet
        multiSig.createWallet(walletId, owners, 2);
    }

    @Test
    public void testCreateWallet() {
        MultiSigWallet wallet = multiSig.getWallet(walletId);

        assertNotNull(wallet);
        assertEquals(walletId, wallet.getWalletId());
        assertEquals(3, wallet.getTotalOwners());
        assertEquals(2, wallet.getRequiredSignatures());
        assertTrue(wallet.isOwner("owner1"));
        assertTrue(wallet.isOwner("owner2"));
        assertTrue(wallet.isOwner("owner3"));
        assertFalse(wallet.isOwner("stranger"));
    }

    @Test
    public void testCreateProposal() {
        Map<String, Object> data = new HashMap<>();
        data.put("deviceId", "sensor-001");
        data.put("newOwner", "alice");

        long expiration = System.currentTimeMillis() + 60000; // 1 minute

        String proposalId = multiSig.createProposal(
                walletId,
                ProposalType.TRANSFER_OWNERSHIP,
                data,
                expiration);

        assertNotNull(proposalId);

        Proposal proposal = multiSig.getProposal(proposalId);
        assertNotNull(proposal);
        assertEquals(walletId, proposal.getWalletId());
        assertEquals(ProposalType.TRANSFER_OWNERSHIP, proposal.getProposalType());
        assertEquals("sensor-001", proposal.getData().get("deviceId"));
        assertFalse(proposal.isExpired());
        assertFalse(proposal.isExecuted());
    }

    @Test
    public void testSignProposal() {
        Map<String, Object> data = new HashMap<>();
        data.put("firmwareVersion", "v2.0.1");

        String proposalId = multiSig.createProposal(
                walletId,
                ProposalType.UPDATE_FIRMWARE,
                data,
                System.currentTimeMillis() + 60000);

        // Owner1 signs
        multiSig.signProposal(
                proposalId,
                "owner1",
                ownerKeys.get("owner1"),
                ownerPubKeys.get("owner1"));

        Proposal proposal = multiSig.getProposal(proposalId);
        assertEquals(1, proposal.getCurrentSignatures());
        assertTrue(proposal.hasSigned("owner1"));
        assertFalse(proposal.hasSigned("owner2"));
    }

    @Test
    public void testMultipleSignatures() {
        Map<String, Object> data = new HashMap<>();
        data.put("action", "test");

        String proposalId = multiSig.createProposal(
                walletId,
                ProposalType.EXECUTE_CONTRACT,
                data,
                System.currentTimeMillis() + 60000);

        // Owner1 signs
        multiSig.signProposal(
                proposalId,
                "owner1",
                ownerKeys.get("owner1"),
                ownerPubKeys.get("owner1"));

        // Owner2 signs
        multiSig.signProposal(
                proposalId,
                "owner2",
                ownerKeys.get("owner2"),
                ownerPubKeys.get("owner2"));

        Proposal proposal = multiSig.getProposal(proposalId);
        assertEquals(2, proposal.getCurrentSignatures());
        assertTrue(proposal.hasEnoughSignatures());
        assertTrue(proposal.hasSigned("owner1"));
        assertTrue(proposal.hasSigned("owner2"));
    }

    @Test
    public void testCanExecute() {
        Map<String, Object> data = new HashMap<>();
        String proposalId = multiSig.createProposal(
                walletId,
                ProposalType.MODIFY_COLLECTION,
                data,
                System.currentTimeMillis() + 60000);

        // Not enough signatures yet
        assertFalse(multiSig.canExecute(proposalId));

        // Add first signature
        multiSig.signProposal(proposalId, "owner1", ownerKeys.get("owner1"), ownerPubKeys.get("owner1"));
        assertFalse(multiSig.canExecute(proposalId));

        // Add second signature (now we have 2-of-3)
        multiSig.signProposal(proposalId, "owner2", ownerKeys.get("owner2"), ownerPubKeys.get("owner2"));
        assertTrue(multiSig.canExecute(proposalId));
    }

    @Test
    public void testExecuteProposal() {
        Map<String, Object> data = new HashMap<>();
        String proposalId = multiSig.createProposal(
                walletId,
                ProposalType.REVOKE_DEVICE,
                data,
                System.currentTimeMillis() + 60000);

        // Add 2 signatures
        multiSig.signProposal(proposalId, "owner1", ownerKeys.get("owner1"), ownerPubKeys.get("owner1"));
        multiSig.signProposal(proposalId, "owner2", ownerKeys.get("owner2"), ownerPubKeys.get("owner2"));

        // Execute
        assertTrue(multiSig.executeProposal(proposalId));

        Proposal proposal = multiSig.getProposal(proposalId);
        assertTrue(proposal.isExecuted());

        // Cannot execute again
        assertFalse(multiSig.executeProposal(proposalId));
    }

    @Test
    public void testUnauthorizedSigner() {
        Map<String, Object> data = new HashMap<>();
        String proposalId = multiSig.createProposal(
                walletId,
                ProposalType.TRANSFER_OWNERSHIP,
                data,
                System.currentTimeMillis() + 60000);

        BigInteger strangerKey = new BigInteger("9999");
        byte[] strangerPubKey = Crypto.derivePublicKey(strangerKey);

        // Stranger tries to sign
        assertThrows(SecurityException.class, () -> {
            multiSig.signProposal(proposalId, "stranger", strangerKey, strangerPubKey);
        });
    }

    @Test
    public void testDuplicateSignature() {
        Map<String, Object> data = new HashMap<>();
        String proposalId = multiSig.createProposal(
                walletId,
                ProposalType.CHANGE_THRESHOLD,
                data,
                System.currentTimeMillis() + 60000);

        // Owner1 signs
        multiSig.signProposal(proposalId, "owner1", ownerKeys.get("owner1"), ownerPubKeys.get("owner1"));

        // Owner1 tries to sign again
        assertThrows(IllegalStateException.class, () -> {
            multiSig.signProposal(proposalId, "owner1", ownerKeys.get("owner1"), ownerPubKeys.get("owner1"));
        });
    }

    @Test
    public void testExpiredProposal() throws InterruptedException {
        Map<String, Object> data = new HashMap<>();

        // Create proposal that expires in 100ms
        String proposalId = multiSig.createProposal(
                walletId,
                ProposalType.EMERGENCY_STOP,
                data,
                System.currentTimeMillis() + 100);

        // Wait for expiration
        Thread.sleep(150);

        Proposal proposal = multiSig.getProposal(proposalId);
        assertTrue(proposal.isExpired());

        // Cannot sign expired proposal
        assertThrows(IllegalStateException.class, () -> {
            multiSig.signProposal(proposalId, "owner1", ownerKeys.get("owner1"), ownerPubKeys.get("owner1"));
        });
    }

    @Test
    public void testGetProposalsForWallet() {
        // Create multiple proposals
        for (int i = 0; i < 3; i++) {
            Map<String, Object> data = new HashMap<>();
            data.put("index", i);
            multiSig.createProposal(
                    walletId,
                    ProposalType.EXECUTE_CONTRACT,
                    data,
                    System.currentTimeMillis() + 60000);
        }

        List<Proposal> proposals = multiSig.getProposalsForWallet(walletId);
        assertEquals(3, proposals.size());
    }

    @Test
    public void testStats() {
        // Create wallet and proposals
        Map<String, Object> data = new HashMap<>();

        String proposal1 = multiSig.createProposal(walletId, ProposalType.TRANSFER_OWNERSHIP, data,
                System.currentTimeMillis() + 60000);
        String proposal2 = multiSig.createProposal(walletId, ProposalType.UPDATE_FIRMWARE, data,
                System.currentTimeMillis() + 60000);

        // Execute one
        multiSig.signProposal(proposal1, "owner1", ownerKeys.get("owner1"), ownerPubKeys.get("owner1"));
        multiSig.signProposal(proposal1, "owner2", ownerKeys.get("owner2"), ownerPubKeys.get("owner2"));
        multiSig.executeProposal(proposal1);

        Map<String, Object> stats = multiSig.getStats();
        assertEquals(1, stats.get("totalWallets"));
        assertEquals(2, stats.get("totalProposals"));
        assertEquals(1, stats.get("activeProposals"));
        assertEquals(1, stats.get("executedProposals"));
    }

    @Test
    public void test3of5MultiSig() {
        // Create 3-of-5 wallet
        List<String> fiveOwners = Arrays.asList("o1", "o2", "o3", "o4", "o5");
        multiSig.createWallet("wallet-3of5", fiveOwners, 3);

        MultiSigWallet wallet = multiSig.getWallet("wallet-3of5");
        assertEquals(5, wallet.getTotalOwners());
        assertEquals(3, wallet.getRequiredSignatures());
    }

    @Test
    public void testInvalidWalletCreation() {
        // Required signatures > owners
        assertThrows(IllegalArgumentException.class, () -> {
            multiSig.createWallet("invalid", Arrays.asList("o1", "o2"), 3);
        });

        // Required signatures < 1
        assertThrows(IllegalArgumentException.class, () -> {
            multiSig.createWallet("invalid", Arrays.asList("o1", "o2"), 0);
        });
    }
}
