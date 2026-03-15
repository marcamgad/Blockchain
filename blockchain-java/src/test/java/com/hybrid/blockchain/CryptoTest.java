package com.hybrid.blockchain;

import org.bouncycastle.asn1.x9.X9ECParameters;
import org.bouncycastle.crypto.ec.CustomNamedCurves;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

@Tag("unit")
class CryptoTest {

    private static final SecureRandom RANDOM = new SecureRandom();

    @Test
    @DisplayName("Crypto.sign + Crypto.verify returns true for matching message and key")
    void signThenVerify() {
        BigInteger priv = new BigInteger("12345678901234567890");
        byte[] pub = Crypto.derivePublicKey(priv);
        byte[] msg = Crypto.hash("hello".getBytes());
        byte[] sig = Crypto.sign(msg, priv);

        assertTrue(Crypto.verify(msg, sig, pub), "Signature must verify for original message and matching public key");
    }

    @Test
    @DisplayName("Crypto.verify returns false when using a different public key")
    void verifyWithDifferentPublicKeyFails() {
        BigInteger priv = new BigInteger("12345678901234567890");
        BigInteger otherPriv = new BigInteger("99999999999999999999");
        byte[] msg = Crypto.hash("hello".getBytes());
        byte[] sig = Crypto.sign(msg, priv);

        assertFalse(Crypto.verify(msg, sig, Crypto.derivePublicKey(otherPriv)), "Signature must fail verification with a non-matching public key");
    }

    @Test
    @DisplayName("Crypto.verify returns false for tampered message")
    void verifyWithTamperedMessageFails() {
        BigInteger priv = new BigInteger("12345678901234567890");
        byte[] pub = Crypto.derivePublicKey(priv);
        byte[] msg = Crypto.hash("hello".getBytes());
        byte[] sig = Crypto.sign(msg, priv);

        byte[] tampered = Arrays.copyOf(msg, msg.length);
        tampered[0] ^= 0x01;

        assertFalse(Crypto.verify(tampered, sig, pub), "Signature must fail verification after message tampering");
    }

    @Test
    @DisplayName("Crypto.verify returns false for signature tampering at boundary bytes")
    void verifyWithTamperedSignatureFailsForBoundaryIndices() {
        BigInteger priv = new BigInteger("12345678901234567890");
        byte[] pub = Crypto.derivePublicKey(priv);
        byte[] msg = Crypto.hash("hello".getBytes());
        byte[] sig = Crypto.sign(msg, priv);

        int[] indices = {0, 31, 32, 63};
        for (int index : indices) {
            byte[] tampered = Arrays.copyOf(sig, sig.length);
            tampered[index] ^= 0x01;
            assertFalse(Crypto.verify(msg, tampered, pub), "Tampering signature byte at index " + index + " must invalidate verification");
        }
    }

    @Test
    @DisplayName("Crypto.sign always emits low-S signatures for 1000 random messages")
    void lowSNormalizationAlwaysHolds() {
        X9ECParameters curveParams = CustomNamedCurves.getByName(Config.EC_CURVE);
        BigInteger halfN = curveParams.getN().shiftRight(1);
        BigInteger priv = new BigInteger("12345678901234567890");

        for (int i = 0; i < 1000; i++) {
            byte[] msg = new byte[32];
            RANDOM.nextBytes(msg);
            byte[] sig = Crypto.sign(msg, priv);
            BigInteger s = new BigInteger(1, Arrays.copyOfRange(sig, 32, 64));
            assertTrue(s.compareTo(halfN) <= 0, "Signature S component must always be low-S to prevent malleability");
        }
    }

    @Test
    @DisplayName("deriveAddress is deterministic for identical public keys")
    void deriveAddressDeterministic() {
        byte[] pub = Crypto.derivePublicKey(new BigInteger("12345678901234567890"));
        String a1 = Crypto.deriveAddress(pub);
        String a2 = Crypto.deriveAddress(pub);

        assertEquals(a1, a2, "Address derivation must be deterministic for the same public key");
    }

    @Test
    @DisplayName("deriveAddress outputs hb-prefixed addresses")
    void deriveAddressPrefix() {
        byte[] pub = Crypto.derivePublicKey(new BigInteger("12345678901234567890"));
        String address = Crypto.deriveAddress(pub);

        assertTrue(address.startsWith("hb"), "Derived addresses must start with the expected hb prefix");
    }

    @Test
    @DisplayName("deriveAddress has no collisions across 10,000 random keys")
    void deriveAddressNoCollisionsInTenThousand() {
        Set<String> addresses = new HashSet<>();
        for (int i = 0; i < 10_000; i++) {
            BigInteger priv = new BigInteger(250, RANDOM).add(BigInteger.ONE);
            String address = Crypto.deriveAddress(Crypto.derivePublicKey(priv));
            assertTrue(addresses.add(address), "Address collision must not occur in this 10,000-key sample");
        }
    }

    @Test
    @DisplayName("hash is deterministic and always 32 bytes")
    void hashDeterministicAndSize() {
        byte[] data = "deterministic-input".getBytes();
        byte[] h1 = Crypto.hash(data);
        byte[] h2 = Crypto.hash(data);

        assertArrayEquals(h1, h2, "Hash output must be deterministic for identical input");
        assertEquals(32, h1.length, "SHA-256 hash output must be exactly 32 bytes");
    }

    @Test
    @DisplayName("hash collision check across 100,000 unique inputs")
    void hashNoCollisionsInOneHundredThousand() {
        Set<String> digests = new HashSet<>();
        for (int i = 0; i < 100_000; i++) {
            byte[] hash = Crypto.hash(("input-" + i).getBytes());
            assertTrue(digests.add(HexUtils.encode(hash)), "Hash collision must not occur in this 100,000-input sample");
        }
    }

    @Test
    @DisplayName("derivePublicKey -> deriveAddress -> sign -> verify full pipeline succeeds")
    void fullKeyAddressSignaturePipeline() {
        BigInteger priv = new BigInteger("98765432109876543210");
        byte[] pub = Crypto.derivePublicKey(priv);
        String addr = Crypto.deriveAddress(pub);

        byte[] msg = Crypto.hash(("msg-for-" + addr).getBytes());
        byte[] sig = Crypto.sign(msg, priv);

        assertNotNull(addr, "Derived address must be non-null in full pipeline");
        assertTrue(Crypto.verify(msg, sig, pub), "Signature verification must succeed in the end-to-end key pipeline");
    }

    @Test
    @DisplayName("Signature length is always 64 bytes for empty, 1-byte, and 1MB messages")
    void signatureLengthAlways64Bytes() {
        BigInteger priv = new BigInteger("12345678901234567890");

        byte[][] cases = {
                new byte[0],
                new byte[]{0x01},
                new byte[1024 * 1024]
        };

        for (byte[] msg : cases) {
            byte[] sig = Crypto.sign(msg, priv);
            assertEquals(64, sig.length, "ECDSA signature must be encoded as fixed 64-byte (R||S) format");
        }
    }
}
