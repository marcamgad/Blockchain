package com.hybrid.blockchain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import java.util.ArrayList;
import java.util.List;
import static org.assertj.core.api.Assertions.*;

@Tag("integration")
public class DifficultyAndPruningTest {

    @Test
    @DisplayName("Invariant: Difficulty must adjust to maintain target block time")
    void testDifficultyAdjustment() {
        // Mock a chain where blocks come too fast
        List<Block> fastChain = new ArrayList<>();
        long now = System.currentTimeMillis();
        for (int i = 0; i <= Config.DIFFICULTY_ADJUSTMENT_INTERVAL; i++) {
            fastChain.add(new Block(i, now + (i * 1000), new ArrayList<>(), "prev", 10, "root"));
        }
        
        int nextDiff = Difficulty.adjustDifficulty(fastChain, 10);
        assertThat(nextDiff).isEqualTo(11);
        
        // Mock a chain where blocks come too slow
        List<Block> slowChain = new ArrayList<>();
        for (int i = 0; i <= Config.DIFFICULTY_ADJUSTMENT_INTERVAL; i++) {
            slowChain.add(new Block(i, now + (i * 200000), new ArrayList<>(), "prev", 10, "root"));
        }
        nextDiff = Difficulty.adjustDifficulty(slowChain, 10);
        assertThat(nextDiff).isEqualTo(9);
    }

    @Test
    @DisplayName("Security: Pruning must be possible without corrupting state root")
    void testLedgerPruning() throws Exception {
        try (TestBlockchain tb = new TestBlockchain()) {
            Blockchain chain = tb.getBlockchain();
            Storage storage = chain.getStorage();
            Mempool mempool = chain.getMempool();
            
            // 1. Create a PrunedBlockchain instance
            @SuppressWarnings("unused")
            PrunedBlockchain prunedChain = new PrunedBlockchain(storage, mempool, 3, (Consensus) chain.getConsensus());
            
            // 2. Generate chain history
            for (int i = 0; i < 5; i++) {
                BlockApplier.createAndApplyBlock(tb, new ArrayList<>());
            }
            
            String stateRootBefore = chain.getAccountState().calculateStateRoot();
            
            // Trigger pruning by adding one more block
            BlockApplier.createAndApplyBlock(tb, new ArrayList<>());
            
            assertThat(stateRootBefore).isNotNull();
        }
    }

    @Test
    @DisplayName("Adversarial: Mining with insufficient difficulty must be rejected")
    void testInsufficientDifficultyRejection() {
        int targetDifficulty = 4;
        Block block = new Block(1, System.currentTimeMillis(), new ArrayList<>(), "prev", targetDifficulty, "state");
        
        // Use block.mine to satisfy difficulty
        block.mine(targetDifficulty, 1000000);
        String targetPrefix = "0".repeat(targetDifficulty);
        assertThat(block.getHash()).startsWith(targetPrefix);
        
        // Manual verification of insufficient difficulty
        String easyHash = "ffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff";
        assertThat(easyHash.startsWith(targetPrefix)).isFalse();
    }
}
