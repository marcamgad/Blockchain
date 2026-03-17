package com.hybrid.blockchain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

@Tag("unit")
public class FeeMarketTest {

    @Test
    @DisplayName("Invariant: Base fee must increase when block is full (congestion)")
    void testBaseFeeIncrease() {
        long currentBaseFee = 100;
        int targetGas = 1000;
        int usedGas = 2000; // Double the target
        
        long nextFee = FeeMarket.calculateNextBaseFee(currentBaseFee, usedGas, targetGas);
        
        // EIP-1559 max change is 12.5% (1/8)
        // 100 * (1000) / 1000 / 8 = 100 / 8 = 12
        assertThat(nextFee).isEqualTo(112);
    }

    @Test
    @DisplayName("Invariant: Base fee must decrease when block is empty")
    void testBaseFeeDecrease() {
        long currentBaseFee = 100;
        int targetGas = 1000;
        int usedGas = 0; // Empty block
        
        long nextFee = FeeMarket.calculateNextBaseFee(currentBaseFee, usedGas, targetGas);
        
        // 100 * (-1000) / 1000 / 8 = -12
        assertThat(nextFee).isEqualTo(88);
    }

    @Test
    @DisplayName("Security: Base fee must never drop below 1")
    void testBaseFeeFloor() {
        long currentBaseFee = 1;
        int targetGas = 1000;
        int usedGas = 0;
        
        long nextFee = FeeMarket.calculateNextBaseFee(currentBaseFee, usedGas, targetGas);
        assertThat(nextFee).isEqualTo(1);
    }

    @Test
    @DisplayName("Invariant: Base fee must be persisted and loaded correctly")
    void testBaseFeePersistence() throws Exception {
        try (TestBlockchain tb = new TestBlockchain()) {
            Storage storage = tb.getStorage();
            FeeMarket.saveBaseFee(storage, 555);
            
            assertThat(FeeMarket.getCurrentBaseFee(storage)).isEqualTo(555);
        }
    }
}
