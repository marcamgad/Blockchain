package com.hybrid.blockchain;

// FIX 5: Add gasConsumed tracking; throw RevertException (not generic Exception) on gas
// exhaustion, consistent with the bytecode VM. Expose getGasConsumed() so the caller
// can report actual gas used instead of the full gasLimit.

import com.dylibso.chicory.runtime.HostFunction;
import com.dylibso.chicory.runtime.ImportFunction;
import com.dylibso.chicory.runtime.Instance;
import com.dylibso.chicory.runtime.ImportValues;
import com.dylibso.chicory.runtime.MStack;
import com.dylibso.chicory.wasm.types.Instruction;
import com.dylibso.chicory.wasm.types.OpCode;
import com.dylibso.chicory.wasm.Parser;
import com.dylibso.chicory.wasm.WasmModule;
import com.dylibso.chicory.wasm.types.ValueType;

import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.List;
import java.util.concurrent.*;

/**
 * High-performance WASM Smart Contract Engine using Chicory (Pure Java).
 * Provides a secure sandbox with host-function access to the blockchain state.
 *
 * <p>Gas accounting: every host-function call charges a fixed cost. When total
 * {@code gasConsumed >= gasLimit}, a {@link RevertException} is thrown so that
 * the calling pipeline treats the execution as reverted (not failed) and issues
 * a {@code STATUS_REVERTED} receipt without committing state changes.
 */
