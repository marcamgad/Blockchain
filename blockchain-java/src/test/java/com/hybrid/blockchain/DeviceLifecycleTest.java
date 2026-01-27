package com.hybrid.blockchain;

import com.hybrid.blockchain.identity.SSIManager;
import com.hybrid.blockchain.lifecycle.DeviceLifecycleManager;
import com.hybrid.blockchain.lifecycle.DeviceLifecycleManager.DeviceRecord;
import com.hybrid.blockchain.lifecycle.DeviceLifecycleManager.DeviceStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Comprehensive tests for Device Lifecycle Management
 */
public class DeviceLifecycleTest {

    private SSIManager ssiManager;
    private DeviceLifecycleManager lifecycleManager;
    private String manufacturerId;
    private BigInteger manufacturerPrivateKey;
    private byte[] manufacturerPublicKey;
    private String ownerAddress;

    @BeforeEach
    public void setUp() {
        ssiManager = new SSIManager();
        lifecycleManager = new DeviceLifecycleManager(ssiManager);

        // Setup manufacturer
        manufacturerId = "manufacturer-001";
        manufacturerPrivateKey = new BigInteger("9999999999999999999999999999999999999999999999999999999999999999", 16);
        manufacturerPublicKey = Crypto.derivePublicKey(manufacturerPrivateKey);
        lifecycleManager.registerManufacturer(manufacturerId, manufacturerPublicKey);

        // Setup owner
        BigInteger ownerPrivateKey = new BigInteger("8888888888888888888888888888888888888888888888888888888888888888",
                16);
        byte[] ownerPublicKey = Crypto.derivePublicKey(ownerPrivateKey);
        ownerAddress = Crypto.deriveAddress(ownerPublicKey);

        // Set blockchain height
        lifecycleManager.setCurrentBlockHeight(100);
    }

    @Test
    public void testDeviceProvisioning() {
        String deviceId = "sensor-001";
        String model = "TempSensor-X1";
        byte[] devicePublicKey = Crypto
                .derivePublicKey(
                        new BigInteger("1111111111111111111111111111111111111111111111111111111111111111", 16));

        // Create manufacturer attestation
        byte[] attestation = createManufacturerAttestation(deviceId, model, devicePublicKey);

        // Provision device
        DeviceRecord record = lifecycleManager.provisionDevice(
                deviceId,
                manufacturerId,
                model,
                devicePublicKey,
                attestation);

        // Verify device record
        assertNotNull(record);
        assertEquals(deviceId, record.getDeviceId());
        assertEquals(manufacturerId, record.getManufacturer());
        assertEquals(model, record.getModel());
        assertEquals(DeviceStatus.PROVISIONING, record.getStatus());
        assertEquals(100, record.getRegistrationBlock());
    }

    @Test
    public void testDeviceActivation() {
        String deviceId = "sensor-002";
        String model = "HumiditySensor-Y2";
        byte[] devicePublicKey = Crypto
                .derivePublicKey(
                        new BigInteger("2222222222222222222222222222222222222222222222222222222222222222", 16));

        // Provision device
        byte[] attestation = createManufacturerAttestation(deviceId, model, devicePublicKey);
        lifecycleManager.provisionDevice(deviceId, manufacturerId, model, devicePublicKey, attestation);

        // Activate device
        lifecycleManager.activateDevice(deviceId, ownerAddress, devicePublicKey);

        // Verify device is active
        DeviceRecord record = lifecycleManager.getDeviceRecord(deviceId);
        assertEquals(DeviceStatus.ACTIVE, record.getStatus());
        assertEquals(ownerAddress, record.getOwner());
        assertNotNull(record.getDid());
        assertEquals("did:iot:" + deviceId, record.getDid());

        // Verify device is operational
        assertTrue(lifecycleManager.isDeviceOperational(deviceId));
    }

