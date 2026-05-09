package com.hybrid.blockchain;

import org.junit.jupiter.api.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

@Tag("severe")
public class AdaptiveDifficultyTest {

    @Test
    @DisplayName("DIFF.1: Should increase difficulty when TPS is high")
    void testTPSBasedDifficulty() {
        List<Block> chain = new ArrayList<>();
        long now = System.currentTimeMillis();
        
        // Create 11 blocks (interval=10)
        for (int i = 0; i <= 10; i++) {
            List<Transaction> txs = new ArrayList<>();
            if (i > 0) {
                // High volume: 100 tx per block -> 1000 tx over 10s (if blocks are 1s apart)
                for (int j = 0; j < 100; j++) {
                    txs.add(new Transaction.Builder().build());
                }
            }
            // 1 second blocks
            chain.add(new Block(i, now + (i * 1000), txs, "prev", 4, "root"));
        }
        
        // interval = 10
        // actualTime = 10000ms (10s)
        // expectedTime = 10 * 60000 = 600000ms (600s)
        // actualTime < expectedTime/2 -> +1 from time
        // totalTxs = 10 * 100 = 1000
        // TPS = 1000 / 10 = 100
        // TPS > 50 -> +1 from TPS
        // Total should be 4 + 1 + 1 = 6
        
        int nextDiff = Difficulty.adjustDifficulty(chain, 4);
        assertThat(nextDiff).isEqualTo(6);
    }

    @Test
    @DisplayName("DIFF.2: Should only adjust by time if TPS is low")
    void testTimeOnlyDifficulty() {
        List<Block> chain = new ArrayList<>();
        long now = System.currentTimeMillis();
        
        for (int i = 0; i <= 10; i++) {
            // Low volume: 1 tx per block
            chain.add(new Block(i, now + (i * 1000), Collections.singletonList(new Transaction.Builder().build()), "prev", 4, "root"));
        }
        
        // TPS = 10 / 10 = 1.0 (Low)
        // Time = 10s (Fast) -> +1
        // Total = 4 + 1 = 5
        
        int nextDiff = Difficulty.adjustDifficulty(chain, 4);
        assertThat(nextDiff).isEqualTo(5);
    }
}
