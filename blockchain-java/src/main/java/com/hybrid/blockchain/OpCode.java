package com.hybrid.blockchain;

/**
 * Defines the instruction set for the IoT-hardened VM.
 * Each OpCode is 1 byte.
 */
public enum OpCode {
    // Stack Operations (0x00 - 0x0F)
    STOP(0x00, 0),
    PUSH(0x01, 1), // Followed by 8 bytes
    POP(0x02, 1),
    DUP(0x03, 1),
    SWAP(0x04, 2),

    // Arithmetic (0x10 - 0x1F)
    ADD(0x10, 3),
    SUB(0x11, 3),
    MUL(0x12, 5),
    DIV(0x13, 5),
    MOD(0x14, 5),

    // Logic/Control (0x20 - 0x2F)
    JUMP(0x20, 8),
    JUMPI(0x21, 10), // Jump if top of stack != 0
    EQ(0x22, 3),
    LT(0x23, 3),
    GT(0x24, 3),

    // State & Storage (0x30 - 0x3F)
    SLOAD(0x30, 200), // Load from persistent storage
    SSTORE(0x31, 5000), // Store to persistent storage (expensive!)
    MLOAD(0x32, 3), // Load from memory (heap)
    MSTORE(0x33, 3), // Store to memory (heap)

    // Blockchain Context (0x40 - 0x4F)
    BALANCE(0x40, 20),
    CALLER(0x41, 2),
    VALUE(0x42, 2),
    TIMESTAMP(0x43, 2),
    NUMBER(0x44, 2), // Block height

    // IoT Capabilities (0x50 - 0x5F)
    SYSCALL(0x50, 100);

    private final int code;
    private final int gasCost;

    OpCode(int code, int gasCost) {
        this.code = code;
        this.gasCost = gasCost;
    }

    public int getCode() {
        return code;
    }

    public int getGasCost() {
        return gasCost;
    }

    public byte getByte() {
        return (byte) code;
    }

    public static OpCode fromByte(byte b) {
        for (OpCode op : values()) {
            if (op.code == (b & 0xFF))
                return op;
        }
        return null;
    }
}
