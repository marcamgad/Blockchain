package com.hybrid.blockchain;

import org.bouncycastle.jce.provider.BouncyCastleProvider;
import java.math.BigInteger;
import java.security.*;
import java.security.spec.ECGenParameterSpec;
import java.util.Random;

/**
 * Utility for generating deterministic secp256k1 key pairs for testing.
 * Always produces the same key for the same seed.
 */
public class TestKeyPair {
    static {
        Security.addProvider(new BouncyCastleProvider());
    }

    private final BigInteger privateKey;
    private final byte[] publicKey;
    private final String address;

    public TestKeyPair(int seed) {
        // Deterministic private key generation
        Random random = new Random(seed);
        byte[] privBytes = new byte[32];
        random.nextBytes(privBytes);
        this.privateKey = new BigInteger(1, privBytes);
        this.publicKey = Crypto.derivePublicKey(this.privateKey);
        this.address = Crypto.deriveAddress(this.publicKey);
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

    /**
     * Create a random but verifiable key pair.
     */
    public static TestKeyPair random() {
        return new TestKeyPair(new SecureRandom().nextInt());
    }
}
