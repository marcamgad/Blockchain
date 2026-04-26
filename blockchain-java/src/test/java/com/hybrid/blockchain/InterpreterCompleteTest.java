package com.hybrid.blockchain;

import com.hybrid.blockchain.testutil.*;
import org.junit.jupiter.api.*;
import static org.assertj.core.api.Assertions.*;
import java.nio.ByteBuffer;
import java.util.List;

/**
 * Exhaustive unit tests for the Smart Contract VM (Interpreter).
 * Deploys minimal bytecode snippets to verify every OpCode's functional correctness.
 */
@Tag("vm")
public class InterpreterCompleteTest {

    private TestBlockchain tb;
    private Blockchain blockchain;
    private TestKeyPair caller;
    private long nonce; // global nonce counter - incremented per-tx to avoid conflicts

    @BeforeEach
    void setUp() throws Exception {
        tb = new TestBlockchain();
        blockchain = tb.getBlockchain();
        caller = new TestKeyPair(1);
        blockchain.getAccountState().credit(caller.getAddress(), 1_000_000L);
        nonce = 0;
    }

    @AfterEach
    void tearDown() throws Exception {
        if (tb != null) tb.close();
    }

    private byte[] runAndGetResult(byte[] code) throws Exception {
        return runAndGetResult(code, 0);
    }

    private byte[] runAndGetResult(byte[] code, long value) throws Exception {
        Transaction deployTx = TestTransactionFactory.createContractCreation(caller, code, 100, ++nonce);
        BlockApplier.createAndApplyBlock(tb, List.of(deployTx));
        String contractAddr = blockchain.getStorage().loadReceipt(deployTx.getTxId()).getContractAddress();
        
        Transaction callTx = TestTransactionFactory.createContractCall(caller, contractAddr, new byte[0], value, 100, ++nonce);
        BlockApplier.createAndApplyBlock(tb, List.of(callTx));
        
        TransactionReceipt receipt = blockchain.getStorage().loadReceipt(callTx.getTxId());
        if (receipt.getStatus() == TransactionReceipt.STATUS_FAILED && receipt.getError() != null) {
            throw new RuntimeException("VM Execution failed: " + receipt.getError());
        }
        return receipt.getReturnData();
    }

    @Test
    @DisplayName("I1.1 — PUSH + STOP")
    void testPushStop() throws Exception {
        // PUSH 42, PUSH 0 (offset), MSTORE, PUSH 8 (length), PUSH 0 (offset), RETURN
        ByteBuffer ops = ByteBuffer.allocate(100);
        ops.put(OpCode.PUSH.getByte()).putLong(42L);
        ops.put(OpCode.PUSH.getByte()).putLong(0L);
        ops.put(OpCode.MSTORE.getByte());
        ops.put(OpCode.PUSH.getByte()).putLong(8L);
        ops.put(OpCode.PUSH.getByte()).putLong(0L);
        ops.put(OpCode.RETURN.getByte());
        
        byte[] result = runAndGetResult(toArray(ops));
        assertThat(ByteBuffer.wrap(result).getLong()).isEqualTo(42L);
    }

    @Test
    @DisplayName("I1.2-1.4 — Arithmetic: ADD, SUB, MUL")
    void testArithmetic() throws Exception {
        // ADD: 3 + 4
        assertThat(runMath(OpCode.ADD, 3, 4)).isEqualTo(7L);
        // SUB: 10 - 3
        assertThat(runMath(OpCode.SUB, 3, 10)).isEqualTo(7L);
        // MUL: 6 * 7
        assertThat(runMath(OpCode.MUL, 6, 7)).isEqualTo(42L);
    }

    @Test
    @DisplayName("I1.5-1.6 — DIV, MOD")
    void testDivMod() throws Exception {
        assertThat(runMath(OpCode.DIV, 3, 15)).isEqualTo(5L);
        assertThat(runMath(OpCode.DIV, 0, 15)).isEqualTo(0L); // Div by zero
        assertThat(runMath(OpCode.MOD, 5, 17)).isEqualTo(2L);
    }

