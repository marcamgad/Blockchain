package com.hybrid.blockchain.testutil;

import com.hybrid.blockchain.Crypto;
import java.math.BigInteger;

/**
 * Deterministic key pair generator for testing.
 * Strictly guarantees identical keys for identical seeds.
 */
public class TestKeyPair {
    private final int seed;
    private final BigInteger privateKey;
    private final byte[] publicKey;
    private final String address;

    public TestKeyPair(int seed) {
        this.seed = seed;
        // Generate a deterministic private key based on the seed
        // We use a simple but deterministic approach: SHA-256 of the seed string
        byte[] hash = Crypto.hash(String.valueOf(seed).getBytes());
        this.privateKey = new BigInteger(1, hash);
        this.publicKey = Crypto.derivePublicKey(this.privateKey);
        this.address = Crypto.deriveAddress(this.publicKey);
    }

    public int getSeed() {
        return seed;
    }

    public BigInteger getPrivateKey() {
        return privateKey;
    }

    public byte[] getPublicKey() {
        return publicKey;
    }

    public String getAddress() {
        return address;
    }
}
