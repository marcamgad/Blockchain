package com.hybrid.blockchain;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hybrid.blockchain.lifecycle.DeviceLifecycleManager;
import com.hybrid.blockchain.security.PUFIdentityProvider;
import org.junit.jupiter.api.*;

import java.math.BigInteger;
import java.util.*;

import static org.assertj.core.api.Assertions.*;

/**
 * Tests for Phase 1 features:
 * - P1-B: Gateway Telemetry Batching
 * - P1-C: PUF + ZKP Identity
 */
public class Phase1FeatureTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    @DisplayName("P1-B: TELEMETRY_BATCH aggregates multiple device readings into one transaction")
    void testTelemetryBatching() throws Exception {
        // Setup blockchain
        try (Storage storage = new Storage("test-batch-data", new byte[32])) {
            Mempool mempool = new Mempool();
            PoAConsensus consensus = new PoAConsensus(Collections.singletonList(new Validator("gateway-1", new byte[32])));
            Blockchain blockchain = new Blockchain(storage, mempool, consensus);
            
            AccountState state = blockchain.getAccountState();
            state.credit("gateway-1", 1000000L);
            
            // Prepare batch data: 3 readings for device-A, device-B, device-C
            List<Map<String, Object>> batch = new ArrayList<>();
            batch.add(Map.of("deviceId", "device-A", "value", 25.5, "timestamp", System.currentTimeMillis()));
            batch.add(Map.of("deviceId", "device-B", "value", 60.0, "timestamp", System.currentTimeMillis()));
            batch.add(Map.of("deviceId", "device-C", "value", 10.2, "timestamp", System.currentTimeMillis()));
            
            byte[] batchData = MAPPER.writeValueAsBytes(batch);
            BigInteger signerPrivKey = BigInteger.valueOf(12345);
            String gatewayAddr = Crypto.deriveAddress(Crypto.derivePublicKey(signerPrivKey));
            state.credit(gatewayAddr, 1000000L);
            
            Transaction tx = new Transaction.Builder()
                .type(Transaction.Type.TELEMETRY_BATCH)
                .from(gatewayAddr)
                .data(batchData)
                .fee(100L)
                .build();
            tx.sign(signerPrivKey);
            
            // Validation should pass
            blockchain.validateTransaction(tx);
            
            // Apply should process all 3 readings
            blockchain.applyTransactionToState(state, blockchain.getUTXOSet(), tx, 1, System.currentTimeMillis(), "0xhash", new ArrayList<>());
            
            assertThat(state.getBalance(gatewayAddr)).isEqualTo(1000000L - 100L);
            // Anomaly detector and reputation logic are triggered internally.
        }
    }

    @Test
    @DisplayName("P1-C: PUF-based key derivation and ZKP identity integration")
    void testPUFIdentity() throws Exception {
        String deviceId = "iot-sensor-99";
        
        // 1. Simulate PUF response from hardware
        byte[] pufResponse = PUFIdentityProvider.getSimulatedPUFResponse(deviceId);
        assertThat(pufResponse).isNotNull().hasSize(32);
        
        // 2. Derive private key from PUF
        BigInteger privKey = PUFIdentityProvider.derivePrivateKey(pufResponse);
        assertThat(privKey).isNotNull().isGreaterThan(BigInteger.ZERO);
        
        // 3. Verify derivation is deterministic
        BigInteger privKey2 = PUFIdentityProvider.derivePrivateKey(pufResponse);
        assertThat(privKey).isEqualTo(privKey2);
        
        // 4. Test lifecycle integration
        DeviceLifecycleManager lifecycle = new DeviceLifecycleManager(null);
        // Use a valid (though random) public key for the manufacturer
        byte[] mfPubKey = com.hybrid.blockchain.Crypto.derivePublicKey(BigInteger.valueOf(123));
        lifecycle.registerManufacturer("trusted-mf", mfPubKey);
        
        // Verify nullifier generation in provisionDevice
        byte[] pubKey = com.hybrid.blockchain.Crypto.derivePublicKey(privKey);
        // Provisioning logic in DeviceLifecycleManager was updated to generate PUF proof internally for simulation
        // We use a dummy 64-byte signature that might fail verification, but we want to check the record state.
        // Actually, provisionDevice verifies attestation FIRST. 
        // To bypass verification or make it pass, we'd need a valid signature.
        // Let's create a valid manufacturer signature.
        byte[] message = (deviceId + "trusted-mf" + "model-X").getBytes();
        byte[] combined = new byte[message.length + pubKey.length];
        System.arraycopy(message, 0, combined, 0, message.length);
        System.arraycopy(pubKey, 0, combined, message.length, pubKey.length);
        byte[] mfSig = com.hybrid.blockchain.Crypto.sign(com.hybrid.blockchain.Crypto.hash(combined), BigInteger.valueOf(123));

        DeviceLifecycleManager.DeviceRecord record = lifecycle.provisionDevice(
            deviceId, "trusted-mf", "model-X", pubKey, mfSig);
        
        assertThat(record.getPufNullifier()).isNotNull().isNotEmpty();
    }
}
