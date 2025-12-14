package com.hybrid.blockchain;

import java.security.PublicKey;

public class Validator {
    private final String id;
    private final PublicKey publicKey;

    public Validator(String id, PublicKey publicKey) {
        this.id = id;
        this.publicKey = publicKey;
    }

    public String getId() {
        return id;
    }

    public PublicKey getPublicKey() {
        return publicKey;
    }
}
