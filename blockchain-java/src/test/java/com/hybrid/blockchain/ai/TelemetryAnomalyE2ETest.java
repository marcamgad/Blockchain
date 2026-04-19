package com.hybrid.blockchain.ai;

import com.hybrid.blockchain.*;
import org.junit.jupiter.api.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

@Tag("AI")
@Tag("Scenarios")
public class TelemetryAnomalyE2ETest {

    private TestBlockchain tb;
    private Blockchain blockchain;

    @BeforeEach
    public void setup() throws Exception {
        tb = new TestBlockchain();
        blockchain = tb.getBlockchain();
        TelemetryAnomalyDetector.getInstance().reset();
    }

    @AfterEach
    public void teardown() throws Exception {
        if (tb != null) tb.close();
    }

    @Test
    @DisplayName("D3: Telemetry Anomaly 10x Fee Punishment")
    public void testTelemetryAnomalyFeePunishment() throws Exception {
        TestKeyPair device = new TestKeyPair(300);
        blockchain.getAccountState().credit(device.getAddress(), 1000);
        blockchain.getAccountState().getLifecycleManager().registerDevice(device.getAddress(), "sensor-001", "temp-sensor");

        // 1. Submit normal telemetry (Training Phase: req 10 samples)
        for (int i = 1; i <= 10; i++) {
            Transaction tx = new Transaction.Builder()
                .type(Transaction.Type.TELEMETRY)
                .from(device.getAddress())
                .data("25.0".getBytes())
                .fee(10)
                .nonce(i)
                .build();
            tx.sign(device.getPrivateKey());
            BlockApplier.createAndApplyBlock(tb, List.of(tx));
        }
        
        long balAfterNormal = blockchain.getAccountState().getBalance(device.getAddress());
        assertThat(balAfterNormal).isEqualTo(1000 - (10 * 10)); // 10 txs * 10 fee

        // 2. Submit ANOMALOUS telemetry (huge jump)
        // Data: 150.0
        Transaction anomalyTx = new Transaction.Builder()
                .type(Transaction.Type.TELEMETRY)
                .from(device.getAddress())
                .data("150.0".getBytes())
                .fee(10)
                .nonce(11)
                .build();
        anomalyTx.sign(device.getPrivateKey());
        
        BlockApplier.createAndApplyBlock(tb, List.of(anomalyTx));
        
        // Fee should be 10x (100)
        long finalBal = blockchain.getAccountState().getBalance(device.getAddress());
        assertThat(finalBal).isEqualTo(balAfterNormal - 100);
        
        // Device reputation should also be penalized (recordDeviceActivity(..., false))
        // In this implementation, reputation starts at 100 and drops by 10 for penalty.
        int rep = blockchain.getAccountState().getLifecycleManager().getDeviceReputation(device.getAddress());
        assertThat(rep).isLessThan(100);
    }
}
