package com.hybrid.blockchain;

public class Validator {
    private final String id;
    private final byte[] publicKey;

    public Validator(String id, byte[] publicKey) {
        this.id = id;
        this.publicKey = publicKey;
    }

    public String getId() {
        return id;
    }

    public byte[] getPublicKey() {
        return publicKey;
    }
}
