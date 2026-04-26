package com.hybrid.blockchain.lifecycle;

import com.hybrid.blockchain.*;
import com.hybrid.blockchain.testutil.*;
import org.junit.jupiter.api.*;
import static org.assertj.core.api.Assertions.*;
import java.util.List;

/**
 * Integration tests for IoT Device Lifecycle management.
 * Covers the full state machine of device provisioning and activity.
 */
@Tag("iot")
public class DeviceLifecycleCompleteTest {

    private TestBlockchain tb;
    private Blockchain blockchain;
    private DeviceLifecycleManager manager;

    @BeforeEach
    void setUp() throws Exception {
        tb = new TestBlockchain();
        blockchain = tb.getBlockchain();
        manager = blockchain.getDeviceLifecycleManager();
    }

    @AfterEach
    void tearDown() throws Exception {
        if (tb != null) tb.close();
    }

    @Test
    @DisplayName("DL1.1 — Full state machine")
    void testFullStateMachine() throws Exception {
        String deviceId = "DEV-001";
        TestKeyPair manufacturer = new TestKeyPair(1);
        TestKeyPair owner = new TestKeyPair(2);
        TestKeyPair device = new TestKeyPair(3);
        
        blockchain.getAccountState().credit(manufacturer.getAddress(), 1000L);
        blockchain.getAccountState().credit(owner.getAddress(), 1000L);
        manager.registerManufacturer("MAN", manufacturer.getPublicKey());
        
        // 1. PROVISION
        applyAction("PROVISION", manufacturer, deviceId, "MAN", "X1", device.getPublicKey(), null);
        assertThat(manager.getDeviceRecord(deviceId).getStatus()).isEqualTo(DeviceLifecycleManager.DeviceStatus.PROVISIONING);
        
        // 2. ACTIVATE
        applyAction("ACTIVATE", owner, deviceId, null, null, device.getPublicKey(), owner.getAddress());
        assertThat(manager.getDeviceRecord(deviceId).getStatus()).isEqualTo(DeviceLifecycleManager.DeviceStatus.ACTIVE);
        
        // 3. SUSPEND
        applyAction("SUSPEND", owner, deviceId, null, null, null, null);
        assertThat(manager.getDeviceRecord(deviceId).getStatus()).isEqualTo(DeviceLifecycleManager.DeviceStatus.SUSPENDED);
        
        // 4. RESUME
        applyAction("RESUME", owner, deviceId, null, null, null, null);
        assertThat(manager.getDeviceRecord(deviceId).getStatus()).isEqualTo(DeviceLifecycleManager.DeviceStatus.ACTIVE);
        
        // 5. REVOKE
        applyAction("REVOKE", owner, deviceId, null, null, null, null);
        assertThat(manager.getDeviceRecord(deviceId).getStatus()).isEqualTo(DeviceLifecycleManager.DeviceStatus.REVOKED);
    }

    @Test
    @DisplayName("DL1.9-1.10 — reputation updates")
    void testReputationLogic() throws Exception {
        String id = "REP-DEV";
        setupActiveDevice(id, new TestKeyPair(5));
        
        double initial = manager.getDeviceRecord(id).getReputationScore();
        manager.recordDeviceActivity(id, true);
        assertThat(manager.getDeviceRecord(id).getReputationScore()).isGreaterThan(initial);
        
        double mid = manager.getDeviceRecord(id).getReputationScore();
        manager.recordDeviceActivity(id, false);
        assertThat(manager.getDeviceRecord(id).getReputationScore()).isLessThan(mid);
    }

    private void setupActiveDevice(String deviceId, TestKeyPair device) throws Exception {
        TestKeyPair man = new TestKeyPair(10);
        blockchain.getAccountState().credit(man.getAddress(), 1000L);
        manager.registerManufacturer("M", man.getPublicKey());
        applyAction("PROVISION", man, deviceId, "M", "M1", device.getPublicKey(), null);
        applyAction("ACTIVATE", man, deviceId, null, null, device.getPublicKey(), man.getAddress());
    }

    private void applyAction(String action, TestKeyPair actor, String deviceId, String man, String model, byte[] devPub, String owner) throws Exception {
        java.util.Map<String, Object> data = new java.util.HashMap<>();
        data.put("action", action);
        data.put("deviceId", deviceId);
        if (man != null) data.put("manufacturer", man);
        if (model != null) data.put("model", model);
        if (devPub != null) data.put("devicePublicKey", Crypto.bytesToHex(devPub));
        if (owner != null) data.put("owner", owner);
        
        if (action.equals("PROVISION")) {
            byte[] message = (deviceId + man + model).getBytes();
            byte[] combined = new byte[message.length + devPub.length];
            System.arraycopy(message, 0, combined, 0, message.length);
            System.arraycopy(devPub, 0, combined, message.length, devPub.length);
            data.put("signature", Crypto.bytesToHex(Crypto.sign(Crypto.hash(combined), actor.getPrivateKey())));
        }
        
        Transaction tx = new Transaction.Builder()
                .type(Transaction.Type.IOT_MANAGEMENT)
                .from(actor.getAddress())
                .data(new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsBytes(data))
                .nonce(blockchain.getAccountState().getNonce(actor.getAddress()) + 1)
                .sign(actor.getPrivateKey(), actor.getPublicKey());
        BlockApplier.createAndApplyBlock(tb, List.of(tx));
    }
}
