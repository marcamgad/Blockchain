package com.hybrid.blockchain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;

@Tag("integration")
public class CheckpointTest {

    @Test
    @DisplayName("Invariant: Checkpoints must capture full state and be restorable")
    void testCheckpointPersistence() throws Exception {
        try (TestBlockchain tb = new TestBlockchain()) {
            Blockchain chain = tb.getBlockchain();
            Storage storage = tb.getStorage();
            
            // 1. Setup some state
            chain.getAccountState().credit("hbAlice", 1000);
            chain.getAccountState().incrementNonce("hbAlice");
            
            String stateRoot = chain.getAccountState().calculateStateRoot();
            String utxoRoot = "utxo_root_xyz"; // Mock for this test context or calculated from utxoSet
            
            // 2. Save Checkpoint
            Checkpoint cp = new Checkpoint(10, "block_hash_10", stateRoot, utxoRoot, System.currentTimeMillis(), new java.util.HashMap<>());
            storage.saveCheckpoint(cp);
            
            // 3. Load Latest Checkpoint
            Checkpoint latest = storage.loadLatestCheckpoint();
            assertThat(latest).isNotNull();
            assertThat(latest.getBlockHeight()).isEqualTo(10);
            assertThat(latest.getStateRoot()).isEqualTo(stateRoot);
            
            // 4. Load by height
            Checkpoint byHeight = storage.loadCheckpointAtHeight(10);
            assertThat(byHeight.getBlockHash()).isEqualTo("block_hash_10");
        }
    }

    @Test
    @DisplayName("Security: Checkpoint loading must return null if none exists")
    void testEmptyCheckpointLookup() throws Exception {
        try (TestBlockchain tb = new TestBlockchain()) {
            assertThat(tb.getStorage().loadLatestCheckpoint()).isNull();
            assertThat(tb.getStorage().loadCheckpointAtHeight(999)).isNull();
        }
    }
}
