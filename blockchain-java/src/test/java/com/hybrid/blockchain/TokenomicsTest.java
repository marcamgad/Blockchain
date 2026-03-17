package com.hybrid.blockchain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

@Tag("unit")
public class TokenomicsTest {

    @Test
    @DisplayName("Invariant: Block reward must start at 50 and halve correctly")
    void testHalvingSchedule() {
        // Initial reward
        assertThat(Tokenomics.getCurrentReward(0)).isEqualTo(50);
        assertThat(Tokenomics.getCurrentReward(209999)).isEqualTo(50);
        
        // First halving
        assertThat(Tokenomics.getCurrentReward(210000)).isEqualTo(25);
        assertThat(Tokenomics.getCurrentReward(419999)).isEqualTo(25);
        
        // Second halving
        assertThat(Tokenomics.getCurrentReward(420000)).isEqualTo(12); // Integer division 25/2
    }

    @Test
    @DisplayName("Security: Block reward must never drop below 1 until supply is exhausted")
    void testMinimumReward() {
        // 6 halvings: 50 -> 25 -> 12 -> 6 -> 3 -> 1 -> 0? 
        // Tokenomics.java: INITIAL_REWARD >> halvings, if < 1 then 1.
        
        long heightAt6Halvings = 6L * Tokenomics.HALVING_INTERVAL;
        assertThat(Tokenomics.getCurrentReward(heightAt6Halvings)).isEqualTo(1);
        
        long deepHeight = 100L * Tokenomics.HALVING_INTERVAL;
        assertThat(Tokenomics.getCurrentReward(deepHeight)).isEqualTo(1);
    }

    @Test
    @DisplayName("Security: Total supply must never exceed MAX_SUPPLY")
    void testSupplyCapEnforcement() {
        long almostFull = Tokenomics.MAX_SUPPLY - 10;
        
        // Should return remaining supply when reward > remaining
        long reward = Tokenomics.getCurrentReward(1, almostFull);
        assertThat(reward).isEqualTo(10);
        
        // Should return 0 when exactly at max supply
        long rewardAtMax = Tokenomics.getCurrentReward(1, Tokenomics.MAX_SUPPLY);
        assertThat(rewardAtMax).isEqualTo(0);
        
        // Should return 0 when over max supply (adversarial case)
        long rewardOverMax = Tokenomics.getCurrentReward(1, Tokenomics.MAX_SUPPLY + 100);
        assertThat(rewardOverMax).isEqualTo(0);
    }
}