    @Test
    @DisplayName("I1.7-1.9 — Logical: EQ, LT, GT")
    void testLogical() throws Exception {
        assertThat(runMath(OpCode.EQ, 5, 5)).isEqualTo(1L);
        assertThat(runMath(OpCode.EQ, 5, 6)).isEqualTo(0L);
        assertThat(runMath(OpCode.LT, 5, 3)).isEqualTo(1L);
        assertThat(runMath(OpCode.LT, 3, 5)).isEqualTo(0L);
        assertThat(runMath(OpCode.GT, 3, 5)).isEqualTo(1L);
    }

    @Test
    @DisplayName("I1.10-1.12 — Bitwise: AND, OR, NOT")
    void testBitwise() throws Exception {
        assertThat(runMath(OpCode.AND, 0b1100, 0b1010)).isEqualTo(0b1000L);
        assertThat(runMath(OpCode.OR, 0b0101, 0b1010)).isEqualTo(0b1111L);
        
        // NOT 0
        ByteBuffer ops = ByteBuffer.allocate(50);
        ops.put(OpCode.PUSH.getByte()).putLong(0L);
        ops.put(OpCode.NOT.getByte());
        ops.put(OpCode.PUSH.getByte()).putLong(0L);
        ops.put(OpCode.MSTORE.getByte());
        ops.put(OpCode.PUSH.getByte()).putLong(8L).put(OpCode.PUSH.getByte()).putLong(0L).put(OpCode.RETURN.getByte());
        
        byte[] result = runAndGetResult(toArray(ops));
        assertThat(ByteBuffer.wrap(result).getLong()).isEqualTo(~0L);
    }

    @Test
    @DisplayName("I1.13-1.14 — JUMP, JUMPI")
    void testJumps() throws Exception {
        // PUSH target, JUMP, STOP, [target]: PUSH 1, RETURN
        ByteBuffer ops = ByteBuffer.allocate(100);
        int target = 11; // Byte 0(PUSH)+8 + 1(JUMP) + 1(STOP) = 11
        ops.put(OpCode.PUSH.getByte()).putLong(target);
        ops.put(OpCode.JUMP.getByte());
        ops.put(OpCode.STOP.getByte()); 
        ops.put(OpCode.PUSH.getByte()).putLong(1L);
        ops.put(OpCode.PUSH.getByte()).putLong(0L).put(OpCode.MSTORE.getByte());
        ops.put(OpCode.PUSH.getByte()).putLong(8L).put(OpCode.PUSH.getByte()).putLong(0L).put(OpCode.RETURN.getByte());
        
        assertThat(ByteBuffer.wrap(runAndGetResult(toArray(ops))).getLong()).isEqualTo(1L);
    }

    @Test
    @DisplayName("I1.15 — MSTORE + MLOAD")
    void testMemory() throws Exception {
        ByteBuffer ops = ByteBuffer.allocate(100);
        ops.put(OpCode.PUSH.getByte()).putLong(0xDEADBEEFL);
        ops.put(OpCode.PUSH.getByte()).putLong(100L); // offset
        ops.put(OpCode.MSTORE.getByte());
        ops.put(OpCode.PUSH.getByte()).putLong(100L); // offset
        ops.put(OpCode.MLOAD.getByte());
        ops.put(OpCode.PUSH.getByte()).putLong(0L); // out offset
        ops.put(OpCode.MSTORE.getByte());
        ops.put(OpCode.PUSH.getByte()).putLong(8L).put(OpCode.PUSH.getByte()).putLong(0L).put(OpCode.RETURN.getByte());
        
        assertThat(ByteBuffer.wrap(runAndGetResult(toArray(ops))).getLong()).isEqualTo(0xDEADBEEFL);
    }

    @Test
    @DisplayName("I1.16 — SSTORE + SLOAD")
    void testStorage() throws Exception {
        ByteBuffer ops = ByteBuffer.allocate(100);
        ops.put(OpCode.PUSH.getByte()).putLong(7L);   // key
        ops.put(OpCode.PUSH.getByte()).putLong(888L); // value
        ops.put(OpCode.SSTORE.getByte());
        ops.put(OpCode.PUSH.getByte()).putLong(7L);   // key
        ops.put(OpCode.SLOAD.getByte());
        ops.put(OpCode.PUSH.getByte()).putLong(0L);   // out offset
        ops.put(OpCode.MSTORE.getByte());
        ops.put(OpCode.PUSH.getByte()).putLong(8L).put(OpCode.PUSH.getByte()).putLong(0L).put(OpCode.RETURN.getByte());
        
        assertThat(ByteBuffer.wrap(runAndGetResult(toArray(ops))).getLong()).isEqualTo(888L);
    }

