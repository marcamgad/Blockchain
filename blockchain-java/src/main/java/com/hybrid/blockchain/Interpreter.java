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
    private byte[] memory = new byte[16384]; // 16KB sandbox memory
    private int pc = 0; // Program Counter
    private long gasRemaining;
    private final BlockchainContext context;
    private byte[] returnData = null;

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

                case RETURN:
                    long retLen = stack.pop();
                    long retOffset = stack.pop();
                    if (retOffset < 0 || retOffset + retLen > memory.length) throw new Exception("Memory access out of bounds");
                    this.returnData = new byte[(int)retLen];
                    System.arraycopy(memory, (int)retOffset, this.returnData, 0, (int)retLen);
                    return;

                case REVERT:
                    long rLen = stack.pop();
                    long rOff = stack.pop();
                    if (rOff < 0 || rOff + rLen > memory.length) throw new Exception("Memory access out of bounds");
                    byte[] rData = new byte[(int) rLen];
                    System.arraycopy(memory, (int) rOff, rData, 0, (int) rLen);
                    throw new RevertException(rData);

                case JUMP:
                    long dest = stack.pop();
                    if (dest < 0 || dest >= bytecode.length) throw new Exception("Invalid JUMP destination");
                    pc = (int) dest;
                    break;

                case JUMPI:
                    long cond = stack.pop();
                    long target = stack.pop();
                    if (cond != 0) {
                        if (target < 0 || target >= bytecode.length) throw new Exception("Invalid JUMPI destination");
                        pc = (int) target;
                    }
                    break;

                case CALL:
                    @SuppressWarnings("unused") long callGas = stack.pop();
                    @SuppressWarnings("unused") long toAddr = stack.pop(); 
                    @SuppressWarnings("unused") long callValue = stack.pop();
                    @SuppressWarnings("unused") long inOffset = stack.pop();
                    @SuppressWarnings("unused") long inLen = stack.pop();
                    @SuppressWarnings("unused") long outOffset = stack.pop();
                    @SuppressWarnings("unused") long outLen = stack.pop();
                    
                    // Simple stub for intra-contract calls. 
                    // In full implementation, this instantiates a new Interpreter.
                    stack.push(1L); // 1 = success
                    break;

                case LOG:
                    long topic = stack.pop();
                    long dataLen = stack.pop();
                    long dataOffset = stack.pop();
                    if (dataOffset < 0 || dataOffset + dataLen > memory.length) throw new Exception("Memory access out of bounds");
                    byte[] eventData = new byte[(int)dataLen];
                    System.arraycopy(memory, (int)dataOffset, eventData, 0, (int)dataLen);
                    context.events.add(new ContractEvent(context.contractAddress, topic, eventData, context.timestamp));
                    break;

                case PUSH:
                    stack.push(readLong());
                    break;

                case POP:
                    stack.pop();
                    break;

                case DUP:
                    stack.push(stack.peek());
                    break;

                case SWAP:
                    long s1 = stack.pop();
                    long s2 = stack.pop();
                    stack.push(s1);
                    stack.push(s2);
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

                case MOD:
                    long mDivisor = stack.pop();
                    if (mDivisor == 0) throw new Exception("Division by zero");
                    stack.push(stack.pop() % mDivisor);
                    break;

                case MLOAD:
                    long mOffset = stack.pop();
                    if (mOffset < 0 || mOffset + 8 > memory.length) throw new Exception("Memory access out of bounds");
                    stack.push(ByteBuffer.wrap(memory, (int)mOffset, 8).order(ByteOrder.BIG_ENDIAN).getLong());
                    break;

                case MSTORE:
                    long mOff = stack.pop();
                    long mVal = stack.pop();
                    if (mOff < 0 || mOff + 8 > memory.length) throw new Exception("Memory access out of bounds");
                    ByteBuffer.wrap(memory, (int)mOff, 8).order(ByteOrder.BIG_ENDIAN).putLong(mVal);
                    break;

                case DIV:
                    long divisor = stack.pop();
                    if (divisor == 0)
                        throw new Exception("Division by zero");
                    stack.push(stack.pop() / divisor);
                    break;

                case EQ:
                    stack.push(stack.pop().equals(stack.pop()) ? 1L : 0L);
                    break;

                case LT:
                    long ltB = stack.pop();
                    long ltA = stack.pop();
                    stack.push(ltA < ltB ? 1L : 0L);
                    break;

                case GT:
                    long gtB = stack.pop();
                    long gtA = stack.pop();
                    stack.push(gtA > gtB ? 1L : 0L);
                    break;

                case SLOAD:
                    long sKey = stack.pop();
                    stack.push(context.state.getAccountStorage(context.contractAddress).getOrDefault(sKey, 0L));
                    break;

                case SSTORE:
                    long k = stack.pop();
                    long val = stack.pop();
                    context.state.putStorage(context.contractAddress, k, val);
                    break;

                case BALANCE:
                    long addrSeed = stack.pop();
                    // In our simplified VM, we derive address from the long seed if it's not a real address string
                    // For now, look up by the address seed if it's already a known address or use a mapping.
                    // This is a placeholder for real address conversion.
                    stack.push(context.state.getBalance(context.state.getAccountAddresses().stream().skip(addrSeed % 10).findFirst().orElse(context.contractAddress))); 
                    break;

                case SELFBALANCE:
                    stack.push(context.state.getBalance(context.contractAddress));
                    break;

                case VALUE:
                    stack.push(context.value);
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
                    stack.push(callerToLong(context.caller));
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

    private long callerToLong(String callerAddress) {
        if (callerAddress == null || callerAddress.isEmpty()) {
            return 0L;
        }
        byte[] callerHash = Crypto.hash(callerAddress.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        return ByteBuffer.wrap(callerHash, 0, Long.BYTES).order(ByteOrder.BIG_ENDIAN).getLong();
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
        public java.util.List<ContractEvent> events = new java.util.ArrayList<>();

        public BlockchainContext() {}

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

    public byte[] getReturnData() {
        return returnData != null ? returnData : new byte[0];
    }
}
