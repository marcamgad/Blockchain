package com.hybrid.blockchain.audit;

import com.hybrid.blockchain.AccountState;
import com.hybrid.blockchain.Block;
import com.hybrid.blockchain.TestKeyPair;
import com.hybrid.blockchain.Transaction;
import com.hybrid.blockchain.lifecycle.DeviceLifecycleManager;
import com.hybrid.blockchain.lifecycle.DeviceReading;
import com.hybrid.blockchain.lifecycle.SensorReadings;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * [HC-097] Found by the independent audit (docs/audit/independent-2026-10-24, C-3). A device-signed
 * reading is accepted within ±10 minutes of block time and carries no sequence number, and the
 * contract-visible sensor value was overwritten unconditionally. Anyone could relay an older signed
 * reading (they are public in transaction data) and roll the value contracts see back to it.
 */
class ReadingReplayTest {

    private static final String DEVICE = "thermo-97";

    @Test
    @DisplayName("a replayed or older device-signed reading does not replace a newer one")
    void replayCannotRollBack() throws Exception {
        try (LedgerPoCFixture f = new LedgerPoCFixture()) {
            TestKeyPair device = new TestKeyPair(9701), owner = new TestKeyPair(9702), relay = new TestKeyPair(9703);
            f.fund(relay.getAddress());
            AccountState st = f.chain.getState();
            String did = st.getSSIManager().registerDID(DEVICE, device.getPublicKey(), owner.getAddress());
            DeviceLifecycleManager lcm = st.getLifecycleManager();
            lcm.registerDevice(DEVICE, "acme", "t1");
            lcm.getDeviceRecord(DEVICE).setDid(did);
            lcm.getDeviceRecord(DEVICE).setOwner(owner.getAddress());
            long sid = SensorReadings.sensorId(DEVICE);
            long fee = Math.max(10, f.chain.getCurrentBaseFee());
            long t = System.currentTimeMillis();

            byte[] r1 = DeviceReading.sign(DEVICE, "30".getBytes(StandardCharsets.UTF_8), t, device.getPrivateKey()).toJson();
            byte[] r2 = DeviceReading.sign(DEVICE, "80".getBytes(StandardCharsets.UTF_8), t + 1_000, device.getPrivateKey()).toJson();
            byte[] r0 = DeviceReading.sign(DEVICE, "10".getBytes(StandardCharsets.UTF_8), t - 500, device.getPrivateKey()).toJson();

            long nonce = 1, blockTime = t + 1;
            for (byte[] reading : List.of(r1, r2)) {
                Transaction tx = new Transaction.Builder().type(Transaction.Type.TELEMETRY).from(relay.getAddress())
                        .to(DEVICE).fee(fee).nonce(nonce++).data(reading).sign(relay.getPrivateKey(), relay.getPublicKey());
                f.commitAndApply(LedgerPoCFixture.build(f.chain, List.of(tx), 0, blockTime += 1_000));
            }
            assertThat(f.chain.getState().getLatestReading(sid)).isEqualTo(80_000);

            for (byte[] stale : List.of(r1, r0)) {   // replay of r1, and an older reading
                Transaction tx = new Transaction.Builder().type(Transaction.Type.TELEMETRY).from(relay.getAddress())
                        .to(DEVICE).fee(fee).nonce(nonce++).data(stale).sign(relay.getPrivateKey(), relay.getPublicKey());
                Block b = LedgerPoCFixture.build(f.chain, List.of(tx), 0, blockTime += 1_000);
                f.commitAndApply(b);
                assertThat(f.chain.getState().getLatestReading(sid)).as("value after a stale reading").isEqualTo(80_000);
            }
            // a restart (state round trip) keeps the order information
            assertThat(f.chain.getState().cloneState().calculateStateRoot()).isEqualTo(f.chain.getState().calculateStateRoot());
        }
    }
}
