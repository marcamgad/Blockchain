package com.hybrid.blockchain;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@Tag("unit")
class SmartContractTest extends TestHarness {

    private BigInteger senderPriv;
    private byte[] senderPub;
    private String sender;
    private PoAConsensus poa;
    private Validator leader;
    private BigInteger leaderPriv;

    @BeforeEach
    void setUp() throws Exception {
        System.setProperty("DEBUG", "false");
        List<Validator> validators = defaultValidators();
        poa = new PoAConsensus(validators);
        tempDir = java.nio.file.Files.createTempDirectory("sc-");
        storage = new Storage(tempDir.toString(), TEST_AES_KEY);
        blockchain = new Blockchain(storage, new Mempool(1000), poa);
        blockchain.init();

        senderPriv = privateKey(4001);
        senderPub = Crypto.derivePublicKey(senderPriv);
        sender = Crypto.deriveAddress(senderPub);
        blockchain.getState().credit(sender, 1_000_000);

        leader = validators.get(0);
        leaderPriv = privateKey(101);
    }

    private byte[] push(long value) {
        ByteBuffer buf = ByteBuffer.allocate(9).order(ByteOrder.BIG_ENDIAN);
        buf.put(OpCode.PUSH.getByte());
        buf.putLong(value);
        return buf.array();
    }

    private byte[] code(byte[]... chunks) {
        int total = 0;
        for (byte[] c : chunks) total += c.length;
        byte[] out = new byte[total];
        int off = 0;
        for (byte[] c : chunks) {
            System.arraycopy(c, 0, out, off, c.length);
            off += c.length;
        }
        return out;
    }

    @Test
    @DisplayName("ADD program leaves correct result on stack")
    void addProgramWorks() throws Exception {
        byte[] program = code(push(2), push(3), new byte[]{OpCode.ADD.getByte(), OpCode.STOP.getByte()});
        Interpreter vm = new Interpreter(program, 1000, new Interpreter.BlockchainContext(System.currentTimeMillis(), 1, sender, "hb-c", 0, new AccountState(), new HardwareManager(), "h"));
        vm.execute();

        assertEquals(5L, vm.getStack().peek(), "ADD must leave exact arithmetic sum at stack top");
    }

    @Test
    @DisplayName("SUB MUL DIV pipeline computes expected result")
    void subMulDivProgramWorks() throws Exception {
        byte[] program = code(
                push(10), push(4), new byte[]{OpCode.SUB.getByte()},
                push(3), new byte[]{OpCode.MUL.getByte()},
                push(2), new byte[]{OpCode.DIV.getByte(), OpCode.STOP.getByte()}
        );
        Interpreter vm = new Interpreter(program, 5000, new Interpreter.BlockchainContext(System.currentTimeMillis(), 1, sender, "hb-c", 0, new AccountState(), new HardwareManager(), "h"));
        vm.execute();

        assertEquals(9L, vm.getStack().peek(), "Arithmetic sequence ((10-4)*3)/2 must evaluate to 9");
    }

    @Test
    @DisplayName("Division by zero throws with explicit zero message")
    void divisionByZeroThrows() {
        byte[] program = code(push(10), push(0), new byte[]{OpCode.DIV.getByte()});
        Interpreter vm = new Interpreter(program, 500, new Interpreter.BlockchainContext(System.currentTimeMillis(), 1, sender, "hb-c", 0, new AccountState(), new HardwareManager(), "h"));

        Exception ex = assertThrows(Exception.class, vm::execute, "DIV by zero must throw instead of producing undefined behavior");
        assertTrue(ex.getMessage().toLowerCase().contains("zero"), "Division by zero exception must mention zero divisor");
    }

    @Test
    @DisplayName("Program exceeding gas limit throws gas exhaustion error")
    void gasExceededThrows() {
        byte[] program = code(push(1), push(2), new byte[]{OpCode.ADD.getByte(), OpCode.ADD.getByte(), OpCode.ADD.getByte()});
        Interpreter vm = new Interpreter(program, 1, new Interpreter.BlockchainContext(System.currentTimeMillis(), 1, sender, "hb-c", 0, new AccountState(), new HardwareManager(), "h"));

        Exception ex = assertThrows(Exception.class, vm::execute, "Interpreter must stop execution when gas limit is exceeded");
        assertTrue(ex.getMessage().toLowerCase().contains("gas"), "Gas exhaustion error must explicitly mention gas limit");
    }

