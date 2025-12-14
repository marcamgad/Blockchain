package com.hybrid.blockchain;

public class UTXOInput {
    private String txid;
    private int index;

    public UTXOInput() {}

    public UTXOInput(String txid, int index) {
        this.txid = txid;
        this.index = index;
    }

    public String getTxid() {
        return txid;
    }

    public int getIndex() {
        return index;
    }
}
