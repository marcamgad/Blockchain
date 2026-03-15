package com.hybrid.blockchain;

import com.hybrid.blockchain.lifecycle.DeviceLifecycleManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@Tag("integration")
class IoTLifecycleTest {

    private AccountState state;
    private DeviceLifecycleManager lifecycle;
    private String owner;
    private BigInteger manufacturerPriv;
    private String manufacturer;

    @BeforeEach
    void setUp() {
        state = new AccountState();
        lifecycle = state.getLifecycleManager();

        owner = Crypto.deriveAddress(Crypto.derivePublicKey(BigInteger.valueOf(6001)));
        manufacturerPriv = BigInteger.valueOf(6002);
        manufacturer = "mfg-1";
        lifecycle.registerManufacturer(manufacturer, Crypto.derivePublicKey(manufacturerPriv));
        lifecycle.setCurrentBlockHeight(1);
    }

    private byte[] manufacturerAttestation(String deviceId, String model, byte[] devicePub) {
        byte[] message = (deviceId + manufacturer + model).getBytes();
        byte[] combined = new byte[message.length + devicePub.length];
        System.arraycopy(message, 0, combined, 0, message.length);
        System.arraycopy(devicePub, 0, combined, message.length, devicePub.length);
        return Crypto.sign(Crypto.hash(combined), manufacturerPriv);
    }

    @Test
    @DisplayName("PROVISION transitions device into PROVISIONING state")
    void provisionLandsInBlock() {
        String deviceId = "dev-1";
        byte[] devicePub = Crypto.derivePublicKey(BigInteger.valueOf(7001));
        lifecycle.provisionDevice(deviceId, manufacturer, "m1", devicePub, manufacturerAttestation(deviceId, "m1", devicePub));

        assertEquals(DeviceLifecycleManager.DeviceStatus.PROVISIONING, lifecycle.getDeviceRecord(deviceId).getStatus(), "Provisioned device must enter PROVISIONING state");
    }

    @Test
    @DisplayName("ACTIVATE after PROVISION transitions to ACTIVE")
    void activateAfterProvision() {
        String deviceId = "dev-2";
        byte[] devicePub = Crypto.derivePublicKey(BigInteger.valueOf(7002));
        lifecycle.provisionDevice(deviceId, manufacturer, "m2", devicePub, manufacturerAttestation(deviceId, "m2", devicePub));
        lifecycle.activateDevice(deviceId, owner, devicePub);

        assertEquals(DeviceLifecycleManager.DeviceStatus.ACTIVE, lifecycle.getDeviceRecord(deviceId).getStatus(), "ACTIVATE after PROVISION must transition to ACTIVE");
    }

    @Test
    @DisplayName("ACTIVATE before PROVISION throws")
    void activateBeforeProvisionThrows() {
        Exception ex = assertThrows(Exception.class, () -> lifecycle.activateDevice("missing", owner, Crypto.derivePublicKey(BigInteger.valueOf(1))), "ACTIVATE before PROVISION must throw for missing device");
        assertTrue(ex.getMessage().toLowerCase().contains("not found") || ex.getMessage().toLowerCase().contains("device"), "Error must indicate missing device state");
    }

    @Test
    @DisplayName("SUSPEND from ACTIVE transitions to SUSPENDED")
    void suspendTransitions() {
        String deviceId = "dev-3";
        byte[] devicePub = Crypto.derivePublicKey(BigInteger.valueOf(7003));
        lifecycle.provisionDevice(deviceId, manufacturer, "m3", devicePub, manufacturerAttestation(deviceId, "m3", devicePub));
        lifecycle.activateDevice(deviceId, owner, devicePub);
        lifecycle.suspendDevice(deviceId, owner, "maintenance");

        assertEquals(DeviceLifecycleManager.DeviceStatus.SUSPENDED, lifecycle.getDeviceRecord(deviceId).getStatus(), "SUSPEND from ACTIVE must transition to SUSPENDED");
    }

    @Test
    @DisplayName("RESUME from SUSPENDED transitions back to ACTIVE")
    void resumeTransitions() {
        String deviceId = "dev-4";
        byte[] devicePub = Crypto.derivePublicKey(BigInteger.valueOf(7004));
        lifecycle.provisionDevice(deviceId, manufacturer, "m4", devicePub, manufacturerAttestation(deviceId, "m4", devicePub));
        lifecycle.activateDevice(deviceId, owner, devicePub);
        lifecycle.suspendDevice(deviceId, owner, "maintenance");
        lifecycle.resumeDevice(deviceId, owner);

        assertEquals(DeviceLifecycleManager.DeviceStatus.ACTIVE, lifecycle.getDeviceRecord(deviceId).getStatus(), "RESUME from SUSPENDED must return device to ACTIVE");
    }

