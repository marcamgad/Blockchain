package com.hybrid.blockchain;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Map;
import java.util.Stack;

/**
 * A deterministic, sandboxed, and metered interpreter for IoT smart contracts.
 * Zero access to host OS, reflection, or non-deterministic APIs.
 */
public class Interpreter {
    private final byte[] bytecode;
    private final Stack<Long> stack;
    private int pc = 0; // Program Counter
    private long gasRemaining;
    private final BlockchainContext context;

    public Interpreter(byte[] bytecode, long gasLimit, BlockchainContext context) {
        this.bytecode = bytecode;
        this.stack = new Stack<>();
        this.gasRemaining = gasLimit;
        this.context = context;
    }

    public void execute() throws Exception {
        while (pc < bytecode.length) {
            OpCode op = OpCode.fromByte(bytecode[pc++]);
            if (op == null)
                throw new Exception("Unknown OpCode at " + (pc - 1));

            deductGas(op.getGasCost());

            switch (op) {
                case STOP:
                    return;

                case PUSH:
                    stack.push(readLong());
                    break;

                case POP:
                    stack.pop();
                    break;

                case ADD:
                    stack.push(stack.pop() + stack.pop());
                    break;

                case SUB:
                    long b = stack.pop();
                    long a = stack.pop();
                    stack.push(a - b);
                    break;

                case MUL:
                    stack.push(stack.pop() * stack.pop());
                    break;

                case DIV:
                    long divisor = stack.pop();
                    if (divisor == 0)
                        throw new Exception("Division by zero");
                    stack.push(stack.pop() / divisor);
                    break;

                case SLOAD:
                    long sKey = stack.pop();
                    stack.push(context.state.getAccountStorage(context.contractAddress).get(sKey));
                    break;

                case SSTORE:
                    long val = stack.pop();
                    long k = stack.pop();
                    context.state.getAccountStorage(context.contractAddress).put(k, val);
                    break;

                case SYSCALL:
                    handleSyscall();
                    break;

                case TIMESTAMP:
                    stack.push(context.timestamp);
                    break;

                case NUMBER:
                    stack.push((long) context.blockHeight);
                    break;

                case CALLER:
                    stack.push(0L); // Placeholder
                    break;

                default:
                    throw new Exception("OpCode " + op + " not yet implemented");
            }

            if (stack.size() > 1024)
                throw new Exception("Stack overflow");
        }
    }

    private final Map<String, Long> lastSyscallTime = new java.util.HashMap<>();

    private void handleSyscall() throws Exception {
        long callId = stack.pop();
        deductGas(100);

        // Simple Rate limiting per contract/device
        String rateKey = context.contractAddress + ":" + callId;
        long now = context.timestamp;
        if (lastSyscallTime.containsKey(rateKey) && (now - lastSyscallTime.get(rateKey)) < 1000) {
            throw new Exception("Rate limit exceeded for Syscall " + callId);
        }
        lastSyscallTime.put(rateKey, now);

        java.util.Set<Capability> caps = context.state.getAccountCapabilities(context.contractAddress);

        if (callId == 1) { // READ_SENSOR
            long sensorId = stack.pop();
            if (!hasCapability(caps, Capability.Type.READ_SENSOR, sensorId)) {
                throw new Exception("Unauthorized: Contract lacks READ_SENSOR capability for ID " + sensorId);
            }
            stack.push(context.hardware.readSensor(sensorId));
        } else if (callId == 2) { // WRITE_ACTUATOR
            long actionId = stack.pop();
            long value = stack.pop();
            if (!hasCapability(caps, Capability.Type.WRITE_ACTUATOR, actionId)) {
                throw new Exception("Unauthorized: Contract lacks WRITE_ACTUATOR capability for ID " + actionId);
            }
            context.hardware.queueActuator(context.currentBlockHash, actionId, value);
        } else {

            throw new Exception("Invalid Syscall ID: " + callId);
        }
    }

    private boolean hasCapability(java.util.Set<Capability> caps, Capability.Type type, long deviceId) {
        return caps.contains(new Capability(type, deviceId));
    }

    private void deductGas(int amount) throws Exception {
        gasRemaining -= amount;
        if (gasRemaining < 0)
            throw new Exception("Out of Gas");
    }

    private long readLong() throws Exception {
        if (pc + 8 > bytecode.length)
            throw new Exception("Malformed bytecode: expected 8 bytes for PUSH");
        ByteBuffer buffer = ByteBuffer.wrap(bytecode, pc, 8).order(ByteOrder.BIG_ENDIAN);
        pc += 8;
        return buffer.getLong();
    }

    public static class BlockchainContext {
        public long timestamp;
        public int blockHeight;
        public String caller;
        public String contractAddress;
        public long value;
        public AccountState state;
        public HardwareManager hardware;
        public String currentBlockHash;

        public BlockchainContext(long timestamp, int blockHeight, String caller, String contractAddress, long value,
                AccountState state, HardwareManager hardware, String currentBlockHash) {
            this.timestamp = timestamp;
            this.blockHeight = blockHeight;
            this.caller = caller;
            this.contractAddress = contractAddress;
            this.value = value;
            this.state = state;
            this.hardware = hardware;
            this.currentBlockHash = currentBlockHash;
        }

    }

    public long getGasRemaining() {
        return gasRemaining;
    }

    public Stack<Long> getStack() {
        return stack;
    }
}