    @Test
    public void testFirmwareUpdate() {
        String deviceId = "sensor-003";
        String model = "PressureSensor-Z3";
        byte[] devicePublicKey = Crypto
                .derivePublicKey(
                        new BigInteger("3333333333333333333333333333333333333333333333333333333333333333", 16));

        // Provision and activate device
        byte[] attestation = createManufacturerAttestation(deviceId, model, devicePublicKey);
        lifecycleManager.provisionDevice(deviceId, manufacturerId, model, devicePublicKey, attestation);
        lifecycleManager.activateDevice(deviceId, ownerAddress, devicePublicKey);

        // Update firmware
        String newVersion = "v2.0.1";
        byte[] firmwareHash = Crypto.hash("firmware-v2.0.1".getBytes());
        lifecycleManager.updateFirmware(deviceId, newVersion, firmwareHash, ownerAddress);

        // Verify firmware update
        DeviceRecord record = lifecycleManager.getDeviceRecord(deviceId);
        assertEquals(newVersion, record.getFirmwareVersion());
        assertEquals(1, record.getFirmwareHistory().size());
        assertEquals(newVersion, record.getFirmwareHistory().get(0).getVersion());
    }

    @Test
    public void testDeviceSuspension() {
        String deviceId = "sensor-004";
        String model = "MotionSensor-M4";
        byte[] devicePublicKey = Crypto
                .derivePublicKey(
                        new BigInteger("4444444444444444444444444444444444444444444444444444444444444444", 16));

        // Provision and activate device
        byte[] attestation = createManufacturerAttestation(deviceId, model, devicePublicKey);
        lifecycleManager.provisionDevice(deviceId, manufacturerId, model, devicePublicKey, attestation);
        lifecycleManager.activateDevice(deviceId, ownerAddress, devicePublicKey);

        // Suspend device
        lifecycleManager.suspendDevice(deviceId, ownerAddress, "Maintenance required");

        // Verify device is suspended
        DeviceRecord record = lifecycleManager.getDeviceRecord(deviceId);
        assertEquals(DeviceStatus.SUSPENDED, record.getStatus());
        assertFalse(lifecycleManager.isDeviceOperational(deviceId));

        // Resume device
        lifecycleManager.resumeDevice(deviceId, ownerAddress);

        // Verify device is active again
        record = lifecycleManager.getDeviceRecord(deviceId);
        assertEquals(DeviceStatus.ACTIVE, record.getStatus());
        assertTrue(lifecycleManager.isDeviceOperational(deviceId));
    }

    @Test
    public void testDeviceRevocation() {
        String deviceId = "sensor-005";
        String model = "LightSensor-L5";
        byte[] devicePublicKey = Crypto
                .derivePublicKey(
                        new BigInteger("5555555555555555555555555555555555555555555555555555555555555555", 16));

        // Provision and activate device
        byte[] attestation = createManufacturerAttestation(deviceId, model, devicePublicKey);
        lifecycleManager.provisionDevice(deviceId, manufacturerId, model, devicePublicKey, attestation);
        lifecycleManager.activateDevice(deviceId, ownerAddress, devicePublicKey);

        // Revoke device
        lifecycleManager.revokeDevice(deviceId, ownerAddress, "Security breach detected");

        // Verify device is revoked
        DeviceRecord record = lifecycleManager.getDeviceRecord(deviceId);
        assertEquals(DeviceStatus.REVOKED, record.getStatus());
        assertFalse(lifecycleManager.isDeviceOperational(deviceId));

        // Verify DID is revoked
        assertThrows(SecurityException.class, () -> {
            ssiManager.resolveDID(record.getDid());
        });
    }

    @Test
    public void testInvalidStateTransitions() {
        String deviceId = "sensor-006";
        String model = "SoundSensor-S6";
        byte[] devicePublicKey = Crypto
                .derivePublicKey(
                        new BigInteger("6666666666666666666666666666666666666666666666666666666666666666", 16));

        // Provision device
        byte[] attestation = createManufacturerAttestation(deviceId, model, devicePublicKey);
        lifecycleManager.provisionDevice(deviceId, manufacturerId, model, devicePublicKey, attestation);

        // Activate device first so it has an owner
        lifecycleManager.activateDevice(deviceId, ownerAddress, devicePublicKey);

        // Try to activate again (should fail - already active)
        assertThrows(IllegalStateException.class, () -> {
            lifecycleManager.activateDevice(deviceId, ownerAddress, devicePublicKey);
        });

        // Suspend device
        lifecycleManager.suspendDevice(deviceId, ownerAddress, "Test");

        // Try to suspend again (should fail - already suspended)
        assertThrows(IllegalStateException.class, () -> {
            lifecycleManager.suspendDevice(deviceId, ownerAddress, "Test");
        });
    }

