package com.hybrid.blockchain.audit;

import com.hybrid.blockchain.Config;
import com.hybrid.blockchain.TestKeyPair;
import com.hybrid.blockchain.Transaction;
import com.hybrid.blockchain.WasmContractEngine;
import com.hybrid.blockchain.WasmDeterminism;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * [HC-098] Found by the independent audit (docs/audit/independent-2026-10-24, C-5). WebAssembly
 * floating-point operations may return NaNs with non-deterministic payloads, and the bits real
 * hardware produces differ between x86 and ARM. A contract that reinterprets such a NaN as an
 * integer and stores it could give different state on different validators. Float and SIMD types
 * and instructions are now refused, at deployment and at execution.
 */
class WasmDeterminismTest {

    private static byte[] hex(String s) {
        s = s.replace(" ", "");
        byte[] b = new byte[s.length() / 2];
        for (int i = 0; i < b.length; i++) b[i] = (byte) Integer.parseInt(s.substring(2 * i, 2 * i + 2), 16);
        return b;
    }

    /** (func (export "main") (result i32) f32.const 0 f32.const 0 f32.div i32.reinterpret_f32): the NaN's bits */
    static final byte[] FLOAT_MODULE = hex("0061736d 01000000 01050160 00017f 03020100 07080104 6d61696e 0000"
            + " 0a10010e 00 4300000000 4300000000 95 bc 0b");
    /** (func (export "main") (result i32) i32.const 7) */
    static final byte[] INT_MODULE = hex("0061736d 01000000 01050160 00017f 03020100 07080104 6d61696e 0000 0a060104 00 4107 0b");

    @Test
    @DisplayName("a module with float instructions or types is reported; an integer-only module is clean")
    void detection() {
        List<String> found = WasmDeterminism.nonDeterministicFeatures(FLOAT_MODULE);
        assertThat(found).isNotEmpty();
        assertThat(String.join(" ", found)).contains("F32");
        assertThat(WasmDeterminism.nonDeterministicFeatures(INT_MODULE)).isEmpty();
    }

    @Test
    @DisplayName("deploying a float module is refused, even when the contract audit is bypassed")
    void deployRefused() throws Exception {
        boolean previous = Config.BYPASS_CONTRACT_AUDIT;
        Config.BYPASS_CONTRACT_AUDIT = true;   // the float ban must not depend on the (test-only) audit switch
        try (LedgerPoCFixture f = new LedgerPoCFixture()) {
            TestKeyPair dev = new TestKeyPair(9801);
            f.fund(dev.getAddress());
            long fee = Math.max(10, f.chain.getCurrentBaseFee());
            Transaction deploy = new Transaction.Builder().type(Transaction.Type.CONTRACT).from(dev.getAddress())
                    .fee(fee).nonce(1).data(FLOAT_MODULE).sign(dev.getPrivateKey(), dev.getPublicKey());
            assertThatThrownBy(() -> f.chain.validateTransaction(deploy)).hasMessageContaining("HC-098");

            Transaction ok = new Transaction.Builder().type(Transaction.Type.CONTRACT).from(dev.getAddress())
                    .fee(fee).nonce(1).data(INT_MODULE).sign(dev.getPrivateKey(), dev.getPublicKey());
            f.chain.validateTransaction(ok);   // an integer-only module is still accepted
        } finally {
            Config.BYPASS_CONTRACT_AUDIT = previous;
        }
    }

    @Test
    @DisplayName("executing a float module is refused (defence in depth)")
    void executionRefused() {
        WasmContractEngine engine = new WasmContractEngine(FLOAT_MODULE, 1_000_000, null);
        assertThatThrownBy(() -> engine.execute("main", List.of())).hasMessageContaining("HC-098");
    }
}
