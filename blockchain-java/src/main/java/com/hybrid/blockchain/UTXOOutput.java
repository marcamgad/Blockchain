package com.hybrid.blockchain;

public class UTXOOutput {
    private String address;
    private long amount;

    public UTXOOutput() {}

    public UTXOOutput(String address, long amount) {
        this.address = address;
        this.amount = amount;
    }

    public String getAddress() {
        return address;
    }

    public long getAmount() {
        return amount;
    }
}
