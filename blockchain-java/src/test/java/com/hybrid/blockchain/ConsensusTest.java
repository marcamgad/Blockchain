package com.hybrid.blockchain;

import com.hybrid.blockchain.consensus.PBFTConsensus;
import com.hybrid.blockchain.PoAConsensus;
import org.junit.jupiter.api.*;
import java.util.*;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import static org.assertj.core.api.Assertions.*;

@Tag("unit")
public class ConsensusTest {

    @Test
    @DisplayName("C10.1: PBFT Timer Fires View Change")
    void testPBFTTimerFiresViewChange() throws Exception {
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
        consensus.setTimeout(100L); // Set 100ms for test
        long initialView = consensus.getViewNumber();

        // Wait for timeout (100ms) + buffer
        Thread.sleep(300);

        // To make the view actually advance in a single-instance test, 
        // we simulate receiving VC votes from others after the timeout
        consensus.addViewChangeVote(1, 0, v2.getAddress(), signViewChange(1, 0, v2));
        consensus.addViewChangeVote(1, 0, v3.getAddress(), signViewChange(1, 0, v3));

        assertThat(consensus.getViewNumber()).isGreaterThan(initialView);
        consensus.shutdown();
    }

    @Test
    @DisplayName("C10.2: Reputation-Weighted Leader Selection")
    void testReputationWeightedLeaderSelection() {
        Map<String, byte[]> validatorSet = new java.util.TreeMap<>();
        TestKeyPair v1 = new TestKeyPair(1);
        TestKeyPair v2 = new TestKeyPair(2);
        TestKeyPair v3 = new TestKeyPair(3);
        TestKeyPair v4 = new TestKeyPair(4);
        validatorSet.put(v1.getAddress(), v1.getPublicKey());
        validatorSet.put(v2.getAddress(), v2.getPublicKey());
        validatorSet.put(v3.getAddress(), v3.getPublicKey());
        validatorSet.put(v4.getAddress(), v4.getPublicKey());

        PBFTConsensus consensus = new PBFTConsensus(validatorSet, v1.getAddress(), v1.getPrivateKey());
        
        // Set v1 reputation high
        consensus.updateReputation(v1.getAddress(), 9.0); // 1.0 + 9.0 = 10.0
        
        int v1Count = 0;
        for (int i = 0; i < 100; i++) {
            if (consensus.selectLeader(i).equals(v1.getAddress())) {
                v1Count++;
            }
        }
        
        // v1 has 10.0 weight, others have 1.0 each. Total = 13.0.
        // v1 probability = 10/13 ~= 77%. Expected count > 50.
        assertThat(v1Count).isGreaterThan(50);
    }

    @Test
    @DisplayName("Invariant: PBFT must reach quorum only with exactly 2f+1 valid votes")
    void testPBFTQuorumLogic() {
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
        
        consensus.addPrepareVote(height, blockHash, v1.getAddress(), signVote(PBFTConsensus.Phase.PREPARE, 0, height, blockHash, v1));
        consensus.addPrepareVote(height, blockHash, v2.getAddress(), signVote(PBFTConsensus.Phase.PREPARE, 0, height, blockHash, v2));
        
        assertThat(consensus.hasQuorum(height, PBFTConsensus.Phase.PREPARE)).isFalse();
        
        consensus.addPrepareVote(height, blockHash, v3.getAddress(), signVote(PBFTConsensus.Phase.PREPARE, 0, height, blockHash, v3));
        assertThat(consensus.hasQuorum(height, PBFTConsensus.Phase.PREPARE)).isTrue();
    }

