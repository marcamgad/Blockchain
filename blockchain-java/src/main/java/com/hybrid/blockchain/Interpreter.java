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
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(Interpreter.class);
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

    /**
     * Executes the bytecode instruction by instruction.
     * 
     * Stack Convention (EVM standard): For multi-argument opcodes, arguments are pushed left-to-right.
     * The rightmost argument becomes the stack top and is popped first.
     * Example for JUMPI(address, condition): push(address) then push(condition), 
     * JUMPI pops condition first (top), then address (below), and jumps if condition != 0.
     * 
     * Self-test verification: 
     *   Code: PUSH 100, PUSH 1, JUMPI
     *   Expected: Jump to PC=100 because condition (1) != 0
     */
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
                    long retOffset = stack.pop();
                    long retLen = stack.pop();
                    if (retOffset < 0 || retOffset + retLen > memory.length) throw new Exception("Memory access out of bounds");
                    this.returnData = new byte[(int)retLen];
                    System.arraycopy(memory, (int)retOffset, this.returnData, 0, (int)retLen);
                    return;

                case REVERT:
                    long rOff = stack.pop();
                    long rLen = stack.pop();
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
                    // JUMPI(address, condition): Pop condition (on top), then address.
                    // Jump to address if condition != 0.
                    long jiCond   = stack.pop(); // condition is on top (pushed second/rightmost)
                    long jiTarget = stack.pop(); // target is below (pushed first/leftmost)
                    if (jiCond != 0) {
                        if (jiTarget < 0 || jiTarget >= bytecode.length) throw new Exception("Invalid JUMPI destination: " + jiTarget);
                        pc = (int) jiTarget;
                    }
                    break;

                case CALL:
                    long callGas = stack.pop();
                    long toAddr = stack.pop();
                    long callValue = stack.pop();
                    long inOffset = stack.pop();
                    long inLen = stack.pop();
                    long outOffset = stack.pop();
                    long outLen = stack.pop();
                    
                    // Resolve target address from registry
                    String targetAddr = context.addressRegistry.getOrDefault(toAddr, null);
                    if (targetAddr == null) {
                        // Try hash-based lookup
                        targetAddr = context.state.getAllAddresses().stream()
                            .filter(addr -> {
                                byte[] h = Crypto.hash(addr.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                                return java.nio.ByteBuffer.wrap(h, 0, 8)
                                    .order(java.nio.ByteOrder.BIG_ENDIAN).getLong() == toAddr;
                            })
                            .findFirst()
                            .orElse(null);
                    }
                    
                    if (targetAddr == null) {
                        // Target address not found; execution fails
                        stack.push(0L); // 0 = failure
                        break;
                    }
                    
                    // Check target has code
                    AccountState.Account targetAccount = context.state.getAccount(targetAddr);
                    if (targetAccount == null || targetAccount.getCode() == null) {
                        // Even if no code, transfer value if present
                        if (callValue > 0) {
                            try {
                                context.state.debit(context.contractAddress, callValue);
                                context.state.credit(targetAddr, callValue);
                            } catch (Exception e) {
                                stack.push(0L); // Insufficient funds
                                break;
                            }
                        }
                        stack.push(1L); // Success (value transferred, no code to execute)
                        break;
                    }

                    // [FIX-B4] Reentrancy prevention
                    if (context.callStack.contains(targetAddr)) {
                        stack.push(0L); // Reentrancy detected — fail the call
                        log.warn("[VM] Reentrancy blocked: contract {} attempted to call itself", targetAddr);
                        break;
                    }
                    context.callStack.add(targetAddr);
                    
                    // Prepare input data
                    byte[] callInput = new byte[(int)Math.min(inLen, memory.length - inOffset)];
                    if (inLen > 0 && inOffset + inLen <= memory.length) {
                        System.arraycopy(memory, (int)inOffset, callInput, 0, (int)inLen);
                    }
                    
                    // Deduct gas
                    long childGasLimit = Math.min(callGas, gasRemaining);
                    deductGas((int)childGasLimit);
                    
                    try {
                        // Transfer value
                        if (callValue > 0) {
                            context.state.debit(context.contractAddress, callValue);
                            context.state.credit(targetAddr, callValue);
                        }
                        
                        // Clone state for child execution
                        AccountState childState = context.state.cloneState();
                        
                        // Create child context
                        Interpreter.BlockchainContext childContext = new Interpreter.BlockchainContext(
                                context.timestamp,
                                context.blockHeight,
                                context.contractAddress, // caller is current contract
                                targetAddr, // target contract
                                callValue,
                                childState,
                                context.hardware,
                                context.currentBlockHash);
                        childContext.events = new java.util.ArrayList<>();
                        
                        // Copy address registry
                        childContext.addressRegistry.putAll(context.addressRegistry);
                        
                        // [FIX-B4] Propagate call stack for reentrancy prevention
                        childContext.callStack.addAll(context.callStack);
                        
                        // Execute child interpreter
                        Interpreter childInterp = new Interpreter(targetAccount.getCode(), childGasLimit, childContext);
                        childInterp.execute();
                        
                        // Merge child state changes (contract succeeded)
                        context.state.merge(childState);
                        
                        // Copy return data
                        byte[] returnData = childInterp.getReturnData();
                        if (returnData != null && outLen > 0) {
                            int copyLen = (int)Math.min(outLen, Math.min(returnData.length, memory.length - outOffset));
                            System.arraycopy(returnData, 0, memory, (int)outOffset, copyLen);
                        }
                        
                        stack.push(1L); // 1 = success
                    } catch (Exception e) {
                        // Execution failed; revert state is handled by exception propagation
                        stack.push(0L); // 0 = failure
                    } finally {
                        context.callStack.remove(targetAddr); // [FIX-B4]
                    }
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
                    if (stack.size() > 1024) throw new Exception("Stack overflow: depth " + stack.size());
                    break;

                case POP:
                    stack.pop();
                    break;

                case DUP:
                    stack.push(stack.peek());
                    if (stack.size() > 1024) throw new Exception("Stack overflow: depth " + stack.size());
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
                    if (mDivisor == 0) {
                        stack.pop(); // Pop the dividend
                        stack.push(0L);
                    } else {
                        stack.push(stack.pop() % mDivisor);
                    }
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
                    if (divisor == 0) {
                        stack.pop(); // Pop the dividend
                        stack.push(0L);
                    } else {
                        stack.push(stack.pop() / divisor);
                    }
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

                case AND:
                    stack.push(stack.pop() & stack.pop());
                    break;

                case OR:
                    stack.push(stack.pop() | stack.pop());
                    break;

                case NOT:
                    stack.push(~stack.pop());
                    break;

                case SLOAD:
                    long sKey = stack.pop();
                    stack.push(context.state.getAccountStorage(context.contractAddress).getOrDefault(sKey, 0L));
                    break;

                case SSTORE:
                    // SSTORE(key, value): Pop value (on top), then key (below).
                    long ssVal = stack.pop(); // value is on top
                    long ssKey = stack.pop(); // key is below
                    context.state.putStorage(context.contractAddress, ssKey, ssVal);
                    break;

                case BALANCE:
                    long addrKey = stack.pop();
                    String resolvedAddr = context.addressRegistry.getOrDefault(addrKey, null);
                    if (resolvedAddr == null) {
                        // Try interpreting as a direct address hash — look up by iterating
                        resolvedAddr = context.state.getAllAddresses().stream()
                            .filter(addr -> {
                                byte[] h = Crypto.hash(addr.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                                return java.nio.ByteBuffer.wrap(h, 0, 8)
                                    .order(java.nio.ByteOrder.BIG_ENDIAN).getLong() == addrKey;
                            })
                            .findFirst()
                            .orElse(context.contractAddress);
                    }
                    stack.push(context.state.getBalance(resolvedAddr));
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
            throw new Exception("Out of gas");
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
        public java.util.Map<Long, String> addressRegistry = new java.util.HashMap<>();
        public java.util.Set<String> callStack = new java.util.HashSet<>(); // [FIX A5]

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