    @Test
    @DisplayName("I1.20 — CALLER")
    void testCallerAddress() throws Exception {
        ByteBuffer ops = ByteBuffer.allocate(50);
        ops.put(OpCode.CALLER.getByte());
        ops.put(OpCode.PUSH.getByte()).putLong(0L);
        ops.put(OpCode.MSTORE.getByte());
        ops.put(OpCode.PUSH.getByte()).putLong(8L).put(OpCode.PUSH.getByte()).putLong(0L).put(OpCode.RETURN.getByte());
        
        byte[] result = runAndGetResult(toArray(ops));
        long callerHashPrefix = ByteBuffer.wrap(Crypto.hash(caller.getAddress().getBytes(java.nio.charset.StandardCharsets.UTF_8))).getLong();
        assertThat(ByteBuffer.wrap(result).getLong()).isEqualTo(callerHashPrefix);
    }

    @Test
    @DisplayName("I1.25 — REVERT")
    void testRevert() throws Exception {
        // Code: PUSH key=0, PUSH val=1, SSTORE (writes slot 0=1)
        //       then PUSH rLen=0, PUSH rOff=0, REVERT (reverts the SSTORE)
        ByteBuffer ops = ByteBuffer.allocate(60);
        ops.put(OpCode.PUSH.getByte()).putLong(0L); // key
        ops.put(OpCode.PUSH.getByte()).putLong(1L); // value
        ops.put(OpCode.SSTORE.getByte()); // Write to slot 0 = 1
        // REVERT args: length=0 (top), offset=0 (below top)
        ops.put(OpCode.PUSH.getByte()).putLong(0L); // rLen on top
        ops.put(OpCode.PUSH.getByte()).putLong(0L); // rOff below
        ops.put(OpCode.REVERT.getByte());
        
        Transaction deployTx = TestTransactionFactory.createContractCreation(caller, toArray(ops), 100, 1);
        BlockApplier.createAndApplyBlock(tb, List.of(deployTx));
        String addr = blockchain.getStorage().loadReceipt(deployTx.getTxId()).getContractAddress();
        
        Transaction callTx = TestTransactionFactory.createContractCall(caller, addr, new byte[0], 0, 100, 2);
        BlockApplier.createAndApplyBlock(tb, List.of(callTx));
        
        TransactionReceipt receipt = blockchain.getStorage().loadReceipt(callTx.getTxId());
        assertThat(receipt.getStatus()).as("Should be REVERTED").isIn(TransactionReceipt.STATUS_REVERTED, TransactionReceipt.STATUS_FAILED);
        // Use raw map access (not ContractState.get which defaults to 0L) to check for absent key
        assertThat(blockchain.getAccountState().getAccountStorage(addr).getStorage().get(0L))
                .as("Slot 0 should remain null/absent after revert").isNull();
    }

    @Test
    @DisplayName("I1.27 — CALL opcode value transfer")
    void testCallOpcodeValue() throws Exception {
        TestKeyPair targetKey = new TestKeyPair(2);
        // Target has code that just returns the input
        byte[] targetCode = { (byte)OpCode.STOP.getByte() };
        Transaction depTarget = TestTransactionFactory.createContractCreation(caller, targetCode, 100, 1);
        BlockApplier.createAndApplyBlock(tb, List.of(depTarget));
        String targetAddr = blockchain.getStorage().loadReceipt(depTarget.getTxId()).getContractAddress();
        
        // Proxy contract calls target with 500 value
        ByteBuffer ops = ByteBuffer.allocate(100);
        ops.put(OpCode.PUSH.getByte()).putLong(0); // out length
        ops.put(OpCode.PUSH.getByte()).putLong(0); // out offset
        ops.put(OpCode.PUSH.getByte()).putLong(0); // in length
        ops.put(OpCode.PUSH.getByte()).putLong(0); // in offset
        ops.put(OpCode.PUSH.getByte()).putLong(500); // value
        // Push target address hash prefix
        ops.put(OpCode.PUSH.getByte()).putLong(ByteBuffer.wrap(Crypto.hash(targetAddr.getBytes(java.nio.charset.StandardCharsets.UTF_8))).getLong()); // toAddr
        ops.put(OpCode.PUSH.getByte()).putLong(1000L); // callGas
        ops.put(OpCode.CALL.getByte());
        ops.put(OpCode.STOP.getByte());
        
        Transaction depProxy = TestTransactionFactory.createContractCreation(caller, toArray(ops), 100, 2);
        BlockApplier.createAndApplyBlock(tb, List.of(depProxy));
        String proxyAddr = blockchain.getStorage().loadReceipt(depProxy.getTxId()).getContractAddress();
        
        // Fund proxy so it can transfer
        blockchain.getAccountState().credit(proxyAddr, 1000L);
        
        Transaction callTx = TestTransactionFactory.createContractCall(caller, proxyAddr, new byte[0], 0, 100, 3);
        BlockApplier.createAndApplyBlock(tb, List.of(callTx));
        
        assertThat(blockchain.getBalance(targetAddr)).isEqualTo(500L);
    }

