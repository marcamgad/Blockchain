package com.hybrid.blockchain.security;

import com.hybrid.blockchain.TestKeyPair;
import org.junit.jupiter.api.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.junit.jupiter.api.Assertions.assertThrows;

@Tag("Security")
public class MultiSigManagerTest {

    private MultiSigManager manager;
    private TestKeyPair ownerA;
    private TestKeyPair ownerB;
    private TestKeyPair ownerC;
    private TestKeyPair strangerD;
    private final String walletId = "MS-001";

    @BeforeEach
    public void setup() {
        manager = new MultiSigManager();
        ownerA = new TestKeyPair(100);
        ownerB = new TestKeyPair(101);
        ownerC = new TestKeyPair(102);
        strangerD = new TestKeyPair(103);
        
        manager.createWallet(walletId, Arrays.asList(
            ownerA.getAddress(), 
            ownerB.getAddress(), 
            ownerC.getAddress()
        ), 2); // 2-of-3 threshold
    }

    @Test
    @DisplayName("C4.1: Proposal Requires Threshold Signatures")
    public void testProposalRequiresThresholdSignatures() {
        String proposalId = manager.createProposal(walletId, MultiSigManager.ProposalType.TRANSFER_OWNERSHIP, new HashMap<>(), System.currentTimeMillis() + 10000);
        
        // Sign with A only
        manager.signProposal(proposalId, ownerA.getAddress(), ownerA.getPrivateKey(), ownerA.getPublicKey());
        assertThat(manager.canExecute(proposalId)).isFalse();
        
        // Sign with B
        manager.signProposal(proposalId, ownerB.getAddress(), ownerB.getPrivateKey(), ownerB.getPublicKey());
        assertThat(manager.canExecute(proposalId)).isTrue();
    }

    @Test
    @DisplayName("C4.2: Duplicate Signature Not Counted")
    public void testDuplicateSignatureNotCounted() {
        String proposalId = manager.createProposal(walletId, MultiSigManager.ProposalType.TRANSFER_OWNERSHIP, new HashMap<>(), System.currentTimeMillis() + 10000);
        
        // Sign with A
        manager.signProposal(proposalId, ownerA.getAddress(), ownerA.getPrivateKey(), ownerA.getPublicKey());
        
        // Sign with A again - should throw IllegalStateException as per implementation
        assertThrows(IllegalStateException.class, () -> 
            manager.signProposal(proposalId, ownerA.getAddress(), ownerA.getPrivateKey(), ownerA.getPublicKey())
        );
        
        assertThat(manager.getProposal(proposalId).getCurrentSignatures()).isEqualTo(1);
    }

    @Test
    @DisplayName("C4.3: Unknown Signer Rejected")
    public void testUnknownSignerRejected() {
        String proposalId = manager.createProposal(walletId, MultiSigManager.ProposalType.TRANSFER_OWNERSHIP, new HashMap<>(), System.currentTimeMillis() + 10000);
        
        // Sign with D (not an owner)
        assertThrows(SecurityException.class, () -> 
            manager.signProposal(proposalId, strangerD.getAddress(), strangerD.getPrivateKey(), strangerD.getPublicKey())
        );
    }
}
