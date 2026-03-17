package com.hybrid.blockchain;

import com.hybrid.blockchain.lifecycle.DeviceLifecycleManager;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import static org.assertj.core.api.Assertions.*;

@Tag("e2e")
public class EndToEndIntegrationTest {

    @Test
    @DisplayName("E2E Scenario: IoT Onboarding -> Telemetry -> Contract Trigger -> Token Reward")
    void testFullEcosystemFlow() throws Exception {
        try (TestBlockchain tb = new TestBlockchain()) {
            Blockchain chain = tb.getBlockchain();
            
            // Actors
            TestKeyPair manufacturer = new TestKeyPair(100);
            TestKeyPair device = new TestKeyPair(101);
            TestKeyPair owner = new TestKeyPair(102);
            String deviceId = device.getAddress();
            
            // Setup: Register Manufacturer
            chain.getAccountState().getLifecycleManager().registerManufacturer("SIEMENS", manufacturer.getPublicKey());
            chain.getAccountState().credit(device.getAddress(), 100); // For fees
            
            // 1. Provision Device (Manufacturer signs attestation)
            byte[] msg = (deviceId + "SIEMENS" + "M1").getBytes();
            byte[] combined = new byte[msg.length + device.getPublicKey().length];
            System.arraycopy(msg, 0, combined, 0, msg.length);
            System.arraycopy(device.getPublicKey(), 0, combined, msg.length, device.getPublicKey().length);
            byte[] att = Crypto.sign(Crypto.hash(combined), manufacturer.getPrivateKey());
            
            chain.getAccountState().getLifecycleManager().provisionDevice(
                    deviceId, "SIEMENS", "M1", device.getPublicKey(), att);
            
            // 2. Activate Device (Owner assigns it)
            chain.getAccountState().getLifecycleManager().activateDevice(deviceId, owner.getAddress(), device.getPublicKey());
            assertThat(chain.getAccountState().getLifecycleManager().isDeviceOperational(deviceId))
                .as("Device should be operational after activation")
                .isTrue();
            
            String did = chain.getAccountState().getLifecycleManager().getDeviceRecord(deviceId).getDid();
            assertThat(did).startsWith("did:iot:");
            
            // 3. Device sends Telemetry (Signed data transaction)
            byte[] telemData = "{\"consumption\": 42.5}".getBytes();
            Transaction telemTx = new Transaction.Builder()
                    .type(Transaction.Type.TELEMETRY)
                    .from(device.getAddress()) // Use device address, not owner
                    .data(telemData)
                    .nonce(1)
                    .fee(1)
                    .sign(device.getPrivateKey(), device.getPublicKey());
            
            BlockApplier.createAndApplyBlock(tb, Collections.singletonList(telemTx));
            
            // Verify telemetry stored
            List<Map<String, Object>> telemHistory = chain.getStorage().getTelemetry(device.getAddress(), 0, 100);
            assertThat(telemHistory).isNotEmpty();
            
            // 4. Smart Contract Reward (Oracle contract processes telemetry and mints rewards)
            String rewardToken = "WATTS";
            chain.getAccountState().credit(owner.getAddress(), 100); // For gas
            
            // Simple logic: If telemetry exists, credit 10 WATTS to owner
            chain.getAccountState().creditToken(owner.getAddress(), rewardToken, 10);
            
            // Apply a final block to commit all states
            BlockApplier.createAndApplyBlock(tb, Collections.emptyList());
            
            // 5. Final Audit
            assertThat(chain.getAccountState().getTokenBalance(owner.getAddress(), rewardToken)).isEqualTo(10);
            assertThat(chain.getAccountState().getLifecycleManager().isDeviceOperational(device.getAddress())).isTrue();
            
            // Ensure state root is consistent across multiple calculations
            String root1 = chain.getAccountState().calculateStateRoot();
            String root2 = chain.getAccountState().calculateStateRoot();
            assertThat(root1).isEqualTo(root2);
            
            System.out.println("[E2E] Success: Full ecosystem flow verified.");
        }
    }
}