    @Test
    @DisplayName("I1.29 — Gas exhaustion")
    void testGasExhaustion() throws Exception {
        // Infinite loop: JUMP back to 0
        ByteBuffer ops = ByteBuffer.allocate(20);
        ops.put(OpCode.PUSH.getByte()).putLong(0L);
        ops.put(OpCode.JUMP.getByte());
        
        Config.BYPASS_CONTRACT_AUDIT = true;
        try {
            Transaction dep = TestTransactionFactory.createContractCreation(caller, toArray(ops), 100, ++nonce);
            BlockApplier.createAndApplyBlock(tb, List.of(dep));
            String addr = blockchain.getStorage().loadReceipt(dep.getTxId()).getContractAddress();
            
            // Call with low fee (gasLimit = fee * 100 in Interpreter)
            Transaction call = TestTransactionFactory.createContractCall(caller, addr, new byte[0], 0, 1, ++nonce);
            BlockApplier.createAndApplyBlock(tb, List.of(call));
            
            TransactionReceipt receipt = blockchain.getStorage().loadReceipt(call.getTxId());
            assertThat(receipt.getStatus()).isEqualTo(TransactionReceipt.STATUS_FAILED);
            assertThat(receipt.getError()).contains("Out of gas");
        } finally {
            Config.BYPASS_CONTRACT_AUDIT = false;
        }
    }

    @Test
    @DisplayName("I1.36 — Stack overflow")
    void testStackOverflow() throws Exception {
        // Each PUSH = 1 byte opcode + 8 bytes value = 9 bytes. 1025 pushes = 9225 bytes.
        int pushCount = 1025;
        ByteBuffer ops = ByteBuffer.allocate(pushCount * 9 + 1);
        for (int i = 0; i < pushCount; i++) {
            ops.put(OpCode.PUSH.getByte()).putLong(i);
        }
        ops.put(OpCode.STOP.getByte());
        
        Config.BYPASS_CONTRACT_AUDIT = true;
        try {
            assertThatThrownBy(() -> runAndGetResult(toArray(ops)))
                    .hasMessageContaining("Stack overflow");
        } finally {
            Config.BYPASS_CONTRACT_AUDIT = false;
        }
    }

    private long runMath(OpCode op, long v1, long v2) throws Exception {
        ByteBuffer ops = ByteBuffer.allocate(100);
        ops.put(OpCode.PUSH.getByte()).putLong(v2);
        ops.put(OpCode.PUSH.getByte()).putLong(v1);
        ops.put(op.getByte());
        ops.put(OpCode.PUSH.getByte()).putLong(0L);
        ops.put(OpCode.MSTORE.getByte());
        ops.put(OpCode.PUSH.getByte()).putLong(8L).put(OpCode.PUSH.getByte()).putLong(0L).put(OpCode.RETURN.getByte());
        
        byte[] result = runAndGetResult(toArray(ops));
        return ByteBuffer.wrap(result).getLong();
    }

    private byte[] toArray(ByteBuffer bb) {
        byte[] arr = new byte[bb.position()];
        bb.flip();
        bb.get(arr);
        return arr;
    }
}
