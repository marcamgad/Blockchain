package com.hybrid.blockchain;

import com.dylibso.chicory.runtime.HostFunction;
import com.dylibso.chicory.runtime.ImportFunction;
import com.dylibso.chicory.runtime.Instance;
import com.dylibso.chicory.runtime.ImportValues;
import com.dylibso.chicory.wasm.Parser;
import com.dylibso.chicory.wasm.WasmModule;
import com.dylibso.chicory.wasm.types.ValueType;

import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * High-performance WASM Smart Contract Engine using Chicory (Pure Java).
 * Provides a secure sandbox with host-function access to the blockchain state.
 * 
 * Optimized for Chicory 1.0.0 API stability and Java type safety.
 */
public class WasmContractEngine {

    private final byte[] wasmBinary;
    private final Interpreter.BlockchainContext context;
    private long gasRemaining;

    public WasmContractEngine(byte[] wasmBinary, long gasLimit, Interpreter.BlockchainContext context) {
        this.wasmBinary = wasmBinary;
        this.gasRemaining = gasLimit;
        this.context = context;
    }

    public void execute(String functionName, List<Long> args) throws Exception {
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
                try {
                    return new long[]{context.hardware.readSensor(sensorId)};
                } catch (Exception e) {
                    throw new RuntimeException("Hardware read failed: " + e.getMessage());
                }
            }
        ));

        // hb_get_gas() -> gasRemaining
        hostFunctions.add(new HostFunction(
            "env", 
            "hb_get_gas", 
            List.of(), 
            List.of(ValueType.I64), 
            (Instance instance, long... params) -> {
                return new long[]{gasRemaining};
            }
        ));

        // Load Wasm Module
        WasmModule module = Parser.parse(wasmBinary);
        
        // Setup Imports
        ImportValues imports = ImportValues.builder()
            .withFunctions(hostFunctions)
            .build();

        // Instantiate
        Instance instance = Instance.builder(module)
                .withImportValues(imports)
                .build();

        // Execute function - Chicory 1.0.0 uses long[] for arguments
        long[] longArgs = args.stream().mapToLong(l -> l).toArray();
        instance.export(functionName).apply(longArgs);
    }

    private void deductGas(int amount) {
        gasRemaining -= amount;
        if (gasRemaining < 0) {
            throw new RuntimeException("Out of Gas (WASM)");
        }
    }

    public long getGasRemaining() {
        return gasRemaining;
    }
}
