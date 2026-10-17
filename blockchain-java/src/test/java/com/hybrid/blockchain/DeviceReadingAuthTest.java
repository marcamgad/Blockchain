package com.hybrid.blockchain;

import com.hybrid.blockchain.lifecycle.DeviceLifecycleManager;
import com.hybrid.blockchain.lifecycle.DeviceReading;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** [HC-013] End-to-end device authentication for gateway-relayed telemetry. */
class DeviceReadingAuthTest {

    private static final String DEVICE = "thermo-42";

    /** Registers an ACTIVE device whose DID carries {@code device}'s public key. */
    private static void register(Blockchain chain, TestKeyPair device, String owner) {
        AccountState st = chain.getState();
        String did = st.getSSIManager().registerDID(DEVICE, device.getPublicKey(), owner);
        DeviceLifecycleManager lcm = st.getLifecycleManager();
        lcm.registerDevice(DEVICE, "acme", "t1");
        lcm.getDeviceRecord(DEVICE).setDid(did);
        lcm.getDeviceRecord(DEVICE).setOwner(owner);
    }

    private static Transaction relay(TestKeyPair gateway, byte[] data, long nonce) {
        return new Transaction.Builder().type(Transaction.Type.TELEMETRY).from(gateway.getAddress())
                .to(DEVICE).fee(10).nonce(nonce).data(data).sign(gateway.getPrivateKey(), gateway.getPublicKey());
    }

    @Test
    @DisplayName("device-signed reading relayed by an unrelated gateway is accepted")
    void deviceSignedReadingAccepted() throws Exception {
        try (TestBlockchain tb = new TestBlockchain()) {
            TestKeyPair device = new TestKeyPair(4201), gateway = new TestKeyPair(4202);
            register(tb.getBlockchain(), device, new TestKeyPair(4203).getAddress());
            tb.getBlockchain().getState().credit(gateway.getAddress(), 1000);
            DeviceReading r = DeviceReading.sign(DEVICE, "21.5".getBytes(StandardCharsets.UTF_8),
                    System.currentTimeMillis(), device.getPrivateKey());
            tb.getBlockchain().validateTransaction(relay(gateway, r.toJson(), 1));
        }
    }

    @Test
    @DisplayName("forged, re-targeted, stale or tampered readings are rejected")
    void badReadingsRejected() throws Exception {
        try (TestBlockchain tb = new TestBlockchain()) {
            Blockchain chain = tb.getBlockchain();
            TestKeyPair device = new TestKeyPair(4201), gateway = new TestKeyPair(4202), mallory = new TestKeyPair(4204);
            register(chain, device, new TestKeyPair(4203).getAddress());
            chain.getState().credit(gateway.getAddress(), 1000);
            long now = System.currentTimeMillis();
            byte[] payload = "21.5".getBytes(StandardCharsets.UTF_8);

            // unsigned raw reading from a gateway
            assertThatThrownBy(() -> chain.validateTransaction(relay(gateway, payload, 1)))
                    .hasMessageContaining("not authorized");
            // signed by someone else's key
            byte[] forged = DeviceReading.sign(DEVICE, payload, now, mallory.getPrivateKey()).toJson();
            assertThatThrownBy(() -> chain.validateTransaction(relay(gateway, forged, 1)))
                    .hasMessageContaining("not authorized");
            // valid signature for ANOTHER device id, relayed to this device
            byte[] other = DeviceReading.sign("other-device", payload, now, device.getPrivateKey()).toJson();
            assertThatThrownBy(() -> chain.validateTransaction(relay(gateway, other, 1)))
                    .hasMessageContaining("not authorized");
            // replay of an old reading (outside the window)
            byte[] stale = DeviceReading.sign(DEVICE, payload, now - DeviceReading.MAX_SKEW_MS - 60_000,
                    device.getPrivateKey()).toJson();
            assertThatThrownBy(() -> chain.validateTransaction(relay(gateway, stale, 1)))
                    .hasMessageContaining("not authorized");
            // payload changed after signing
            DeviceReading good = DeviceReading.sign(DEVICE, payload, now, device.getPrivateKey());
            byte[] tampered = new DeviceReading(DEVICE, HexUtils.encode("99.9".getBytes(StandardCharsets.UTF_8)),
                    good.timestamp, good.signatureHex).toJson();
            assertThatThrownBy(() -> chain.validateTransaction(relay(gateway, tampered, 1)))
                    .hasMessageContaining("not authorized");
        }
    }

    @Test
    @DisplayName("the MQTT gateway refuses telemetry that is not device-signed")
    void gatewayRejectsUnsignedTelemetry() throws Exception {
        try (TestBlockchain tb = new TestBlockchain()) {
            register(tb.getBlockchain(), new TestKeyPair(4201), "owner");
            com.hybrid.blockchain.api.MQTTAdapter adapter = new com.hybrid.blockchain.api.MQTTAdapter(tb.getBlockchain());
            java.lang.reflect.Method submit = com.hybrid.blockchain.api.AbstractIoTMessageAdapter.class
                    .getDeclaredMethod("submitDeviceTx", Transaction.Type.class, String.class, byte[].class);
            submit.setAccessible(true);
            Throwable t = null;
            try {
                submit.invoke(adapter, Transaction.Type.TELEMETRY, DEVICE,
                        "{\"deviceId\":\"thermo-42\",\"value\":21.5}".getBytes(StandardCharsets.UTF_8));
            } catch (java.lang.reflect.InvocationTargetException e) { t = e.getCause(); }
            assertThat(t).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("device-signed");
            assertThat(tb.getBlockchain().getMempool().size()).isZero();
        }
    }
}