public class WasmContractEngine {
    /** [HC-061] bounded LRU: every distinct binary used to stay parsed in memory forever */
    static final int MODULE_CACHE_MAX = 64;
    private static final Map<String, WasmModule> MODULE_CACHE = Collections.synchronizedMap(
            new LinkedHashMap<>(16, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, WasmModule> e) {
                    return size() > MODULE_CACHE_MAX;
                }
            });
    /**
     * [HC-059] Deterministic metering. Gas used to be charged only inside host calls, so a pure
     * loop or memory.grow cost nothing and was stopped only by a 5 s wall-clock timeout - which
     * fires on a slow node (Pi) and not on a fast one, splitting consensus. Now every instruction
     * costs gas, memory growth and bulk memory ops cost per page/byte, and gas is capped so a
     * metered run always ends far below the timeout, which remains only a liveness backstop.
     */
    static final long GAS_PER_INSTRUCTION = 1;
    static final long GAS_PER_MEMORY_PAGE = 1_000;   // per 64 KiB page grown
    static final long BULK_BYTES_PER_GAS = 64;       // memory.copy / memory.fill
    static final long MAX_WASM_GAS = 10_000_000;
    private static final long TIMEOUT_SECONDS = 30;


    private final byte[] wasmBinary;
    private final Interpreter.BlockchainContext context;
    private final long gasLimit;

    /** Total gas units consumed during this execution. */
    private long gasConsumed = 0;

    public WasmContractEngine(byte[] wasmBinary, long gasLimit, Interpreter.BlockchainContext context) {
        this.wasmBinary = wasmBinary;
        this.gasLimit   = Math.min(gasLimit, MAX_WASM_GAS);
        this.context    = context;
    }

    /**
     * Executes the named WASM export function with the provided arguments.
     *
     * @param functionName name of the WASM export to invoke
     * @param args         arguments passed to the export
     * @throws RevertException if gas is exhausted during execution
     * @throws Exception       on any other execution failure
     */
    public void execute(String functionName, List<Long> args) throws Exception {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<Void> future = executor.submit(() -> {
                // Define Host Functions (Imports for the WASM module)
                List<ImportFunction> hostFunctions = new ArrayList<>();

                // hb_state_read(key) -> value
                hostFunctions.add(new HostFunction(
                    "env",
                    "hb_state_read",
                    List.of(ValueType.I64),
                    List.of(ValueType.I64),
                    (Instance instance, long... params) -> {
                        deductGas(50);
                        long key = params[0];
                        long val = context.state.getAccountStorage(context.contractAddress).getOrDefault(key, 0L);
                        return new long[]{val};
                    }
                ));

                // hb_state_write(key, value)
                hostFunctions.add(new HostFunction(
                    "env",
                    "hb_state_write",
                    List.of(ValueType.I64, ValueType.I64),
                    List.of(),
                    (Instance instance, long... params) -> {
                        deductGas(100);
                        long key = params[0];
                        long val = params[1];
                        context.state.getAccountStorage(context.contractAddress).put(key, val);
                        return null;
                    }
                ));

                // hb_read_sensor(id) -> value
                hostFunctions.add(new HostFunction(
                    "env",
                    "hb_read_sensor",
                    List.of(ValueType.I64),
                    List.of(ValueType.I64),
                    (Instance instance, long... params) -> {
                        deductGas(150);
                        long sensorId = params[0];
                        // [HC-058] latest device-authenticated reading in chain state
                        return new long[]{context.state.getLatestReading(sensorId)};
                    }
                ));

                // hb_get_gas() -> gasRemaining
                hostFunctions.add(new HostFunction(
                    "env",
                    "hb_get_gas",
                    List.of(),
                    List.of(ValueType.I64),
                    (Instance instance, long... params) -> {
                        return new long[]{gasLimit - gasConsumed};
                    }
                ));

                // Load Wasm Module (with caching)
                String moduleKey = HexUtils.encode(Crypto.hash(wasmBinary));
                WasmModule module = MODULE_CACHE.get(moduleKey);
                if (module == null) {
                    module = Parser.parse(wasmBinary);
                    MODULE_CACHE.put(moduleKey, module);
                }


                // Setup Imports
                ImportValues imports = ImportValues.builder()
                    .withFunctions(hostFunctions)
                    .build();

                // Instantiate
                Instance instance = Instance.builder(module)
                        .withImportValues(imports)
                        .withUnsafeExecutionListener(this::meter)
                        .build();

                // Execute function - Chicory 1.0.0 uses long[] for arguments
                long[] longArgs = args.stream().mapToLong(l -> l).toArray();
                instance.export(functionName).apply(longArgs);
                return null;
            });

            try {
                future.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            } catch (ExecutionException e) {
                Throwable cause = e.getCause();
                // FIX 5: Propagate RevertException specifically so state is not committed
                if (cause instanceof RevertException) throw (RevertException) cause;
                if (cause instanceof Exception) throw (Exception) cause;
                throw new Exception(cause);
            }
        } catch (TimeoutException e) {
            this.gasConsumed = gasLimit; // Mark as fully consumed
            throw new RevertException("WASM execution timed out (gas limit equivalent exhausted)");
        } finally {
            executor.shutdownNow();
        }
    }

    /**
     * Returns the total gas units consumed during execution.
     * Call this after {@link #execute} to report actual usage.
     *
     * @return gas units consumed
     */
    public long getGasConsumed() {
        return gasConsumed;
    }

    /**
     * Returns remaining gas (gasLimit - gasConsumed).
     *
     * @return remaining gas units
     */
    public long getGasRemaining() {
        return gasLimit - gasConsumed;
    }

    /**
     * Deducts {@code amount} gas units.
     *
     * @param amount units to deduct
     * @throws RevertException if total consumed reaches or exceeds the gas limit
     */
    /** [HC-059] charged before each instruction executes (operands are still on the stack). */
    private void meter(Instruction ins, MStack stack) {
        long cost = GAS_PER_INSTRUCTION;
        OpCode op = ins.opcode();
        if (op == OpCode.MEMORY_GROW) {
            cost += (stack.peek() & 0xFFFFFFFFL) * GAS_PER_MEMORY_PAGE;
        } else if (op == OpCode.MEMORY_COPY || op == OpCode.MEMORY_FILL) {
            cost += (stack.peek() & 0xFFFFFFFFL) / BULK_BYTES_PER_GAS;
        }
        deductGas(cost);
    }

    private void deductGas(long amount) {
        gasConsumed = (amount >= gasLimit - gasConsumed) ? gasLimit : gasConsumed + amount;
        // FIX 5: Throw RevertException (not RuntimeException) to trigger STATUS_REVERTED receipt
        if (gasConsumed >= gasLimit) {
            throw new RevertException("Out of Gas (WASM): consumed=" + gasConsumed + " limit=" + gasLimit);
        }
    }
}
