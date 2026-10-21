package com.hybrid.blockchain.audit;

import com.hybrid.blockchain.AccountState;
import com.hybrid.blockchain.Block;
import com.hybrid.blockchain.Capability;
import com.hybrid.blockchain.HardwareManager;
import com.hybrid.blockchain.Interpreter;
import com.hybrid.blockchain.OpCode;
import com.hybrid.blockchain.TestKeyPair;
import com.hybrid.blockchain.Transaction;
import com.hybrid.blockchain.lifecycle.DeviceLifecycleManager;
import com.hybrid.blockchain.lifecycle.DeviceReading;
import com.hybrid.blockchain.lifecycle.SensorReadings;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** [HC-058] Contracts read sensors from chain state, so every node computes the same result. */
class DeterministicSensorTest {

    private static final String DEVICE = "thermo-58";

    private static long readViaContract(AccountState state, long sensorId) throws Exception {
        ByteBuffer code = ByteBuffer.allocate(20);
        code.put(OpCode.PUSH.getByte()).putLong(sensorId);
        code.put(OpCode.PUSH.getByte()).putLong(1L);      // SYSCALL 1 = READ_SENSOR
        code.put(OpCode.SYSCALL.getByte());
        state.ensure("c");
        state.getAccountCapabilities("c").add(new Capability(Capability.Type.READ_SENSOR, sensorId));
        Interpreter vm = new Interpreter(code.array(), 10_000,
                new Interpreter.BlockchainContext(1L, 1, "caller", "c", 0, state, new HardwareManager(), "h"));
        vm.execute();
        return vm.getStack().pop();
    }

    @Test
    @DisplayName("a contract reads the value in state, and a defined sentinel when there is none")
    void contractReadsChainState() throws Exception {
        long sid = SensorReadings.sensorId(DEVICE);
        AccountState nodeA = new AccountState(), nodeB = new AccountState();
        assertThat(readViaContract(nodeA, sid)).isEqualTo(SensorReadings.NO_READING);
        nodeA.recordReading(sid, 21_500);
        nodeB.recordReading(sid, 21_500);
        assertThat(readViaContract(nodeA, sid)).isEqualTo(21_500).isEqualTo(readViaContract(nodeB, sid));
    }

    @Test
    @DisplayName("parsing is decimal and exact: 21.5 and {\"value\":21.5} -> 21500; text is ignored")
    void parsing() {
        assertThat(SensorReadings.parseMilli("21.5".getBytes(StandardCharsets.UTF_8))).isEqualTo(21_500L);
        assertThat(SensorReadings.parseMilli("{\"value\":-0.1235}".getBytes(StandardCharsets.UTF_8))).isEqualTo(-124L);
        assertThat(SensorReadings.parseMilli("temp:23.5".getBytes(StandardCharsets.UTF_8))).isNull();
        assertThat(SensorReadings.parseMilli("1e30".getBytes(StandardCharsets.UTF_8))).isNull();
    }

    @Test
    @DisplayName("end to end: a device-signed reading is recorded in state; an owner-typed number is not")
    void onlyDeviceAuthenticatedReadingsAreRecorded() throws Exception {
        try (LedgerPoCFixture f = new LedgerPoCFixture()) {
            TestKeyPair device = new TestKeyPair(5801), owner = new TestKeyPair(5802), gateway = new TestKeyPair(5803);
            f.fund(gateway.getAddress());
            f.fund(owner.getAddress());
            AccountState st = f.chain.getState();
            String did = st.getSSIManager().registerDID(DEVICE, device.getPublicKey(), owner.getAddress());
            DeviceLifecycleManager lcm = st.getLifecycleManager();
            lcm.registerDevice(DEVICE, "acme", "t1");
            lcm.getDeviceRecord(DEVICE).setDid(did);
            lcm.getDeviceRecord(DEVICE).setOwner(owner.getAddress());
            long sid = SensorReadings.sensorId(DEVICE);
            long fee = Math.max(10, f.chain.getCurrentBaseFee());
            long ts = System.currentTimeMillis();

            byte[] signed = DeviceReading.sign(DEVICE, "21.5".getBytes(StandardCharsets.UTF_8), ts, device.getPrivateKey()).toJson();
            Transaction relay = new Transaction.Builder().type(Transaction.Type.TELEMETRY).from(gateway.getAddress())
                    .to(DEVICE).fee(fee).nonce(1).data(signed).sign(gateway.getPrivateKey(), gateway.getPublicKey());
            Block b = LedgerPoCFixture.build(f.chain, List.of(relay), 0, ts);
            f.commitAndApply(b);
            assertThat(f.chain.getState().getLatestReading(sid)).isEqualTo(21_500);

            // the owner may submit telemetry, but a number the owner typed is not device-signed
            Transaction ownerTyped = new Transaction.Builder().type(Transaction.Type.TELEMETRY).from(owner.getAddress())
                    .to(DEVICE).fee(fee).nonce(1).data("99".getBytes(StandardCharsets.UTF_8))
                    .sign(owner.getPrivateKey(), owner.getPublicKey());
            f.chain.validateTransaction(ownerTyped);
            f.commitAndApply(LedgerPoCFixture.build(f.chain, List.of(ownerTyped), 0, ts + 1));
            assertThat(f.chain.getStorage().loadReceipt(ownerTyped.getTxId()).getStatus())
                    .as("the owner's telemetry itself was applied").isEqualTo(com.hybrid.blockchain.TransactionReceipt.STATUS_SUCCESS);
            assertThat(f.chain.getState().getLatestReading(sid)).as("unchanged by an owner-typed value").isEqualTo(21_500);
        }
    }
}
