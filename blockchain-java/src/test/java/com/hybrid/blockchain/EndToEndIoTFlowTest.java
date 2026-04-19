package com.hybrid.blockchain;

import com.hybrid.blockchain.ai.*;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

public class EndToEndIoTFlowTest {

    @Test
    @DisplayName("Severe: End-to-End Industrial IoT Flow with AI Monitoring")
    void testFullIndustrialFlow() throws Exception {
        try (TestBlockchain tb = new TestBlockchain()) {
            Blockchain chain = tb.getBlockchain();
            com.hybrid.blockchain.ai.TelemetryAnomalyDetector.getInstance().reset();
            TestKeyPair device = new TestKeyPair(1);
            String deviceId = device.getAddress();
            
            // 1. Setup ecosystem
            chain.getAccountState().credit(deviceId, 100000);
            
            // Register device in Lifecycle Manager to allow TELEMETRY
            com.hybrid.blockchain.lifecycle.DeviceLifecycleManager lcm = chain.getAccountState().getLifecycleManager();
            TestKeyPair manufacturer = new TestKeyPair(99);
            lcm.registerManufacturer("manuf-1", manufacturer.getPublicKey());
            
            // Create valid attestation signature
            String model = "model-x";
            byte[] message = (deviceId + "manuf-1" + model).getBytes();
            byte[] devicePk = device.getPublicKey();
            byte[] combined = new byte[message.length + devicePk.length];
            System.arraycopy(message, 0, combined, 0, message.length);
            System.arraycopy(devicePk, 0, combined, message.length, devicePk.length);
            byte[] sig = com.hybrid.blockchain.Crypto.sign(com.hybrid.blockchain.Crypto.hash(combined), manufacturer.getPrivateKey());
            
            lcm.provisionDevice(deviceId, "manuf-1", model, devicePk, sig);
            lcm.activateDevice(deviceId, deviceId, devicePk);
            
            // 2. Warm up anomaly detector with benign data
            for (int i = 0; i < 15; i++) {
                Transaction tx = new Transaction.Builder()
                    .type(Transaction.Type.TELEMETRY)
                    .from(deviceId)
                    .data("20.0".getBytes())
                    .nonce(i + 1)
                    .fee(100)
                    .sign(device.getPrivateKey(), device.getPublicKey());
                BlockApplier.createAndApplyBlock(tb, Collections.singletonList(tx));
            }
            
            // 3. Capture baseline reputation after warm-up
            double repAfterWarmup = chain.getAccountState().getLifecycleManager().getDeviceRecord(deviceId).getReputationScore();
            
            // 4. Send ANOMALOUS reading
            Transaction txAnom = new Transaction.Builder()
                .type(Transaction.Type.TELEMETRY)
                .from(deviceId)
                .data("999.9".getBytes())
                .nonce(16)
                .fee(1000)
                .sign(device.getPrivateKey(), device.getPublicKey());
            
            Block bAnom = BlockApplier.createAndApplyBlock(tb, Collections.singletonList(txAnom));
            
            // 5. Verify AI Impact
            // Anomaly should be detected
            TelemetryAnomalyDetector.AnomalyStats stats = TelemetryAnomalyDetector.getInstance().getStats(deviceId);
            assertThat(stats.anomaliesDetected).isGreaterThan(0);
            
            // Reputation should be deducted
            double repAfterAnomaly = chain.getAccountState().getLifecycleManager().getDeviceRecord(deviceId).getReputationScore();
            assertThat(repAfterAnomaly).isLessThan(repAfterWarmup); 
            
            // 6. Verify Federated Learning Update
            // Telemetry tx should have triggered an update if configured
            // Federated model commits should come from a validator (leader)
            TestKeyPair validator = tb.getValidatorKey();
            Transaction txCommit = new Transaction.Builder()
                .type(Transaction.Type.FEDERATED_COMMIT)
                .from(deviceId)
                .data(new byte[32]) // dummy model hash
                .nonce(17)
                .fee(100)
                .sign(device.getPrivateKey(), device.getPublicKey());
            BlockApplier.createAndApplyBlock(tb, Collections.singletonList(txCommit));
            
            // 6. Verify Fee Market Scaling
            long currentFee = FeeMarket.getCurrentBaseFee(chain.getStorage());
            assertThat(currentFee).isGreaterThanOrEqualTo(Config.BASE_FEE_INITIAL);
        }
    }
}
