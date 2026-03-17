package com.hybrid.blockchain;

/**
 * Exception thrown by the {@code REVERT} opcode in {@link Interpreter}.
 * When caught in {@code Blockchain.applyTransactionToState()}, all state changes
 * made by this contract call are discarded and the transaction receipt is set to REVERTED.
 */
public class RevertException extends Exception {

    private byte[] data;

    public RevertException(String message) {
        super(message);
    }

    public RevertException(byte[] data) {
        super("Contract reverted: " + HexUtils.encode(data));
        this.data = data;
    }

    public byte[] getData() {
        return data;
    }
}
