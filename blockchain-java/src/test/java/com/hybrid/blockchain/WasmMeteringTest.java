package com.hybrid.blockchain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** [HC-059] WASM execution is bounded by deterministic gas, not by the wall clock. */
class WasmMeteringTest {

    // (module (func (export "main") (loop br 0)))
    private static final String INFINITE_LOOP =
            "0061736d01000000" + "010401600000" + "03020100" + "070801046d61696e0000"
            + "0a09010700" + "03400c000b" + "0b";

    // (module (memory 1) (func (export "main") i32.const 1000 memory.grow drop))
    private static final String GROW_1000_PAGES =
            "0061736d01000000" + "010401600000" + "03020100" + "0503010001" + "070801046d61696e0000"
            + "0a0a010800" + "41e807" + "4000" + "1a" + "0b";

    private static Interpreter.BlockchainContext ctx() {
        Interpreter.BlockchainContext c = new Interpreter.BlockchainContext();
        c.state = new AccountState();
        c.contractAddress = "wasm-meter";
        c.state.ensure(c.contractAddress);
        return c;
    }

    @Test
    @DisplayName("a pure infinite loop runs out of gas quickly instead of hitting the timeout")
    void infiniteLoopIsMetered() throws Exception {
        WasmContractEngine engine = new WasmContractEngine(HexUtils.decode(INFINITE_LOOP), 100_000, ctx());
        long t0 = System.nanoTime();
        assertThatThrownBy(() -> engine.execute("main", List.of()))
                .isInstanceOf(RevertException.class).hasMessageContaining("Out of Gas");
        assertThat((System.nanoTime() - t0) / 1_000_000).as("ms").isLessThan(4_000);
        assertThat(engine.getGasConsumed()).isEqualTo(100_000);
    }

    @Test
    @DisplayName("memory.grow is charged per page")
    void memoryGrowIsCharged() throws Exception {
        WasmContractEngine engine = new WasmContractEngine(HexUtils.decode(GROW_1000_PAGES), 100_000, ctx());
        assertThatThrownBy(() -> engine.execute("main", List.of()))
                .isInstanceOf(RevertException.class).hasMessageContaining("Out of Gas");
    }

    @Test
    @DisplayName("gas is capped at MAX_WASM_GAS so a huge fee cannot buy unbounded run time")
    void gasIsCapped() throws Exception {
        WasmContractEngine engine = new WasmContractEngine(HexUtils.decode(INFINITE_LOOP), Long.MAX_VALUE, ctx());
        assertThat(engine.getGasRemaining()).isEqualTo(WasmContractEngine.MAX_WASM_GAS);
    }
}