    @Test
    @DisplayName("REVOKE transitions device to permanent REVOKED")
    void revokePermanent() {
        String deviceId = "dev-5";
        byte[] devicePub = Crypto.derivePublicKey(BigInteger.valueOf(7005));
        lifecycle.provisionDevice(deviceId, manufacturer, "m5", devicePub, manufacturerAttestation(deviceId, "m5", devicePub));
        lifecycle.activateDevice(deviceId, owner, devicePub);
        lifecycle.revokeDevice(deviceId, owner, "compromised");

        assertEquals(DeviceLifecycleManager.DeviceStatus.REVOKED, lifecycle.getDeviceRecord(deviceId).getStatus(), "REVOKE must move device into permanent REVOKED state");
        assertThrows(Exception.class, () -> lifecycle.activateDevice(deviceId, owner, devicePub), "ACTIVATE after REVOKE must be rejected");
    }

    @Test
    @DisplayName("Firmware update requires owner authorization")
    void firmwareAuthorization() {
        String deviceId = "dev-6";
        byte[] devicePub = Crypto.derivePublicKey(BigInteger.valueOf(7006));
        lifecycle.provisionDevice(deviceId, manufacturer, "m6", devicePub, manufacturerAttestation(deviceId, "m6", devicePub));
        lifecycle.activateDevice(deviceId, owner, devicePub);

        assertDoesNotThrow(() -> lifecycle.updateFirmware(deviceId, "1.0.1", Crypto.hash("fw".getBytes()), owner), "Firmware update by owner must succeed");
        Exception ex = assertThrows(Exception.class, () -> lifecycle.updateFirmware(deviceId, "1.0.2", Crypto.hash("fw2".getBytes()), "hb-unauthorized"), "Firmware update by non-owner must be rejected");
        assertTrue(ex.getMessage().toLowerCase().contains("owner"), "Unauthorized firmware update must mention owner authorization");
    }

    @Test
    @DisplayName("Lifecycle sequence survives AccountState toJSON/fromMap round-trip")
    void lifecycleRoundTripPreserved() {
        String deviceId = "dev-7";
        byte[] devicePub = Crypto.derivePublicKey(BigInteger.valueOf(7007));
        lifecycle.provisionDevice(deviceId, manufacturer, "m7", devicePub, manufacturerAttestation(deviceId, "m7", devicePub));
        lifecycle.activateDevice(deviceId, owner, devicePub);
        lifecycle.suspendDevice(deviceId, owner, "pause");
        lifecycle.resumeDevice(deviceId, owner);
        lifecycle.revokeDevice(deviceId, owner, "retire");

        AccountState restored = AccountState.fromMap(state.toJSON());
        assertEquals(DeviceLifecycleManager.DeviceStatus.REVOKED, restored.getLifecycleManager().getDeviceRecord(deviceId).getStatus(), "Full lifecycle state must persist through AccountState serialization round-trip");
    }

    @Test
    @DisplayName("Duplicate provision is rejected with already provisioned message")
    void duplicateProvisionRejected() {
        String deviceId = "dev-dup";
        byte[] devicePub = Crypto.derivePublicKey(BigInteger.valueOf(7008));
        lifecycle.provisionDevice(deviceId, manufacturer, "m8", devicePub, manufacturerAttestation(deviceId, "m8", devicePub));

        Exception ex = assertThrows(Exception.class, () -> lifecycle.provisionDevice(deviceId, manufacturer, "m8", devicePub, manufacturerAttestation(deviceId, "m8", devicePub)), "Provisioning same device ID twice must be rejected");
        assertTrue(ex.getMessage().toLowerCase().contains("already"), "Duplicate provision rejection must mention already provisioned device");
    }

    @Test
    @DisplayName("IoT management payer with no balance fails fee check via blockchain validation")
    void insufficientFeeRejected() throws Exception {
        List<Validator> validators = List.of(
                new Validator("v1", Crypto.derivePublicKey(BigInteger.valueOf(1))),
                new Validator("v2", Crypto.derivePublicKey(BigInteger.valueOf(2))),
                new Validator("v3", Crypto.derivePublicKey(BigInteger.valueOf(3))),
                new Validator("v4", Crypto.derivePublicKey(BigInteger.valueOf(4)))
        );
        Storage storage = new Storage(java.nio.file.Files.createTempDirectory("iot-fee-").toString(), HexUtils.decode("00112233445566778899aabbccddeeff00112233445566778899aabbccddeeff"));
        Blockchain chain = new Blockchain(storage, new Mempool(), new PoAConsensus(validators));
        chain.init();

        BigInteger lowPriv = BigInteger.valueOf(6003);
        byte[] lowPub = Crypto.derivePublicKey(lowPriv);
        Transaction tx = new Transaction.Builder().type(Transaction.Type.IOT_MANAGEMENT).to("iot").amount(0).fee(100).nonce(1).data("{}".getBytes()).sign(lowPriv, lowPub);
        Exception ex = assertThrows(Exception.class, () -> chain.validateTransaction(tx), "IOT_MANAGEMENT sender without fee balance must be rejected");
        assertTrue(ex.getMessage().toLowerCase().contains("fee") || ex.getMessage().toLowerCase().contains("funds"), "Insufficient IoT fee rejection must mention fee/funds");
        chain.shutdown();
    }
}