    @Test
    @DisplayName("Security: PoA must detect double signing and slash validator")
    void testPoADoubleSigningDetection() throws Exception {
        TestKeyPair v1 = new TestKeyPair(1);
        Validator val = new Validator(v1.getAddress(), v1.getPublicKey());
        PoAConsensus poa = new PoAConsensus(java.util.Collections.singletonList(val));
        
        String zeroHash = "0000";
        Block b1 = new Block(1, 1000L, new java.util.ArrayList<>(), zeroHash, 0, zeroHash);
        b1.setValidatorId(v1.getAddress());
        poa.signBlock(b1, val, v1.getPrivateKey());
        poa.verifyBlock(b1, val); 
        
        Block b2 = new Block(1, 2000L, new java.util.ArrayList<>(), zeroHash, 0, zeroHash);
        b2.setValidatorId(v1.getAddress());
        poa.signBlock(b2, val, v1.getPrivateKey());
        poa.verifyBlock(b2, val);
        
        assertThat(poa.getSlashedValidators()).contains(v1.getAddress());
    }

    @Test
    @DisplayName("C10.3: PoA selectLeader returns deterministic non-null descriptor")
    void testPoALeaderDescriptorSelection() {
        TestKeyPair v1 = new TestKeyPair(101);
        TestKeyPair v2 = new TestKeyPair(102);
        Validator val1 = new Validator(v1.getAddress(), v1.getPublicKey());
        Validator val2 = new Validator(v2.getAddress(), v2.getPublicKey());

        java.util.List<Validator> validatorList = new java.util.ArrayList<>();
        validatorList.add(val1);
        validatorList.add(val2);
        PoAConsensus poa = new PoAConsensus(validatorList);

        java.util.List<String> authorized = new java.util.ArrayList<>();
        authorized.add(v1.getAddress());
        authorized.add(v2.getAddress());

        Block leader0 = poa.selectLeader(authorized, 0);
        Block leader1 = poa.selectLeader(authorized, 1);

        assertThat(leader0).isNotNull();
        assertThat(leader1).isNotNull();
        assertThat(leader0.getValidatorId()).isNotBlank();
        assertThat(leader1.getValidatorId()).isNotBlank();
        assertThat(poa.selectLeader(authorized, 1).getValidatorId()).isEqualTo(leader1.getValidatorId());
    }

    @Test
    @DisplayName("C10.4: PBFT consensus-interface leader descriptor is non-null")
    void testPBFTLeaderDescriptorViaInterface() {
        Map<String, byte[]> validatorSet = new HashMap<>();
        TestKeyPair v1 = new TestKeyPair(201);
        TestKeyPair v2 = new TestKeyPair(202);
        TestKeyPair v3 = new TestKeyPair(203);
        TestKeyPair v4 = new TestKeyPair(204);
        validatorSet.put(v1.getAddress(), v1.getPublicKey());
        validatorSet.put(v2.getAddress(), v2.getPublicKey());
        validatorSet.put(v3.getAddress(), v3.getPublicKey());
        validatorSet.put(v4.getAddress(), v4.getPublicKey());

        Consensus consensus = new PBFTConsensus(validatorSet, v1.getAddress(), v1.getPrivateKey());
        Block descriptor = consensus.selectLeader(new java.util.ArrayList<>(validatorSet.keySet()), 3);

        assertThat(descriptor).isNotNull();
        assertThat(descriptor.getValidatorId()).isNotBlank();
        consensus.shutdown();
    }

    private byte[] signViewChange(long view, long seq, TestKeyPair kp) {
        String data = PBFTConsensus.Phase.VIEW_CHANGE.name() + view + seq + "VIEW_CHANGE_PROOF" + kp.getAddress();
        return com.hybrid.blockchain.Crypto.sign(com.hybrid.blockchain.Crypto.hash(data.getBytes()), kp.getPrivateKey());
    }

    private byte[] signVote(PBFTConsensus.Phase phase, long view, long height, String hash, TestKeyPair kp) {
        String data = phase.name() + view + height + hash + kp.getAddress();
        return com.hybrid.blockchain.Crypto.sign(com.hybrid.blockchain.Crypto.hash(data.getBytes()), kp.getPrivateKey());
    }
}
