package com.hybrid.blockchain;

import com.dylibso.chicory.wasm.Parser;
import com.dylibso.chicory.wasm.WasmModule;
import com.dylibso.chicory.wasm.types.AnnotatedInstruction;
import com.dylibso.chicory.wasm.types.FunctionBody;
import com.dylibso.chicory.wasm.types.FunctionType;
import com.dylibso.chicory.wasm.types.Global;
import com.dylibso.chicory.wasm.types.ValueType;

import java.util.ArrayList;
import java.util.List;

/**
 * [HC-098] Refuses WebAssembly features whose results are not bit-for-bit deterministic across
 * validators.
 *
 * <p>WebAssembly floating-point operations may return NaNs with non-deterministic payloads, and the
 * NaN bits real hardware produces differ between x86 and ARM (Chicory runs on JVM floating point).
 * A contract that reinterprets such a NaN as an integer and stores it would give different state on
 * different validators. So f32, f64 and v128 (SIMD, which has float lanes) are refused anywhere in a
 * module: function signatures, locals, globals and instructions. Contracts use integer or
 * fixed-point arithmetic, as other deterministic WASM chains require.</p>
 */
public final class WasmDeterminism {

    private WasmDeterminism() {
    }

    /** The non-deterministic features found (empty = acceptable). Unparseable modules are reported too. */
    public static List<String> nonDeterministicFeatures(byte[] wasm) {
        List<String> found = new ArrayList<>();
        WasmModule module;
        try {
            module = Parser.parse(wasm);
        } catch (RuntimeException e) {
            found.add("unparseable module: " + e.getMessage());
            return found;
        }
        FunctionType[] types = module.typeSection().types();
        for (int i = 0; i < types.length; i++) {
            for (ValueType t : types[i].params()) if (banned(t)) found.add("type " + i + " param " + t);
            for (ValueType t : types[i].returns()) if (banned(t)) found.add("type " + i + " result " + t);
        }
        Global[] globals = module.globalSection().globals();
        for (int i = 0; i < globals.length; i++) {
            if (banned(globals[i].valueType())) found.add("global " + i + " " + globals[i].valueType());
        }
        FunctionBody[] bodies = module.codeSection().functionBodies();
        for (int i = 0; i < bodies.length; i++) {
            for (ValueType t : bodies[i].localTypes()) if (banned(t)) found.add("function " + i + " local " + t);
            for (AnnotatedInstruction ins : bodies[i].instructions()) {
                String op = ins.opcode().name();
                if (op.contains("F32") || op.contains("F64") || op.startsWith("V128")) {
                    found.add("function " + i + " instruction " + op);
                }
            }
        }
        return found;
    }

    private static boolean banned(ValueType t) {
        return t == ValueType.F32 || t == ValueType.F64 || t == ValueType.V128;
    }
}
