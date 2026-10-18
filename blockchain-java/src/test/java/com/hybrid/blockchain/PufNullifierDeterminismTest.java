package com.hybrid.blockchain;

import com.hybrid.blockchain.lifecycle.DeviceLifecycleManager;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * [HC-049] Provisioning the same device with the same transaction must give the same state on
 * every node (and in the block producer's simulation).
 */
class PufNullifierDeterminismTest {

    private static String provisionAndGetNullifier() throws Exception {
        TestKeyPair manufacturer = new TestKeyPair(8201), device = new TestKeyPair(8202);
        DeviceLifecycleManager lcm = new DeviceLifecycleManager(new com.hybrid.blockchain.identity.SSIManager());
        lcm.registerManufacturer("MAN1", manufacturer.getPublicKey());
        String id = "dev-1", model = "m1";
        byte[] msg = (id + "MAN1" + model).getBytes(java.nio.charset.StandardCharsets.UTF_8);
        byte[] combined = new byte[msg.length + device.getPublicKey().length];
        System.arraycopy(msg, 0, combined, 0, msg.length);
        System.arraycopy(device.getPublicKey(), 0, combined, msg.length, device.getPublicKey().length);
        byte[] sig = Crypto.sign(Crypto.hash(combined), manufacturer.getPrivateKey());
        lcm.provisionDevice(id, "MAN1", model, device.getPublicKey(), sig, true);
        return lcm.getDeviceRecord(id).getPufNullifier();
    }

    @Test
    @DisplayName("PUF nullifier is a deterministic function of the provisioning transaction")
    void nullifierDeterministic() throws Exception {
        String a = provisionAndGetNullifier(), b = provisionAndGetNullifier();
        System.out.println("[HC-049] nullifier run1=" + a + " run2=" + b);
        assertThat(a).isNotNull();
        assertThat(b).isEqualTo(a);
    }
}
