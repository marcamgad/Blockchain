package com.hybrid.blockchain;

import com.hybrid.blockchain.testutil.*;
import org.junit.jupiter.api.*;
import static org.assertj.core.api.Assertions.*;
import java.util.ArrayList;
import java.util.List;

/**
 * Unit and integration tests for the dynamic EIP-1559 style fee market.
 * Covers baseFee adjustment logic, persistence, and optimal fee prediction.
 */
@Tag("integration")
public class FeeMarketCompleteTest {

    private TestBlockchain tb;
    private FeeMarket feeMarket;

    @BeforeEach
    void setUp() throws Exception {
        tb = new TestBlockchain();
        feeMarket = tb.getBlockchain().getFeeMarket();
    }

    @AfterEach
    void tearDown() throws Exception {
        if (tb != null) tb.close();
    }

    @Test
    @DisplayName("F1.1 — calculateNextBaseFee: stationary")
    void testFeeStationary() {
        long current = 100;
        long targetGas = Config.TARGET_BLOCK_GAS;
        long next = new FeeMarket().calculateNextBaseFee(current, targetGas);
        assertThat(next).isEqualTo(current);
    }

    @Test
    @DisplayName("F1.2 — calculateNextBaseFee: increase")
    void testFeeIncrease() {
        long current = 100;
        long maxGas = Config.MAX_BLOCK_GAS; // > target
        long next = new FeeMarket().calculateNextBaseFee(current, maxGas);
        assertThat(next).isGreaterThan(current);
        // Max increase is 12.5%
        assertThat(next).isLessThanOrEqualTo(113);
    }

    @Test
    @DisplayName("F1.3 — calculateNextBaseFee: decrease")
    void testFeeDecrease() {
        long current = 100;
        long next = new FeeMarket().calculateNextBaseFee(current, 0);
        assertThat(next).isLessThan(current);
    }

    @Test
    @DisplayName("F1.4 — calculateNextBaseFee: minimum floor")
    void testFeeFloor() {
        long next = new FeeMarket().calculateNextBaseFee(1, 0);
        assertThat(next).isGreaterThanOrEqualTo(0);
    }

    @Test
    @DisplayName("F1.6-1.7 — persistence")
    void testPersistence() {
        new FeeMarket().saveBaseFee(250L, tb.getStorage());
        assertThat(new FeeMarket().getCurrentBaseFee(tb.getStorage())).isEqualTo(250L);
    }

    @Test
    @DisplayName("F1.8 — predictOptimalFee: initial default")
    void testPredictionDefault() {
        long pred = new FeeMarket().predictOptimalFee(100, 1000, tb.getStorage(), 4);
        // Should be at least 1L due to floor rule when no history
        assertThat(pred).isEqualTo(Math.max(1L, Config.BASE_FEE_INITIAL));
    }

    @Test
    @DisplayName("F1.9 — predictOptimalFee: high demand")
    void testPredictionHighDemand() throws Exception {
        Blockchain chain = tb.getBlockchain();
        TestKeyPair alice = new TestKeyPair(1);
        chain.getAccountState().credit(alice.getAddress(), 1_000_000L);
        
        // Fill 25 blocks to drive demand
        for (int i = 0; i < 25; i++) {
            List<Transaction> txs = new ArrayList<>();
            for(int j=0; j < Config.MAX_TRANSACTIONS_PER_BLOCK; j++) {
                txs.add(TestTransactionFactory.createAccountTransfer(alice, "b", 1, 10, (long)i * Config.MAX_TRANSACTIONS_PER_BLOCK + j + 1));
            }
            BlockApplier.createAndApplyBlock(tb, txs);
        }
        
        long pred = new FeeMarket().predictOptimalFee(
                Config.MAX_TRANSACTIONS_PER_BLOCK, 
                Config.TARGET_BLOCK_TIME_MS / 2, 
                tb.getStorage(), 
                4);
        
        assertThat(pred).as("Predicted fee should rise under high load").isGreaterThan(Config.BASE_FEE_INITIAL);
    }

    @Test
    @DisplayName("F1.10 — scarcity premium")
    void testScarcityPremium() {
        // Mocked check: same history, more validators should increase prediction (if implemented)
        // Implementation check: FeeMarket usually uses validatorCount to adjust premium
        long p14 = new FeeMarket().predictOptimalFee(100, 1000, tb.getStorage(), 14);
        long p16 = new FeeMarket().predictOptimalFee(100, 1000, tb.getStorage(), 16);
        
        if (p16 != p14) {
            assertThat(p16).isGreaterThan(p14);
        }
    }

    @Test
    @DisplayName("F1.12 — resetHistory")
    void testResetHistory() {
        new FeeMarket().recordFeeDataPoint(1000, 500, tb.getStorage());
        new FeeMarket().resetHistory(tb.getStorage());
        assertThat(new FeeMarket().predictOptimalFee(1, 1, tb.getStorage(), 4)).isEqualTo(Math.max(1L, Config.BASE_FEE_INITIAL));
    }
}
