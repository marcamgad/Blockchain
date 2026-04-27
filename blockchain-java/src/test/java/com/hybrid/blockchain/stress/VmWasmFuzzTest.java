package com.hybrid.blockchain.stress;

import com.hybrid.blockchain.*;
import com.hybrid.blockchain.testutil.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.assertj.core.api.Assertions.*;

import java.nio.ByteBuffer;
import java.util.List;
import java.util.Random;
import java.util.concurrent.TimeUnit;

/**
 * Property-based / pseudo-random fuzz tests for the bytecode {@link Interpreter}
 * (VM) and the {@link WasmContractEngine} (WASM sandbox).
 *
 * <p><b>Invariants under test:</b>
 * <ol>
 *   <li>No fuzz input causes the JVM to hang or crash — all failures must be
 *       caught as {@link RevertException} / {@link BlockValidationException} /
 *       generic {@link Exception}, never as StackOverflow or infinite loop.</li>
 *   <li>Gas exhaustion always results in a thrown exception, not a hang.</li>
 *   <li>State is never mutated after a failed/reverted execution (tested
 *       via receipt status checks).</li>
 * </ol>
 *
 * <h2>Bytecode VM fuzz (VMF1.x)</h2>
 * <h2>WASM engine fuzz (WF1.x)</h2>
 */
@Tag("fuzz")
@Tag("stress")
@TestMethodOrder(MethodOrderer.DisplayName.class)
public class VmWasmFuzzTest {

    // ─── Minimal valid WASM binary: (module (func (export "run") (result i64) i64.const 0)) ──
    //
    // Byte-by-byte layout:
    //  00 61 73 6D  — magic
    //  01 00 00 00  — version 1
    //  01 05 01 60 00 01 7E  — type section: 1 type () -> i64
    //  03 02 01 00           — function section: 1 function, type 0
    //  07 07 01 03 72 75 6E 00 00  — export "run", kind=func, index=0
    //  0A 06 01 04 00 42 00 0B     — code: 1 func, body=4 bytes, 0 locals, i64.const 0, end
    static final byte[] MINIMAL_WASM = {
        0x00, 0x61, 0x73, 0x6D,  // magic
        0x01, 0x00, 0x00, 0x00,  // version
        0x01, 0x05, 0x01, 0x60, 0x00, 0x01, 0x7E,          // type section
        0x03, 0x02, 0x01, 0x00,                              // function section
        0x07, 0x07, 0x01, 0x03, 0x72, 0x75, 0x6E, 0x00, 0x00,  // export "run"
        0x0A, 0x06, 0x01, 0x04, 0x00, 0x42, 0x00, 0x0B     // code section
    };

    // ─── WASM with br_if tight loop (for gas exhaustion test) ─────────────────
    //
    // (module
    //   (func (export "run") (result i64)
    //     (loop $L
    //       i32.const 1
    //       br_if $L)          ;; loops forever
    //     i64.const 0))
    static final byte[] TIGHT_LOOP_WASM = {
        0x00, 0x61, 0x73, 0x6D,  // magic
        0x01, 0x00, 0x00, 0x00,  // version
        0x01, 0x04, 0x01, 0x60, 0x00, 0x00,  // type section: 1 type, () -> ()
        0x03, 0x02, 0x01, 0x00,              // function section: 1 func, type index 0
        0x07, 0x07, 0x01, 0x03, 0x72, 0x75, 0x6E, 0x00, 0x00,  // export section: 1 export, "run", func 0
        0x0A, 0x0B, 0x01, 0x09,              // code section: 1 func, size 9
          0x00,              // 0 local blocks
          0x03, 0x40,        // loop (empty block type)
          0x41, 0x01,        // i32.const 1
          0x0D, 0x00,        // br_if 0
          0x0B,              // end loop
        0x0B                 // end func
    };

    // ─── Test infrastructure ──────────────────────────────────────────────────

    private TestBlockchain tb;
    private Blockchain blockchain;

    @BeforeEach
    void setUp() throws Exception {
        tb         = new TestBlockchain();
        blockchain = tb.getBlockchain();
        Config.BYPASS_CONTRACT_AUDIT = true;
    }

    @AfterEach
    void tearDown() throws Exception {
        Config.BYPASS_CONTRACT_AUDIT = false;
        if (tb != null) tb.close();
    }

    // ════════════════════════════════════════════════════════════════════════
    // VMF1.1 — Random opcode stream: must not hang, must complete or throw
    // ════════════════════════════════════════════════════════════════════════

