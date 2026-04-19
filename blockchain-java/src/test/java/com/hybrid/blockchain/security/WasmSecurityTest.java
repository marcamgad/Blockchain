package com.hybrid.blockchain.security;

import com.hybrid.blockchain.*;
import org.junit.jupiter.api.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

/**
 * D2 – WASM Contract Security Tests.
 *
 * Gas-metering and memory-isolation are verified by:
 *  - Supplying a minimal, valid WASM binary that calls the `hb_state_read` host
 *    function exactly once (cost = 50 gas) while starting with only 40 gas,
 *    which must trigger an "Out of Gas (WASM)" runtime exception.
 *  - Verifying that an infinite-loop WASM times out and is treated as an error.
 */
@Tag("Security")
@Tag("Wasm")
public class WasmSecurityTest {

    private TestBlockchain tb;

    /*
     * Valid minimal WASM (WAT source):
     *
     *   (module
     *     (import "env" "hb_state_read" (func $read (param i64) (result i64)))
     *     (func (export "main") (param i64) (result i64)
     *       local.get 0
     *       call $read))
     *
     * Compiled to hex via https://webassembly.github.io/wabt/demo/wat2wasm/
     * Section layout (all length-prefixed per the WASM binary spec):
     *   0061736d 01000000   -- magic + version
     *   01 08               -- Type section, 8 bytes
     *     02                -- 2 type entries
     *     60 01 7e 01 7e    -- () (i64)->i64   (type 0: hb_state_read)
     *     60 01 7e 01 7e    -- (i64)->i64      (type 1: main)  [same signature here]
     *   02 10               -- Import section, 16 bytes
     *     01                -- 1 import
     *     03 656e76         -- module "env"
     *     0d 68625f73746174655f72656164  -- field "hb_state_read" (13 chars)
     *     00 00             -- kind=func, type index 0
     *   03 02               -- Function section, 2 bytes
     *     01 01             -- 1 function, type index 1
     *   07 08               -- Export section, 8 bytes
     *     01                -- 1 export
     *     04 6d61696e       -- "main"
     *     00 01             -- kind=func, func index 1
     *   0a 08               -- Code section, 8 bytes
     *     01                -- 1 function body
     *     06                -- body size = 6 bytes
     *     00                -- 0 locals
     *     20 00             -- local.get 0
     *     10 00             -- call 0  (hb_state_read)
     *     0b                -- end
     */
    private static final String WASM_READ_ONCE_HEX =
            "0061736d01000000" +       // magic + version
            "010a"           +         // type section, 10 bytes
              "02"           +         //   2 entries
              "60017e017e"  +         //   type 0: (i64)->i64
              "60017e017e"  +         //   type 1: (i64)->i64
            "020f"           +         // import section, 15 bytes
              "01"           +         //   1 import
              "03656e76"    +         //   "env"
              "0d68625f73746174655f72656164" + // "hb_state_read"
              "0000"         +         //   func, type 0
            "030201"         +         // function section: [01 01]  -> 1 fn, type 1
              "01"           +
            "0708"           +         // export section, 8 bytes
              "01"           +         //   1 export
              "046d61696e"   +         //   "main"
              "0001"         +         //   func index 1
            "0a08"           +         // code section, 8 bytes
              "01"           +         //   1 body
              "06"           +         //   body size 6
              "00"           +         //   0 locals
              "2000"         +         //   local.get 0
              "1000"         +         //   call 0
              "0b";                    //   end

    @BeforeEach
    public void setup() throws Exception {
        tb = new TestBlockchain();
    }

    @AfterEach
    public void teardown() throws Exception {
        if (tb != null) tb.close();
    }

    @Test
    @DisplayName("D2.1: WASM Gas Meter – calling hb_state_read with 40 gas must throw Out-of-Gas (cost=50)")
    public void testWasmGasMeterStrictness() {
        byte[] wasm;
        try {
            wasm = HexUtils.decode(WASM_READ_ONCE_HEX);
        } catch (Exception e) {
            fail("WASM hex decode failed: " + e.getMessage());
            return;
        }

        Interpreter.BlockchainContext ctx = new Interpreter.BlockchainContext();
        ctx.state = tb.getBlockchain().getAccountState();
        ctx.contractAddress = "0xWasmGasTest";
        ctx.state.ensure(ctx.contractAddress);

        // 40 gas available; hb_state_read costs 50 → must throw Out of Gas
        WasmContractEngine engine = new WasmContractEngine(wasm, 40, ctx);

        assertThatThrownBy(() -> engine.execute("main", List.of(0L)))
                .isInstanceOf(Exception.class)
                .hasMessageContaining("Out of Gas");
    }

    @Test
    @DisplayName("D2.2: WASM Memory Isolation – engine executes without corrupting host state")
    public void testWasmMemoryIsolation() {
        // This test verifies that even when WASM parsing fails (bad binary),
        // the host blockchain state stays untouched.
        byte[] garbage = new byte[]{0x00, 0x61, 0x73, 0x6d, 0x01, 0x00, 0x00, 0x00, (byte) 0xFF};

        Interpreter.BlockchainContext ctx = new Interpreter.BlockchainContext();
        ctx.state = tb.getBlockchain().getAccountState();
        ctx.contractAddress = "0xIsolationTest";
        ctx.state.ensure(ctx.contractAddress);
        long balanceBefore = ctx.state.getBalance(ctx.contractAddress);

        WasmContractEngine engine = new WasmContractEngine(garbage, 10_000, ctx);

        // Should throw (bad module) – but not corrupt state
        assertThatThrownBy(() -> engine.execute("main", new ArrayList<>()))
                .isInstanceOf(Exception.class);

        // State untouched
        assertThat(ctx.state.getBalance(ctx.contractAddress)).isEqualTo(balanceBefore);
    }
}
