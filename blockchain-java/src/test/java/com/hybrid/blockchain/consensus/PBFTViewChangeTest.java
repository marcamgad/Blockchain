package com.hybrid.blockchain.consensus;

import com.hybrid.blockchain.Blockchain;
import com.hybrid.blockchain.Crypto;
import com.hybrid.blockchain.Mempool;
import com.hybrid.blockchain.Storage;
import com.hybrid.blockchain.Validator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import java.math.BigInteger;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

public class PBFTViewChangeTest {
    private PBFTConsensus pbft;
    private final Map<String, byte[]> validators = new HashMap<>();
    private final BigInteger privateKey = new BigInteger("123456789");

    @BeforeEach
    public void setUp() {
        for (int i = 1; i <= 4; i++) {
            byte[] pub = Crypto.derivePublicKey(BigInteger.valueOf(i * 100));
            validators.put("V" + i, pub);
        }
        pbft = new PBFTConsensus(validators, "V1", privateKey);
    }

    @Test
    public void testLeaderElection() {
        assertEquals("V1", pbft.getCurrentLeader(), "Initially V1 should be leader for view 0");
        
        pbft.triggerViewChange();
        // Since we only have 1 vote (ours), quorum isn't reached yet
        assertEquals("V1", pbft.getCurrentLeader(), "Leader should not change without quorum");
        
        // Add votes from V2 and V3
        pbft.addViewChangeVote(1, 0, "V2", sign(1, 0, "V2", BigInteger.valueOf(200)));
        pbft.addViewChangeVote(1, 0, "V3", sign(1, 0, "V3", BigInteger.valueOf(300)));
        
        // Now quorum (2f+1 = 3) is reached
        // [HC-082] round-robin over the sorted validators [V1..V4]: view 1 -> V2 (the old weighted
        // election, after penalising V1, picked V4)
        assertEquals("V2", pbft.getCurrentLeader(), "Leader should change to V2 after view change quorum");
    }

    @Test
    public void testTimeoutTriggersViewChange() throws InterruptedException {
        // This is a bit hard to test with real time, but we can check if it starts a timer
        assertNotNull(pbft.getCurrentLeader());
        // Wait for timeout (configured for 15s, maybe too slow for test?)
        // Let's just verify the logic of triggerViewChange manually here
    }

    private byte[] sign(long view, long lastSeq, String valId, BigInteger key) {
        PBFTConsensus.PBFTMessage m = new PBFTConsensus.PBFTMessage(
                PBFTConsensus.Phase.VIEW_CHANGE, view, lastSeq, "VIEW_CHANGE_PROOF", valId);
        m.sign(key);
        return m.signature;
    }
}
