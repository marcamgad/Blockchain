package com.hybrid.blockchain;

import com.hybrid.blockchain.identity.SSIManager;
import com.hybrid.blockchain.lifecycle.DeviceLifecycleManager;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

@Tag("integration")
public class IoTLifecycleTest {

    @Test
    @DisplayName("Invariant: Device must follow lifecycle transitions: Provisioning -> Active -> Suspended -> Active")
    void testDeviceLifecycleTransitions() throws Exception {
        SSIManager ssi = new SSIManager();
        DeviceLifecycleManager lifecycle = new DeviceLifecycleManager(ssi);
        
        TestKeyPair manufacturer = new TestKeyPair(1);
        TestKeyPair device = new TestKeyPair(2);
        TestKeyPair owner = new TestKeyPair(3);
        String deviceId = "iot-sensor-001";
        
        // 1. Register Manufacturer
        lifecycle.registerManufacturer("Factory_A", manufacturer.getPublicKey());
        
        // 2. Provision Device
        // Message format: deviceId || manufacturer || model || devicePublicKey
        byte[] message = (deviceId + "Factory_A" + "ModelX").getBytes();
        byte[] combined = new byte[message.length + device.getPublicKey().length];
        System.arraycopy(message, 0, combined, 0, message.length);
        System.arraycopy(device.getPublicKey(), 0, combined, message.length, device.getPublicKey().length);
        byte[] attestation = Crypto.sign(Crypto.hash(combined), manufacturer.getPrivateKey());
        
        DeviceLifecycleManager.DeviceRecord record = lifecycle.provisionDevice(
                deviceId, "Factory_A", "ModelX", device.getPublicKey(), attestation);
        
        assertThat(record.getStatus()).isEqualTo(DeviceLifecycleManager.DeviceStatus.PROVISIONING);
        
        // 3. Activate Device
        lifecycle.activateDevice(deviceId, owner.getAddress(), device.getPublicKey());
        assertThat(record.getStatus()).isEqualTo(DeviceLifecycleManager.DeviceStatus.ACTIVE);
        assertThat(record.getOwner()).isEqualTo(owner.getAddress());
        assertThat(record.getDid()).startsWith("did:iot:");
        
        // 4. Suspend Device
        lifecycle.suspendDevice(deviceId, owner.getAddress(), "MAINTENANCE");
        assertThat(record.getStatus()).isEqualTo(DeviceLifecycleManager.DeviceStatus.SUSPENDED);
        assertThat(lifecycle.isDeviceOperational(deviceId)).isFalse();
        
        // 5. Resume Device
        lifecycle.resumeDevice(deviceId, owner.getAddress());
        assertThat(record.getStatus()).isEqualTo(DeviceLifecycleManager.DeviceStatus.ACTIVE);
        assertThat(lifecycle.isDeviceOperational(deviceId)).isTrue();
    }

    @Test
    @DisplayName("Security: Device firmware update must only be allowed by owner")
    void testFirmwareUpdateSecurity() {
        SSIManager ssi = new SSIManager();
        DeviceLifecycleManager lifecycle = new DeviceLifecycleManager(ssi);
        TestKeyPair manufacturer = new TestKeyPair(1);
        TestKeyPair device = new TestKeyPair(2);
        TestKeyPair owner = new TestKeyPair(3);
        TestKeyPair hacker = new TestKeyPair(4);
        String deviceId = "iot-actuator-01";
        
        lifecycle.registerManufacturer("Factory_A", manufacturer.getPublicKey());
        byte[] msg = (deviceId + "Factory_A" + "ModelY").getBytes();
        byte[] combined = new byte[msg.length + device.getPublicKey().length];
        System.arraycopy(msg, 0, combined, 0, msg.length);
        System.arraycopy(device.getPublicKey(), 0, combined, msg.length, device.getPublicKey().length);
        byte[] att = Crypto.sign(Crypto.hash(combined), manufacturer.getPrivateKey());
        
        lifecycle.provisionDevice(deviceId, "Factory_A", "ModelY", device.getPublicKey(), att);
        lifecycle.activateDevice(deviceId, owner.getAddress(), device.getPublicKey());
        
        // Unauthorized update
        assertThatThrownBy(() -> lifecycle.updateFirmware(deviceId, "v2.0", new byte[32], hacker.getAddress()))
                .isInstanceOf(SecurityException.class);
        
        // Authorized update
        lifecycle.updateFirmware(deviceId, "v2.0", new byte[32], owner.getAddress());
        assertThat(lifecycle.getDeviceRecord(deviceId).getFirmwareVersion()).isEqualTo("v2.0");
    }

    @Test
    @DisplayName("Security: Revoked device MUST have its DID revoked in SSI manager")
    void testRevocationChain() {
        SSIManager ssi = new SSIManager();
        DeviceLifecycleManager lifecycle = new DeviceLifecycleManager(ssi);
        TestKeyPair v1 = new TestKeyPair(1);
        TestKeyPair d1 = new TestKeyPair(2);
        TestKeyPair o1 = new TestKeyPair(3);
        String deviceId = "cam-01";
        
        lifecycle.registerManufacturer("M", v1.getPublicKey());
        byte[] msg = (deviceId + "M" + "C1").getBytes();
        byte[] combined = new byte[msg.length + d1.getPublicKey().length];
        System.arraycopy(msg, 0, combined, 0, msg.length);
        System.arraycopy(d1.getPublicKey(), 0, combined, msg.length, d1.getPublicKey().length);
        byte[] att = Crypto.sign(Crypto.hash(combined), v1.getPrivateKey());
        
        lifecycle.provisionDevice(deviceId, "M", "C1", d1.getPublicKey(), att);
        lifecycle.activateDevice(deviceId, o1.getAddress(), d1.getPublicKey());
        
        String did = lifecycle.getDeviceRecord(deviceId).getDid();
        assertThat(ssi.isDIDRevoked(did)).isFalse();
        
        lifecycle.revokeDevice(deviceId, o1.getAddress(), "COMPROMISED");
        
        assertThat(lifecycle.getDeviceRecord(deviceId).getStatus()).isEqualTo(DeviceLifecycleManager.DeviceStatus.REVOKED);
        assertThat(ssi.isDIDRevoked(did)).isTrue();
    }
}
