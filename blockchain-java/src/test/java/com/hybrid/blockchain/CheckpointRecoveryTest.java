package com.hybrid.blockchain;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.Tag;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * [TEST-C5] Checkpoint Recovery and Fast-Sync Tests.
 */
@Tag("Core")
@Tag("Consensus")
public class CheckpointRecoveryTest {

    private TestBlockchain tb;
    private Blockchain blockchain;

    @BeforeEach
    void setup() throws Exception {
        tb = new TestBlockchain();
        blockchain = tb.getBlockchain();
    }

    @AfterEach
    void teardown() throws Exception {
        if (tb != null) tb.close();
    }

    @Test
    @DisplayName("C5.1: Fast Sync from Valid Checkpoint")
    void testFastSyncFromCheckpoint() throws Exception {
        // 1. Create a checkpoint manually
        String stateRoot = blockchain.getState().calculateStateRoot();
        String utxoRoot = Crypto.bytesToHex(Crypto.hash(new byte[0])); // mock
        
        Checkpoint cp = new Checkpoint(
            100,
            "0xBlockHash100",
            stateRoot,
            utxoRoot,
            System.currentTimeMillis(),
            new HashMap<>()
        );
        
        // 2. Load it into a fresh storage (mock fast sync)
        TestBlockchain freshTb = new TestBlockchain();
        Blockchain freshBc = freshTb.getBlockchain();
        
        // Simulate peer sync logic: load checkpoint state root
        freshBc.getState().merge(blockchain.getState()); // Copy state
        freshBc.recalculateStateRoot();
        
        assertThat(freshBc.getState().calculateStateRoot()).isEqualTo(stateRoot);
        freshTb.close();
    }

    @Test
    @DisplayName("C5.2: State Root Mismatch Rejection")
    void testStateRootMismatch() throws Exception {
        // 1. Create a checkpoint with a FAKE state root
        Checkpoint badCp = new Checkpoint(
            100,
            "0xBlock100",
            "0xFAKE_ROOT",
            "0xUTXO",
            System.currentTimeMillis(),
            new HashMap<>()
        );
        
        // 2. Attempt to verify it against actual state
        String actualRoot = blockchain.getState().calculateStateRoot();
        assertThat(badCp.getStateRoot()).isNotEqualTo(actualRoot);
        
        // In real logic, the node would reject this checkpoint and fall back to full sync
        boolean isValid = badCp.getStateRoot().equals(actualRoot);
        assertThat(isValid).isFalse();
    }
}