    @Test
    @DisplayName("Stack overflow over 1024 items throws")
    void stackOverflowThrows() {
        byte[] program = new byte[1025 * 9];
        int offset = 0;
        for (int i = 0; i < 1025; i++) {
            byte[] push = push(i);
            System.arraycopy(push, 0, program, offset, push.length);
            offset += push.length;
        }
        Interpreter vm = new Interpreter(program, 1_000_000, new Interpreter.BlockchainContext(System.currentTimeMillis(), 1, sender, "hb-c", 0, new AccountState(), new HardwareManager(), "h"));

        Exception ex = assertThrows(Exception.class, vm::execute, "Pushing more than 1024 stack items must throw a stack overflow error");
        assertTrue(ex.getMessage().toLowerCase().contains("stack"), "Stack overflow exception must mention stack capacity overflow");
    }

    @Test
    @DisplayName("SSTORE followed by SLOAD returns stored value")
    void sstoreThenSload() throws Exception {
        AccountState state = new AccountState();
        byte[] program = code(push(10), push(99), new byte[]{OpCode.SSTORE.getByte()}, push(10), new byte[]{OpCode.SLOAD.getByte(), OpCode.STOP.getByte()});
        Interpreter vm = new Interpreter(program, 20_000, new Interpreter.BlockchainContext(System.currentTimeMillis(), 1, sender, "hb-contract", 0, state, new HardwareManager(), "h"));
        vm.execute();

        assertEquals(99L, vm.getStack().peek(), "SLOAD must return the exact value previously written by SSTORE for same storage key");
    }

    @Test
    @DisplayName("TIMESTAMP opcode pushes exact context timestamp")
    void timestampOpcode() throws Exception {
        long ts = System.currentTimeMillis();
        Interpreter vm = new Interpreter(new byte[]{OpCode.TIMESTAMP.getByte(), OpCode.STOP.getByte()}, 100, new Interpreter.BlockchainContext(ts, 7, sender, "hb-c", 0, new AccountState(), new HardwareManager(), "h"));
        vm.execute();

        assertEquals(ts, vm.getStack().peek(), "TIMESTAMP opcode must push context.timestamp exactly");
    }

    @Test
    @DisplayName("NUMBER opcode pushes exact block height")
    void numberOpcode() throws Exception {
        Interpreter vm = new Interpreter(new byte[]{OpCode.NUMBER.getByte(), OpCode.STOP.getByte()}, 100, new Interpreter.BlockchainContext(System.currentTimeMillis(), 42, sender, "hb-c", 0, new AccountState(), new HardwareManager(), "h"));
        vm.execute();

        assertEquals(42L, vm.getStack().peek(), "NUMBER opcode must push context blockHeight exactly");
    }

    @Test
    @DisplayName("CALLER opcode is deterministic and non-zero for same caller")
    void callerOpcodeDeterministicNonZero() throws Exception {
        Interpreter vm1 = new Interpreter(new byte[]{OpCode.CALLER.getByte(), OpCode.STOP.getByte()}, 100, new Interpreter.BlockchainContext(System.currentTimeMillis(), 1, sender, "hb-c", 0, new AccountState(), new HardwareManager(), "h"));
        Interpreter vm2 = new Interpreter(new byte[]{OpCode.CALLER.getByte(), OpCode.STOP.getByte()}, 100, new Interpreter.BlockchainContext(System.currentTimeMillis(), 1, sender, "hb-c", 0, new AccountState(), new HardwareManager(), "h"));
        vm1.execute();
        vm2.execute();

        long value1 = vm1.getStack().peek();
        long value2 = vm2.getStack().peek();
        assertNotEquals(0L, value1, "CALLER opcode must map non-empty caller address to non-zero deterministic value");
        assertEquals(value1, value2, "CALLER opcode output must be deterministic for same caller input");
    }

