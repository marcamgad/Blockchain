package com.hybrid.blockchain.lifecycle;

import com.hybrid.blockchain.AccountState;
import com.hybrid.blockchain.Crypto;
import com.hybrid.blockchain.TestKeyPair;
import com.hybrid.blockchain.identity.SSIManager;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** [HC-047] activation authorisation, [HC-048] complete device-state serialisation. */
class DeviceActivationTest {

    private static final TestKeyPair MAN = new TestKeyPair(8301), DEVICE = new TestKeyPair(8302),
            OWNER = new TestKeyPair(8303), STRANGER = new TestKeyPair(8304);

    private static DeviceLifecycleManager provisioned(String id) {
        DeviceLifecycleManager lcm = new DeviceLifecycleManager(new SSIManager());
        lcm.registerManufacturer("MAN", MAN.getPublicKey());
        byte[] msg = (id + "MAN" + "m").getBytes(StandardCharsets.UTF_8);
        byte[] combined = new byte[msg.length + DEVICE.getPublicKey().length];
        System.arraycopy(msg, 0, combined, 0, msg.length);
        System.arraycopy(DEVICE.getPublicKey(), 0, combined, msg.length, DEVICE.getPublicKey().length);
        lcm.provisionDevice(id, "MAN", "m", DEVICE.getPublicKey(), Crypto.sign(Crypto.hash(combined), MAN.getPrivateKey()), true);
        return lcm;
    }

    private static byte[] consent(String id, String owner, TestKeyPair signer) {
        return Crypto.sign(DeviceLifecycleManager.activationDigest(id, owner), signer.getPrivateKey());
    }

    @Test
    @DisplayName("activation requires: sender == owner, attested key, device consent signature")
    void activationAuthorisation() {
        String id = "d-1";
        DeviceLifecycleManager lcm = provisioned(id);
        // stranger claims the device for themselves without the device's consent
        assertThatThrownBy(() -> lcm.activateDevice(id, STRANGER.getAddress(), DEVICE.getPublicKey(), 1L,
                STRANGER.getAddress(), consent(id, STRANGER.getAddress(), STRANGER))).isInstanceOf(SecurityException.class);
        // sender names someone else as owner
        assertThatThrownBy(() -> lcm.activateDevice(id, OWNER.getAddress(), DEVICE.getPublicKey(), 1L,
                STRANGER.getAddress(), consent(id, OWNER.getAddress(), DEVICE))).isInstanceOf(SecurityException.class);
        // a different key than the one the manufacturer attested
        assertThatThrownBy(() -> lcm.activateDevice(id, OWNER.getAddress(), STRANGER.getPublicKey(), 1L,
                OWNER.getAddress(), consent(id, OWNER.getAddress(), STRANGER))).isInstanceOf(SecurityException.class);
        // missing / wrong-owner device signature
        assertThatThrownBy(() -> lcm.activateDevice(id, OWNER.getAddress(), DEVICE.getPublicKey(), 1L,
                OWNER.getAddress(), null)).isInstanceOf(SecurityException.class);
        assertThatThrownBy(() -> lcm.activateDevice(id, OWNER.getAddress(), DEVICE.getPublicKey(), 1L,
                OWNER.getAddress(), consent(id, STRANGER.getAddress(), DEVICE))).isInstanceOf(SecurityException.class);
        assertThat(lcm.getDeviceRecord(id).getStatus()).isEqualTo(DeviceLifecycleManager.DeviceStatus.PROVISIONING);

        // the legitimate activation
        lcm.activateDevice(id, OWNER.getAddress(), DEVICE.getPublicKey(), 1L, OWNER.getAddress(),
                consent(id, OWNER.getAddress(), DEVICE));
        assertThat(lcm.getDeviceRecord(id).getStatus()).isEqualTo(DeviceLifecycleManager.DeviceStatus.ACTIVE);
        assertThat(lcm.getDeviceRecord(id).getOwner()).isEqualTo(OWNER.getAddress());
    }

    @Test
    @DisplayName("every device field survives serialisation; clone has the same state root")
    void serialisationIsComplete() {
        AccountState s = new AccountState();
        DeviceLifecycleManager lcm = s.getLifecycleManager();
        lcm.registerManufacturer("MAN", MAN.getPublicKey());
        String id = "d-2";
        byte[] msg = (id + "MAN" + "m").getBytes(StandardCharsets.UTF_8);
        byte[] combined = new byte[msg.length + DEVICE.getPublicKey().length];
        System.arraycopy(msg, 0, combined, 0, msg.length);
        System.arraycopy(DEVICE.getPublicKey(), 0, combined, msg.length, DEVICE.getPublicKey().length);
        lcm.provisionDevice(id, "MAN", "m", DEVICE.getPublicKey(), Crypto.sign(Crypto.hash(combined), MAN.getPrivateKey()), true);
        lcm.activateDevice(id, OWNER.getAddress(), DEVICE.getPublicKey(), 1L, OWNER.getAddress(),
                consent(id, OWNER.getAddress(), DEVICE));
        lcm.updateFirmware(id, "2.0", Crypto.hash("fw".getBytes(StandardCharsets.UTF_8)), OWNER.getAddress());
        lcm.suspendDevice(id, OWNER.getAddress(), null);   // null metadata value must stay null

        AccountState clone = s.cloneState();
        DeviceLifecycleManager.DeviceRecord a = lcm.getDeviceRecord(id);
        DeviceLifecycleManager.DeviceRecord b = clone.getLifecycleManager().getDeviceRecord(id);
        assertThat(clone.calculateStateRoot()).isEqualTo(s.calculateStateRoot());
        assertThat(b.getPufNullifier()).isEqualTo(a.getPufNullifier()).isNotNull();
        assertThat(b.getAttestedPublicKey()).isEqualTo(a.getAttestedPublicKey());
        assertThat(b.getFirmwareHistory()).hasSize(1);
        assertThat(b.getFirmwareHistory().get(0).getHash()).isEqualTo(a.getFirmwareHistory().get(0).getHash());
        assertThat(b.getMetadata()).isEqualTo(a.getMetadata());
        assertThat(b.getLastActivityBlock()).isEqualTo(a.getLastActivityBlock());
    }
}
