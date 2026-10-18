package com.hybrid.blockchain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** [HC-057] the contract VM is bounded by a stack limit, not only by gas. */
class InterpreterLimitsTest {

    @Test
    @DisplayName("an endless PUSH loop with a huge gas budget stops at the stack limit")
    void pushLoopHitsStackLimit() {
        // loop: PUSH 7 ; PUSH 0 ; JUMP   -> every iteration leaves one more value on the stack
        ByteBuffer code = ByteBuffer.allocate(19);
        code.put((byte) 0x01).putLong(7L);   // PUSH 7
        code.put((byte) 0x01).putLong(0L);   // PUSH 0 (jump target)
        code.put((byte) 0x20);               // JUMP
        Interpreter vm = new Interpreter(code.array(), Long.MAX_VALUE / 2,
                new Interpreter.BlockchainContext(1L, 1, "caller", "contract", 0, new AccountState(),
                        new HardwareManager(), "hash"));
        assertThatThrownBy(vm::execute).hasMessageContaining("Stack overflow");
    }
}
