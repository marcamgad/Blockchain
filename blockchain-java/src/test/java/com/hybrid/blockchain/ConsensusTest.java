package com.hybrid.blockchain;

import com.hybrid.blockchain.consensus.PBFTConsensus;
import com.hybrid.blockchain.testutil.TestKeyPair;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Tag("unit")
public class ConsensusTest {

    @Test
    @DisplayName("Invariant: PBFT must reach quorum only with exactly 2f+1 valid votes")
    void testPBFTQuorumLogic() {
        // f=1, n=4 (3f+1) meaning 2f+1 = 3 votes needed
        Map<String, byte[]> validatorSet = new HashMap<>();
        TestKeyPair v1 = new TestKeyPair(1);
        TestKeyPair v2 = new TestKeyPair(2);
        TestKeyPair v3 = new TestKeyPair(3);
        TestKeyPair v4 = new TestKeyPair(4);
        
        validatorSet.put(v1.getAddress(), v1.getPublicKey());
        validatorSet.put(v2.getAddress(), v2.getPublicKey());
        validatorSet.put(v3.getAddress(), v3.getPublicKey());
        validatorSet.put(v4.getAddress(), v4.getPublicKey());
        
        PBFTConsensus consensus = new PBFTConsensus(validatorSet, v1.getAddress(), v1.getPrivateKey());
        
        long height = 1;
        String blockHash = "0000000000000000000000000000000000000000000000000000000000000000";
        
        // 1. Prepare phase - simulate votes
        consensus.addPrepareVote(height, blockHash, v1.getAddress(), signVote(PBFTConsensus.Phase.PREPARE, 0, height, blockHash, v1));
        consensus.addPrepareVote(height, blockHash, v2.getAddress(), signVote(PBFTConsensus.Phase.PREPARE, 0, height, blockHash, v2));
        
        assertThat(consensus.hasQuorum(height, PBFTConsensus.Phase.PREPARE))
            .as("2 votes must not satisfy quorum of 4 validators (needs 3)").isFalse();
        
        consensus.addPrepareVote(height, blockHash, v3.getAddress(), signVote(PBFTConsensus.Phase.PREPARE, 0, height, blockHash, v3));
        
        assertThat(consensus.hasQuorum(height, PBFTConsensus.Phase.PREPARE))
            .as("3 votes must satisfy quorum (2f+1)").isTrue();
            
        // 2. Commit phase
        consensus.addCommitVote(height, blockHash, v4.getAddress(), signVote(PBFTConsensus.Phase.COMMIT, 0, height, blockHash, v4));
        consensus.addCommitVote(height, blockHash, v2.getAddress(), signVote(PBFTConsensus.Phase.COMMIT, 0, height, blockHash, v2));
        
        assertThat(consensus.hasQuorum(height, PBFTConsensus.Phase.COMMIT))
            .as("Commit phase requires separate quorum").isFalse();
            
        consensus.addCommitVote(height, blockHash, v1.getAddress(), signVote(PBFTConsensus.Phase.COMMIT, 0, height, blockHash, v1));
        
        assertThat(consensus.hasQuorum(height, PBFTConsensus.Phase.COMMIT))
            .as("3 commit votes satisfy quorum").isTrue();
    }

    @Test
    @DisplayName("Security: Invalid validator signatures must be strictly rejected")
    void testPBFTInvalidSignatureRejection() {
        Map<String, byte[]> validatorSet = new HashMap<>();
        TestKeyPair v1 = new TestKeyPair(1);
        TestKeyPair v2 = new TestKeyPair(2);
        TestKeyPair v3 = new TestKeyPair(3);
        TestKeyPair v4 = new TestKeyPair(4);
        
        validatorSet.put(v1.getAddress(), v1.getPublicKey());
        validatorSet.put(v2.getAddress(), v2.getPublicKey());
        validatorSet.put(v3.getAddress(), v3.getPublicKey());
        validatorSet.put(v4.getAddress(), v4.getPublicKey());
        
        PBFTConsensus consensus = new PBFTConsensus(validatorSet, v1.getAddress(), v1.getPrivateKey());
        
        long height = 1;
        String blockHash = "0000";
        
        TestKeyPair attacker = new TestKeyPair(666); // Not in validator set
        byte[] maliciousSig = signVote(PBFTConsensus.Phase.PREPARE, 0, height, blockHash, attacker);
        
        // Attacker spoofing v2's identity but using their own signature
        assertThatThrownBy(() -> consensus.addPrepareVote(height, blockHash, v2.getAddress(), maliciousSig))
            .isInstanceOf(SecurityException.class)
            .hasMessageContaining("Invalid signature");
    }

    @Test
    @DisplayName("Security: PoA must detect double signing and slash validator")
    void testPoADoubleSigningDetection() throws Exception {
        TestKeyPair v1 = new TestKeyPair(1);
        Validator val = new Validator(v1.getAddress(), v1.getPublicKey());
        PoAConsensus poa = new PoAConsensus(java.util.Collections.singletonList(val));
        
        String zeroHash = "0000";
        
        // First block signed by V1 at height 1
        Block b1 = new Block(1, 1000L, new java.util.ArrayList<>(), zeroHash, 0, zeroHash);
        b1.setValidatorId(v1.getAddress());
        poa.signBlock(b1, val, v1.getPrivateKey());
        poa.verifyBlock(b1, val); 
        
        // Second block signed by V1 at height 1 (equivocation / double-sign)
        Block b2 = new Block(1, 2000L, new java.util.ArrayList<>(), zeroHash, 0, zeroHash);
        b2.setValidatorId(v1.getAddress());
        poa.signBlock(b2, val, v1.getPrivateKey());
        
        // The verify call should detect it
        poa.verifyBlock(b2, val);
        
        assertThat(poa.getSlashedValidators())
            .as("Validator must be slashed for double signing")
            .contains(v1.getAddress());
    }

    private byte[] signVote(PBFTConsensus.Phase phase, long view, long height, String hash, TestKeyPair kp) {
        String data = phase.name() + view + height + hash + kp.getAddress();
        return Crypto.sign(Crypto.hash(data.getBytes()), kp.getPrivateKey());
    }
}