    @Test
    @DisplayName("Unknown opcode throws with byte offset information")
    void unknownOpcodeThrows() {
        Interpreter vm = new Interpreter(new byte[]{(byte) 0x7F}, 100, new Interpreter.BlockchainContext(System.currentTimeMillis(), 1, sender, "hb-c", 0, new AccountState(), new HardwareManager(), "h"));
        Exception ex = assertThrows(Exception.class, vm::execute, "Unknown opcode must throw instead of silently continuing");
        assertTrue(ex.getMessage().toLowerCase().contains("opcode") || ex.getMessage().toLowerCase().contains("at"), "Unknown opcode error must identify opcode/offset context");
    }

    @Test
    @DisplayName("STOP opcode halts execution cleanly")
    void stopHaltsCleanly() {
        Interpreter vm = new Interpreter(new byte[]{OpCode.STOP.getByte()}, 100, new Interpreter.BlockchainContext(System.currentTimeMillis(), 1, sender, "hb-c", 0, new AccountState(), new HardwareManager(), "h"));
        assertDoesNotThrow(vm::execute, "STOP opcode must halt execution without raising an exception");
    }

    @Test
    @DisplayName("CONTRACT deployment transaction stores bytecode in derived contract account")
    void deployContractStoresCode() throws Exception {
        byte[] contractCode = code(push(1), push(2), new byte[]{OpCode.ADD.getByte(), OpCode.STOP.getByte()});

        Transaction deploy = new Transaction.Builder()
                .type(Transaction.Type.CONTRACT)
                .to(null)
                .amount(0)
                .fee(10)
                .nonce(1)
                .data(contractCode)
                .sign(senderPriv, senderPub);

        blockchain.addTransaction(deploy);
        Block block = blockchain.createBlock(leader.getId(), 100);
        poa.signBlock(block, leader, leaderPriv);
        blockchain.applyBlock(block);

        long nonceAfterDebit = 1L;
        String contractAddress = Crypto.deriveAddress(Crypto.hash((sender + nonceAfterDebit).getBytes()));
        assertArrayEquals(contractCode, blockchain.getState().getAccount(contractAddress).getCode(), "Deployed contract bytecode must be persisted in derived contract account");
    }

    @Test
    @DisplayName("CONTRACT call executes persisted bytecode against live account storage")
    void callExistingContractExecutesCode() throws Exception {
        byte[] code = code(push(7), push(55), new byte[]{OpCode.SSTORE.getByte(), OpCode.STOP.getByte()});
        String contractAddress = "hb-existing-contract";
        blockchain.getState().ensure(contractAddress);
        blockchain.getState().getAccount(contractAddress).setCode(code);

        Transaction call = new Transaction.Builder()
                .type(Transaction.Type.CONTRACT)
                .to(contractAddress)
                .amount(0)
                .fee(20)
                .nonce(1)
                .data(new byte[0])
                .sign(senderPriv, senderPub);

        blockchain.addTransaction(call);
        Block block = blockchain.createBlock(leader.getId(), 100);
        poa.signBlock(block, leader, leaderPriv);
        blockchain.applyBlock(block);

        assertEquals(55L, blockchain.getState().getAccountStorage(contractAddress).get(7), "Contract invocation must update live state storage according to contract bytecode");
    }

    @Test
    @DisplayName("isWasm identifies valid WASM magic prefix and rejects non-WASM arrays")
    void isWasmRoutingChecks() {
        assertTrue(blockchain.isWasm(new byte[]{0x00, 0x61, 0x73, 0x6D}), "WASM magic bytes must be recognized by routing logic");
        assertFalse(blockchain.isWasm(new byte[]{0x01, 0x02, 0x03, 0x04}), "Non-WASM byte arrays must route to bytecode interpreter path");
        assertFalse(blockchain.isWasm(null), "Null byte arrays must never be treated as WASM");
        assertFalse(blockchain.isWasm(new byte[0]), "Empty byte arrays must never be treated as WASM");
        assertFalse(blockchain.isWasm(new byte[]{0x00, 0x61, 0x73}), "Arrays shorter than 4 bytes must never be treated as WASM");
    }
}