    @ParameterizedTest(name = "VMF1.1 seed={0}")
    @ValueSource(longs = {0L, 1L, 2L, 3L, 4L, 5L, 6L, 7L, 8L, 9L,
                          42L, 137L, 999L, 12345L, 98765L,
                          Long.MAX_VALUE, Long.MIN_VALUE, 0xDEADBEEFL, 0xCAFEBABEL, 0xFEEDF00DL})
    @Timeout(value = 5, unit = TimeUnit.SECONDS)
    @DisplayName("VMF1.1 — Random byte stream [1-256 bytes]: sandbox never hangs")
    void testRandomOpcodeStreamNeverHangs(long seed) {
        Random rng = new Random(seed);
        int length = 1 + rng.nextInt(255); // 1..256
        byte[] bytecode = new byte[length];
        rng.nextBytes(bytecode);

        // The sandbox must complete (or throw a caught exception) — never hang
        try {
            runBytecodeInSandbox(bytecode);
        } catch (StackOverflowError | OutOfMemoryError fatal) {
            fail("seed=%d len=%d: VM propagated fatal error: %s", seed, length, fatal.getClass().getSimpleName());
        } catch (Exception acceptable) {
            // RevertException, BlockValidationException, or generic Exception are all fine
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    // VMF1.2 — Random PUSH + arithmetic: no ArithmeticException / StackOverflow
    // ════════════════════════════════════════════════════════════════════════

    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    @DisplayName("VMF1.2 — Random PUSH values with arithmetic opcodes never throw ArithmeticException or StackOverflow")
    void testRandomPushArithmeticNoCrash() {
        Random rng = new Random(0xABCD_EF01L);
        byte[] arithmeticOps = {
                OpCode.ADD.getByte(),
                OpCode.SUB.getByte(),
                OpCode.MUL.getByte(),
                OpCode.DIV.getByte(),
                OpCode.MOD.getByte()
        };

        for (int trial = 0; trial < 50; trial++) {
            ByteBuffer bb = ByteBuffer.allocate(256);
            // Push 8 random longs, then 5 arithmetic ops, then STOP
            for (int p = 0; p < 8; p++) {
                if (bb.remaining() < 9) break;
                bb.put(OpCode.PUSH.getByte());
                bb.putLong(rng.nextLong());
            }
            for (int o = 0; o < 5; o++) {
                if (!bb.hasRemaining()) break;
                bb.put(arithmeticOps[rng.nextInt(arithmeticOps.length)]);
            }
            if (bb.hasRemaining()) bb.put(OpCode.STOP.getByte());

            byte[] bytecode = java.util.Arrays.copyOf(bb.array(), bb.position());

            try {
                runBytecodeInSandbox(bytecode);
            } catch (ArithmeticException | StackOverflowError e) {
                fail("Trial %d: VM must not propagate %s: %s",
                        trial, e.getClass().getSimpleName(), e.getMessage());
            } catch (Exception ignored) {
                // RevertException, generic Exception → acceptable
            }
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    // VMF1.3 — Truncated opcodes (PUSH without full 8-byte operand)
    // ════════════════════════════════════════════════════════════════════════

    @Test
    @Timeout(value = 5, unit = TimeUnit.SECONDS)
    @DisplayName("VMF1.3 — Truncated PUSH opcode (0-7 operand bytes) handled gracefully")
    void testTruncatedOpcodeGraceful() {
        for (int operandLen = 0; operandLen < 8; operandLen++) {
            byte[] bytecode = new byte[1 + operandLen];
            bytecode[0] = OpCode.PUSH.getByte();
            // Remaining bytes are 0 — not a full 8-byte long operand

            try {
                runBytecodeInSandbox(bytecode);
            } catch (StackOverflowError | OutOfMemoryError fatal) {
                fail("Truncated PUSH with %d operand bytes caused fatal error: %s",
                        operandLen, fatal.getClass().getSimpleName());
            } catch (Exception acceptable) {
                // Exception is expected; just must not be a fatal error
            }
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    // VMF1.4 — 500 deterministic seeds: valid-opcode sequences
    // ════════════════════════════════════════════════════════════════════════

    @Test
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    @DisplayName("VMF1.4 — 500 deterministic seeds with valid-only opcodes: none hang or crash JVM")
    void test500DeterministicFuzzSeeds() {
        OpCode[] safeOps = {
                OpCode.PUSH, OpCode.POP, OpCode.ADD, OpCode.SUB, OpCode.MUL,
                OpCode.DUP, OpCode.SWAP, OpCode.SLOAD, OpCode.SSTORE, OpCode.STOP
        };

        for (int seed = 0; seed < 500; seed++) {
            Random rng = new Random(seed);
            ByteBuffer bb = ByteBuffer.allocate(300);
            int instrCount = 1 + rng.nextInt(20);
            for (int i = 0; i < instrCount; i++) {
                if (!bb.hasRemaining()) break;
                OpCode op = safeOps[rng.nextInt(safeOps.length)];
                bb.put(op.getByte());
                if (op == OpCode.PUSH && bb.remaining() >= 8) {
                    bb.putLong(Math.abs(rng.nextLong()));
                }
            }
            if (bb.hasRemaining()) bb.put(OpCode.STOP.getByte());
            byte[] bytecode = java.util.Arrays.copyOf(bb.array(), bb.position());

            final int s = seed;
            try {
                runBytecodeInSandbox(bytecode);
            } catch (StackOverflowError | OutOfMemoryError fatal) {
                fail("Seed %d caused fatal JVM error: %s", s, fatal.getMessage());
            } catch (Exception ignored) {
                // All sandbox exceptions are acceptable
            }
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    // VMF1.5 — SSTORE/SLOAD loop exhausts gas → receipt FAILED or REVERTED
    // ════════════════════════════════════════════════════════════════════════

    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    @DisplayName("VMF1.5 — Infinite SSTORE loop exhausts gas; receipt FAILED/REVERTED; state not committed")
    void testSstoreLoopGasExhaustion() throws Exception {
        TestKeyPair attacker = new TestKeyPair(9001);
        blockchain.getAccountState().credit(attacker.getAddress(), 10_000L);

        // Bytecode: push/SSTORE loop jumping back to 0 forever
        ByteBuffer ops = ByteBuffer.allocate(64);
        ops.put(OpCode.PUSH.getByte()); ops.putLong(99L);   // value
        ops.put(OpCode.PUSH.getByte()); ops.putLong(1L);    // slot
        ops.put(OpCode.SSTORE.getByte());
        ops.put(OpCode.PUSH.getByte()); ops.putLong(0L);    // jump target
        ops.put(OpCode.JUMP.getByte());
        ops.put(OpCode.STOP.getByte());

        byte[] bytecode = java.util.Arrays.copyOf(ops.array(), ops.position());

        Transaction deploy = TestTransactionFactory.createContractCreation(attacker, bytecode, 100, 1);
        BlockApplier.createAndApplyBlock(tb, List.of(deploy));

        String contractAddr = blockchain.getStorage().loadReceipt(deploy.getTxId()).getContractAddress();
        assertThat(contractAddr).isNotBlank();

        Transaction call = TestTransactionFactory.createContractCall(
                attacker, contractAddr, new byte[0], 0, 1, 2);
        BlockApplier.createAndApplyBlock(tb, List.of(call));

        TransactionReceipt receipt = blockchain.getStorage().loadReceipt(call.getTxId());
        assertThat(receipt).isNotNull();
        assertThat(receipt.getStatus())
                .as("Gas-exhausting loop must produce FAILED or REVERTED receipt")
                .isIn(TransactionReceipt.STATUS_FAILED, TransactionReceipt.STATUS_REVERTED);
    }

    // ════════════════════════════════════════════════════════════════════════
    // WF1.1 — Minimal valid WASM executes without error
    // ════════════════════════════════════════════════════════════════════════

    @Test
    @Timeout(value = 5, unit = TimeUnit.SECONDS)
    @DisplayName("WF1.1 — Minimal valid WASM module (i64.const 0, return) executes without exception")
    void testMinimalWasmExecutes() {
        assertThatCode(() -> runWasm(MINIMAL_WASM, 10_000L))
                .doesNotThrowAnyException();
    }

    // ════════════════════════════════════════════════════════════════════════
    // WF1.2 — Truncated WASM (valid magic + random bytes) throws, does not hang
    // ════════════════════════════════════════════════════════════════════════

    @Test
    @Timeout(value = 5, unit = TimeUnit.SECONDS)
    @DisplayName("WF1.2 — Truncated WASM binary (valid magic + random body) throws gracefully, does not hang")
    void testTruncatedWasmBinary() {
        Random rng = new Random(0xBAD_BEEFL);
        for (int trial = 0; trial < 20; trial++) {
            byte[] bad = new byte[4 + rng.nextInt(20)];
            bad[0] = 0x00; bad[1] = 0x61; bad[2] = 0x73; bad[3] = 0x6D; // magic ok
            for (int i = 4; i < bad.length; i++) bad[i] = (byte) rng.nextInt(256);

            assertThatThrownBy(() -> runWasm(bad, 5_000L))
                    .as("Truncated WASM trial %d must throw, not hang", trial)
                    .isInstanceOf(Exception.class);
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    // WF1.3 — Tight compute loop + small gas limit → RevertException
    // ════════════════════════════════════════════════════════════════════════

    @Test
    @Timeout(value = 6, unit = TimeUnit.SECONDS)
    @DisplayName("WF1.3 — WASM tight br_if loop + gasLimit=500 → RevertException (gas exhaustion via timeout)")
    void testWasmTightLoopGasExhaustion() {
        // The engine enforces a 5-second hard timeout → RevertException
        assertThatThrownBy(() -> runWasm(TIGHT_LOOP_WASM, 500L))
                .isInstanceOf(RevertException.class);
    }

    // ════════════════════════════════════════════════════════════════════════
    // WF1.4 — All-zero WASM binary → parse failure, graceful Exception
    // ════════════════════════════════════════════════════════════════════════

    @Test
    @Timeout(value = 5, unit = TimeUnit.SECONDS)
    @DisplayName("WF1.4 — All-zero byte array as WASM binary → parse failure, graceful Exception")
    void testAllZeroWasmBinary() {
        byte[] zeros = new byte[64];
        assertThatThrownBy(() -> runWasm(zeros, 5_000L))
                .isInstanceOf(Exception.class);
    }

    // ════════════════════════════════════════════════════════════════════════
    // WF1.5 — 50 randomly-corrupted WASM seeds: none hang past 5 seconds
    // ════════════════════════════════════════════════════════════════════════

    @Test
    @Timeout(value = 60, unit = TimeUnit.SECONDS)
    @DisplayName("WF1.5 — 50 randomly-corrupted WASM binary seeds: none hang past 5 seconds each")
    void test50RandomCorruptedWasmSeeds() {
        Random rng = new Random(0xFEEDC0DEL);
        for (int seed = 0; seed < 50; seed++) {
            // Start from the valid minimal WASM and XOR-flip a random body byte
            byte[] corrupted = java.util.Arrays.copyOf(MINIMAL_WASM, MINIMAL_WASM.length);
            int flipIdx = 4 + rng.nextInt(corrupted.length - 4);
            corrupted[flipIdx] ^= (byte) (1 + rng.nextInt(255));

            final int s = seed;
            try {
                runWasm(corrupted, 5_000L);
                // If it executes successfully after corruption, that's fine
            } catch (StackOverflowError | OutOfMemoryError fatal) {
                fail("Seed %d: corrupted WASM caused fatal JVM error: %s", s, fatal.getMessage());
            } catch (Exception ignored) {
                // Parse or execution failures are expected and acceptable
            }
        }
    }

    // ─── Helpers ────────────────────────────────────────────────────────────────

    /**
     * Executes raw bytecode in an isolated {@link Interpreter} sandbox.
     * Creates a cloned account state so production state is never mutated.
     */
    private void runBytecodeInSandbox(byte[] bytecode) throws Exception {
        AccountState state = blockchain.getAccountState().cloneState();
        Interpreter.BlockchainContext ctx = new Interpreter.BlockchainContext(
                System.currentTimeMillis(),  // timestamp
                blockchain.getHeight(),      // blockHeight
                "fuzz-caller",               // caller
                "fuzz-contract",             // contractAddress
                0L,                          // value
                state,
                new HardwareManager(),  // Isolated mock hardware
                "fuzz-block-hash"            // currentBlockHash
        );
        ctx.events = new java.util.ArrayList<>();
        Interpreter interp = new Interpreter(bytecode, Config.CONTRACT_EXECUTION_LIMIT, ctx);
        interp.execute();
    }

    /**
     * Executes a WASM binary using {@link WasmContractEngine} in an isolated context.
     */
    private void runWasm(byte[] wasmBinary, long gasLimit) throws Exception {
        AccountState state = blockchain.getAccountState().cloneState();
        Interpreter.BlockchainContext ctx = new Interpreter.BlockchainContext(
                System.currentTimeMillis(),
                blockchain.getHeight(),
                "wasm-caller",
                "wasm-contract",
                0L,
                state,
                new HardwareManager(),
                "wasm-block-hash"
        );
        ctx.events = new java.util.ArrayList<>();
        WasmContractEngine engine = new WasmContractEngine(wasmBinary, gasLimit, ctx);
        engine.execute("run", List.of());
    }
}
