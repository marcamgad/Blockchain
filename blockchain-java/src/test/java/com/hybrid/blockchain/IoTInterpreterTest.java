package com.hybrid.blockchain;

import org.junit.jupiter.api.Test;
import java.nio.ByteBuffer;
import static org.junit.jupiter.api.Assertions.*;

public class IoTInterpreterTest {

    @Test
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

        assertEquals(30L, vm.getStack().pop());
    }

    @Test
    public void testStoragePersistence() throws Exception {
        ByteBuffer code = ByteBuffer.allocate(31);
        code.put(OpCode.PUSH.getByte()).putLong(1L);
        code.put(OpCode.PUSH.getByte()).putLong(100L);
        code.put(OpCode.SSTORE.getByte());
        code.put(OpCode.PUSH.getByte()).putLong(1L);
        code.put(OpCode.SLOAD.getByte());
        code.put(OpCode.STOP.getByte());

        AccountState state = new AccountState();
        HardwareManager hw = new HardwareManager();
        Interpreter.BlockchainContext ctx = new Interpreter.BlockchainContext(
                System.currentTimeMillis(), 1, "alice", "contract1", 0, state, hw, "hash1");

        Interpreter vm = new Interpreter(code.array(), 10000, ctx);
        vm.execute();

        assertEquals(100L, state.getAccountStorage("contract1").get(1L));
    }

    @Test
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
        Exception ex = assertThrows(Exception.class, vm::execute);
        assertTrue(ex.getMessage().contains("Unauthorized"));
    }

    @Test
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

        assertEquals(42L, vm.getStack().pop());
    }

    @Test
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
        assertEquals(0L, hw.getActuatorState(100L));

        // Commit and verify
        hw.commitDeferredActions("block_abc");
        assertEquals(1L, hw.getActuatorState(100L));
    }

    @Test
    public void testOutOfGas() {
        ByteBuffer code = ByteBuffer.allocate(200);
        for (int i = 0; i < 15; i++)
            code.put(OpCode.PUSH.getByte()).putLong(i);

        AccountState state = new AccountState();
        HardwareManager hw = new HardwareManager();
        Interpreter.BlockchainContext ctx = new Interpreter.BlockchainContext(
                System.currentTimeMillis(), 1, "alice", "contract1", 0, state, hw, "hash1");

        Interpreter vm = new Interpreter(code.array(), 10, ctx);
        assertThrows(Exception.class, vm::execute);
    }
}
