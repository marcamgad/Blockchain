package com.hybrid.blockchain;

import java.nio.ByteBuffer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Tag("unit")
public class IoTInterpreterTest {

    @Test
    @DisplayName("Invariant: Basic arithmetic ops evaluate correctly")
    public void testBasicArithmetic() throws Exception {
        // PUSH 10, PUSH 20, ADD, STOP
        ByteBuffer code = ByteBuffer.allocate(20);
        code.put(OpCode.PUSH.getByte());
        code.putLong(10);
        code.put(OpCode.PUSH.getByte());
        code.putLong(20);
        code.put(OpCode.ADD.getByte());
        code.put(OpCode.STOP.getByte());

        AccountState state = new AccountState();
        HardwareManager hw = new HardwareManager();
        Interpreter.BlockchainContext ctx = new Interpreter.BlockchainContext(
                System.currentTimeMillis(), 1, "alice", "contract1", 0, state, hw, "hash1");

        Interpreter vm = new Interpreter(code.array(), 1000, ctx);
        vm.execute();

        assertThat(vm.getStack().pop()).isEqualTo(30L);
    }

    @Test
    @DisplayName("Invariant: Storage persistence writes and reads correctly")
    public void testStoragePersistence() throws Exception {
        ByteBuffer code = ByteBuffer.allocate(31);
        code.put(OpCode.PUSH.getByte()).putLong(100L); // value
        code.put(OpCode.PUSH.getByte()).putLong(1L);   // key
        code.put(OpCode.SSTORE.getByte());
        code.put(OpCode.PUSH.getByte()).putLong(1L);   // key
        code.put(OpCode.SLOAD.getByte());
        code.put(OpCode.STOP.getByte());

        AccountState state = new AccountState();
        HardwareManager hw = new HardwareManager();
        Interpreter.BlockchainContext ctx = new Interpreter.BlockchainContext(
                System.currentTimeMillis(), 1, "alice", "contract1", 0, state, hw, "hash1");

        Interpreter vm = new Interpreter(code.array(), 10000, ctx);
        vm.execute();

        assertThat(state.getAccountStorage("contract1").get(1L)).isEqualTo(100L);
    }

    @Test
    @DisplayName("Security: Unauthorized system calls must be rejected")
    public void testSyscallUnauthorized() {
        ByteBuffer code = ByteBuffer.allocate(20);
        code.put(OpCode.PUSH.getByte()).putLong(1L);
        code.put(OpCode.PUSH.getByte()).putLong(1L); // READ_SENSOR
        code.put(OpCode.SYSCALL.getByte());

        AccountState state = new AccountState();
        HardwareManager hw = new HardwareManager();
        Interpreter.BlockchainContext ctx = new Interpreter.BlockchainContext(
                System.currentTimeMillis(), 1, "alice", "contract1", 0, state, hw, "hash1");

        Interpreter vm = new Interpreter(code.array(), 1000, ctx);
        assertThatThrownBy(vm::execute)
            .isInstanceOf(Exception.class)
            .hasMessageContaining("Unauthorized");
    }

    @Test
    @DisplayName("Security: Authorized system calls must succeed")
    public void testSyscallAuthorized() throws Exception {
        ByteBuffer code = ByteBuffer.allocate(20);
        code.put(OpCode.PUSH.getByte()).putLong(1L);
        code.put(OpCode.PUSH.getByte()).putLong(1L);
        code.put(OpCode.SYSCALL.getByte());

        AccountState state = new AccountState();
        state.ensure("contract1");
        state.getAccountCapabilities("contract1").add(new Capability(Capability.Type.READ_SENSOR, 1L));

        HardwareManager hw = new HardwareManager();
        hw.setMockSensorValue(1L, 42L);

        Interpreter.BlockchainContext ctx = new Interpreter.BlockchainContext(
                System.currentTimeMillis(), 1, "alice", "contract1", 0, state, hw, "hash1");

        Interpreter vm = new Interpreter(code.array(), 1000, ctx);
        vm.execute();

        assertThat(vm.getStack().pop()).isEqualTo(42L);
    }

    @Test
    @DisplayName("Feature: Deferred actuator actions only apply upon commit")
    public void testDeferredActuator() throws Exception {
        // PUSH 100 (ActuatorID), PUSH 1 (Value), PUSH 2 (WRITE_ACTUATOR), SYSCALL, STOP
        ByteBuffer code = ByteBuffer.allocate(31);
        code.put(OpCode.PUSH.getByte()).putLong(1L); // Value
        code.put(OpCode.PUSH.getByte()).putLong(100L); // Device
        code.put(OpCode.PUSH.getByte()).putLong(2L); // SYSCALL ID (WRITE_ACTUATOR)
        code.put(OpCode.SYSCALL.getByte());

        AccountState state = new AccountState();
        state.ensure("contract1");
        state.getAccountCapabilities("contract1").add(new Capability(Capability.Type.WRITE_ACTUATOR, 100L));

        HardwareManager hw = new HardwareManager();
        Interpreter.BlockchainContext ctx = new Interpreter.BlockchainContext(
                System.currentTimeMillis(), 1, "alice", "contract1", 0, state, hw, "block_abc");

        Interpreter vm = new Interpreter(code.array(), 1000, ctx);
        vm.execute();

        // Verify it's NOT yet executed
        assertThat(hw.getActuatorState(100L)).isEqualTo(0L);

        // Commit and verify
        hw.commitDeferredActions("block_abc");
        assertThat(hw.getActuatorState(100L)).isEqualTo(1L);
    }

    @Test
    @DisplayName("Security: Out of gas execution must throw exception")
    public void testOutOfGas() {
        ByteBuffer code = ByteBuffer.allocate(200);
        for (int i = 0; i < 15; i++)
            code.put(OpCode.PUSH.getByte()).putLong(i);

        AccountState state = new AccountState();
        HardwareManager hw = new HardwareManager();
        Interpreter.BlockchainContext ctx = new Interpreter.BlockchainContext(
                System.currentTimeMillis(), 1, "alice", "contract1", 0, state, hw, "hash1");

        Interpreter vm = new Interpreter(code.array(), 10, ctx);
        assertThatThrownBy(vm::execute)
            .isInstanceOf(Exception.class);
    }
}