    @Test
    public void testUnauthorizedOperations() {
        String deviceId = "sensor-007";
        String model = "GasSensor-G7";
        byte[] devicePublicKey = Crypto
                .derivePublicKey(
                        new BigInteger("7777777777777777777777777777777777777777777777777777777777777777", 16));

        // Provision and activate device
        byte[] attestation = createManufacturerAttestation(deviceId, model, devicePublicKey);
        lifecycleManager.provisionDevice(deviceId, manufacturerId, model, devicePublicKey, attestation);
        lifecycleManager.activateDevice(deviceId, ownerAddress, devicePublicKey);

        String unauthorizedAddress = "hb0000000000000000000000000000000000000000";

        // Try to update firmware as non-owner (should fail)
        assertThrows(SecurityException.class, () -> {
            lifecycleManager.updateFirmware(deviceId, "v2.0", new byte[32], unauthorizedAddress);
        });

        // Try to suspend as non-owner (should fail)
        assertThrows(SecurityException.class, () -> {
            lifecycleManager.suspendDevice(deviceId, unauthorizedAddress, "Test");
        });
    }

    @Test
    public void testInvalidManufacturerAttestation() {
        String deviceId = "sensor-008";
        String model = "WaterSensor-W8";
        byte[] devicePublicKey = Crypto
                .derivePublicKey(
                        new BigInteger("8888888888888888888888888888888888888888888888888888888888888889", 16));

        // Create invalid attestation (wrong signature)
        byte[] invalidAttestation = new byte[64];

        // Try to provision with invalid attestation (should fail)
        assertThrows(SecurityException.class, () -> {
            lifecycleManager.provisionDevice(deviceId, manufacturerId, model, devicePublicKey, invalidAttestation);
        });
    }

    @Test
    public void testMultipleDevicesPerOwner() {
        // Provision and activate multiple devices
        for (int i = 0; i < 5; i++) {
            String deviceId = "multi-device-" + i;
            String model = "MultiSensor-" + i;
            byte[] devicePublicKey = Crypto.derivePublicKey(new BigInteger(String.valueOf(1000 + i)));

            byte[] attestation = createManufacturerAttestation(deviceId, model, devicePublicKey);
            lifecycleManager.provisionDevice(deviceId, manufacturerId, model, devicePublicKey, attestation);
            lifecycleManager.activateDevice(deviceId, ownerAddress, devicePublicKey);
        }

        // Verify owner has 5 devices
        List<DeviceRecord> devices = lifecycleManager.getDevicesOwnedBy(ownerAddress);
        assertEquals(5, devices.size());
    }

    @Test
    public void testLifecycleStats() {
        // Create devices in different states
        for (int i = 0; i < 3; i++) {
            String deviceId = "stats-device-" + i;
            byte[] devicePublicKey = Crypto.derivePublicKey(new BigInteger(String.valueOf(2000 + i)));
            byte[] attestation = createManufacturerAttestation(deviceId, "Model-" + i, devicePublicKey);
            lifecycleManager.provisionDevice(deviceId, manufacturerId, "Model-" + i, devicePublicKey, attestation);

            if (i > 0) {
                lifecycleManager.activateDevice(deviceId, ownerAddress, devicePublicKey);
            }
        }

        var stats = lifecycleManager.getStats();
        assertEquals(3, stats.get("totalDevices"));
        assertEquals(1, stats.get("trustedManufacturers"));
    }

    /**
     * Helper: Create manufacturer attestation signature
     */
    private byte[] createManufacturerAttestation(String deviceId, String model, byte[] devicePublicKey) {
        byte[] message = (deviceId + manufacturerId + model).getBytes();
        byte[] combined = new byte[message.length + devicePublicKey.length];
        System.arraycopy(message, 0, combined, 0, message.length);
        System.arraycopy(devicePublicKey, 0, combined, message.length, devicePublicKey.length);

        return Crypto.sign(Crypto.hash(combined), manufacturerPrivateKey);
    }
}
