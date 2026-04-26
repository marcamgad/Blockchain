package com.hybrid.blockchain.consensus;

import com.hybrid.blockchain.*;
import org.junit.jupiter.api.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.junit.jupiter.api.Assertions.assertThrows;

@Tag("Consensus")
public class CheckpointRecoveryTest {

    private TestBlockchain tb;

    @BeforeEach
    public void setup() throws Exception {
        tb = new TestBlockchain();
    }

    @AfterEach
    public void teardown() throws Exception {
        if (tb != null) tb.close();
    }

    @Test
    @DisplayName("C5.1: Fast Sync from Checkpoint")
    public void testFastSyncFromCheckpoint() throws Exception {
        Storage storage = tb.getStorage();
        Blockchain chain = tb.getBlockchain();
        
        // 1. Create some state
        chain.getAccountState().credit("user-1", 1000);
        chain.getAccountState().setBlockHeight(100);
        chain.recalculateStateRoot();
        
        Block lastBlock = chain.getLatestBlock();
        String stateRoot = chain.getAccountState().calculateStateRoot();
        
        // 2. Manually save a checkpoint
        Checkpoint cp = new Checkpoint(
                100,
                lastBlock.getHash(),
                stateRoot,
                "utxo-root-placeholder",
                System.currentTimeMillis(),
                new HashMap<>()
        );
        storage.saveState(chain.getAccountState());
        storage.saveUTXO(tb.getBlockchain().getUtxoSet().toJSON());
        storage.saveCheckpoint(cp);
        
        // 3. Create a NEW blockchain instance with SAME storage
        Blockchain newChain = new Blockchain(storage, new Mempool(), tb.getConsensus());
        newChain.init();
        
        // 4. Verify it recovered to height 100
        // (Assuming the latest block in storage is updated or the checkpoint sets the tip)
        // In current Blockchain.java:
        // if (latestCp != null) { accountState = cpState; ... }
        // The blockchain.init() should set the tip to the checkpoint's block if it's in storage.
        
        assertThat(newChain.getAccountState().getBlockHeight()).isEqualTo(100);
        assertThat(newChain.getAccountState().getBalance("user-1")).isEqualTo(1000);
    }

    @Test
    @DisplayName("C5.2: Checkpoint State Root Mismatch")
    public void testCheckpointStateRootMismatch() throws Exception {
        Storage storage = tb.getStorage();
        Blockchain chain = tb.getBlockchain();
        
        chain.getAccountState().credit("user-1", 1000);
        chain.recalculateStateRoot();
        
        // Save checkpoint with WRONG state root
        Checkpoint cp = new Checkpoint(
                100,
                chain.getLatestBlock().getHash(),
                "invalid-root-hash",
                "utxo-root",
                System.currentTimeMillis(),
                new HashMap<>()
        );
        storage.saveCheckpoint(cp);
        
        // New blockchain should fall back to genesis (height 0) or error out
        Blockchain newChain = new Blockchain(storage, new Mempool(), tb.getConsensus());
        newChain.init();
        
        // If it falls back to genesis, height should be 0
        assertThat(newChain.getAccountState().getBlockHeight()).isEqualTo(0);
    }
}
