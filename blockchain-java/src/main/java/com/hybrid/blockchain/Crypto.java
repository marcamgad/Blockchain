package com.hybrid.blockchain;

import org.bouncycastle.asn1.x9.X9ECParameters;
import org.bouncycastle.crypto.digests.SHA256Digest;
import org.bouncycastle.crypto.ec.CustomNamedCurves;
import org.bouncycastle.crypto.params.ECDomainParameters;
import org.bouncycastle.crypto.params.ECPrivateKeyParameters;
import org.bouncycastle.crypto.params.ECPublicKeyParameters;
import org.bouncycastle.crypto.signers.ECDSASigner;
import org.bouncycastle.crypto.signers.HMacDSAKCalculator;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.bouncycastle.math.ec.ECPoint;
import org.bouncycastle.util.encoders.Hex;

import java.math.BigInteger;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.Security;
import java.util.Arrays;

public final class Crypto {

    static {
        Security.addProvider(new BouncyCastleProvider());
    }

    private static final X9ECParameters CURVE_PARAMS = CustomNamedCurves.getByName(Config.EC_CURVE);
    private static final ECDomainParameters CURVE = new ECDomainParameters(
            CURVE_PARAMS.getCurve(), CURVE_PARAMS.getG(), CURVE_PARAMS.getN(), CURVE_PARAMS.getH());

    private Crypto() {
    }

    public static byte[] hash(byte[] data) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return digest.digest(data);
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 not available", e);
        }
    }

    /** Length of the only input {@link #sign} and {@link #verify} accept: a SHA-256 digest. */
    public static final int DIGEST_LENGTH = 32;

    /**
     * Domain-separated digest: SHA-256(domain || 0x00 || len(p1) || p1 || len(p2) || p2 ...).
     *
     * <p>[HC-040/HC-042] Every signed message in the system must be produced by this method (or
     * by an equivalent, already domain-prefixed hash such as {@code Transaction.signingPayload}).
     * The domain tag stops a signature made for one purpose from verifying for another, and the
     * length prefixes stop two different field lists from encoding to the same bytes.
     */
    public static byte[] hashWithDomain(String domain, byte[]... parts) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(domain.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            digest.update((byte) 0);
            for (byte[] p : parts) {
                byte[] part = p == null ? new byte[0] : p;
                digest.update(java.nio.ByteBuffer.allocate(4).putInt(part.length).array());
                digest.update(part);
            }
            return digest.digest();
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 not available", e);
        }
    }

    /** UTF-8 convenience overload of {@link #hashWithDomain(String, byte[]...)}. */
    public static byte[] hashWithDomain(String domain, String... parts) {
        byte[][] bytes = new byte[parts.length][];
        for (int i = 0; i < parts.length; i++) {
            bytes[i] = parts[i] == null ? new byte[0] : parts[i].getBytes(java.nio.charset.StandardCharsets.UTF_8);
        }
        return hashWithDomain(domain, bytes);
    }

    /**
     * Signs a 32-byte digest.
     *
     * <p>[HC-040] ECDSA consumes only the leftmost 256 bits of its input, so passing a longer
     * message silently signed just its first 32 bytes. The input must now be exactly a digest;
     * anything else is a programming error and is rejected.
     */
    public static byte[] sign(byte[] message, BigInteger privateKey) {
        if (message == null || message.length != DIGEST_LENGTH) {
            throw new IllegalArgumentException("Crypto.sign expects a " + DIGEST_LENGTH
                    + "-byte digest, got " + (message == null ? "null" : message.length + " bytes")
                    + "; hash the message first (Crypto.hashWithDomain)");
        }
        ECDSASigner signer = new ECDSASigner(new HMacDSAKCalculator(new SHA256Digest()));
        ECPrivateKeyParameters privKey = new ECPrivateKeyParameters(privateKey, CURVE);
        signer.init(true, privKey);
        BigInteger[] components = signer.generateSignature(message);

        // Canonical encoding (Low-S normalization)
        BigInteger r = components[0];
        BigInteger s = components[1];

        // Normalize S to low-S for malleability protection
        BigInteger halfN = CURVE.getN().shiftRight(1);
        if (s.compareTo(halfN) > 0) {
            s = CURVE.getN().subtract(s);
        }

        // Return as 64-byte R|S concatenated for simplicity (or ASN.1 if preferred)
        byte[] rBytes = r.toByteArray();
        byte[] sBytes = s.toByteArray(); // s is already normalized here
        byte[] sig = new byte[64];
        copyToLength(rBytes, sig, 0, 32);
        copyToLength(sBytes, sig, 32, 32);
        return sig;
    }

    public static boolean verify(byte[] message, byte[] signature, byte[] pubKey) {
        if (signature == null || pubKey == null || signature.length != 64)
            return false;
        // [HC-040] Only a 32-byte digest is a well-defined ECDSA input; a longer message would be
        // truncated to its first 32 bytes and verify for every message sharing that prefix.
        if (message == null || message.length != DIGEST_LENGTH)
            return false;

        try {
            BigInteger r = new BigInteger(1, Arrays.copyOfRange(signature, 0, 32));
            BigInteger s = new BigInteger(1, Arrays.copyOfRange(signature, 32, 64));

            // Reject non-canonical signatures rather than silently repairing them.
            // A high-S encoding is a second valid form of the same signature; accepting
            // it would leave the signature malleable (a distinct byte string that still
            // verifies for the same message/key). sign() only ever emits low-S.
            BigInteger n = CURVE.getN();
            BigInteger halfN = n.shiftRight(1);
            if (r.signum() <= 0 || s.signum() <= 0 || r.compareTo(n) >= 0 || s.compareTo(halfN) > 0) {
                return false;
            }

            // decodePoint throws on off-curve / malformed keys; treat that as a failed
            // verification (network-supplied bytes must not be able to crash the caller).
            ECPoint point = CURVE.getCurve().decodePoint(pubKey);
            if (!point.isValid()) {
                return false;
            }
            ECPublicKeyParameters publicKey = new ECPublicKeyParameters(point, CURVE);
            ECDSASigner signer = new ECDSASigner();
            signer.init(false, publicKey);

            return signer.verifySignature(message, r, s);
        } catch (RuntimeException e) {
            return false;
        }
    }

    public static String deriveAddress(byte[] pubKey) {
        byte[] h = hash(pubKey);
        return "hb" + Hex.toHexString(Arrays.copyOfRange(h, 0, 20));
    }

    /** [HC-079] Prefix of addresses bound to an ECDSA key AND an ML-DSA key ("q": quantum). */
    public static final String HYBRID_ADDRESS_PREFIX = "hq";

    /**
     * [HC-079] Address committing to both keys, so neither key alone can spend from it.
     * Hex never contains 'q', so "hq" and "hb" addresses can never be confused.
     * See docs/decisions/HC-079-hybrid-address-binding.md.
     */
    public static String deriveHybridAddress(byte[] ecPubKey, byte[] mldsaPubKey) {
        if (ecPubKey == null || mldsaPubKey == null) throw new IllegalArgumentException("both public keys are required");
        byte[] h = hashWithDomain("HYBRIDCHAIN-ADDR-HYBRID-v1", ecPubKey, mldsaPubKey);
        return HYBRID_ADDRESS_PREFIX + Hex.toHexString(Arrays.copyOfRange(h, 0, 20));
    }

    /** [HC-079] */
    public static boolean isHybridAddress(String address) {
        return address != null && address.startsWith(HYBRID_ADDRESS_PREFIX);
    }

    private static void copyToLength(byte[] src, byte[] dest, int destOffset, int length) {
        int srcLen = Math.min(src.length, length);
        int srcOffset = Math.max(0, src.length - length);
        System.arraycopy(src, srcOffset, dest, destOffset + (length - srcLen), srcLen);
    }

    public static String bytesToHex(byte[] data) {
        return Hex.toHexString(data);
    }

    public static byte[] derivePublicKey(BigInteger privateKey) {
        return CURVE.getG().multiply(privateKey).getEncoded(true);
    }

    public static long bytesToLong(byte[] bytes) {
        java.nio.ByteBuffer buffer = java.nio.ByteBuffer.allocate(Long.BYTES);
        buffer.put(bytes);
        buffer.flip();
        return buffer.getLong();
    }

    public static byte[] longToBytes(long x) {
        java.nio.ByteBuffer buffer = java.nio.ByteBuffer.allocate(Long.BYTES);
        buffer.putLong(x);
        return buffer.array();
    }

    public static byte[] hexToBytes(String hex) {
        return Hex.decode(hex);
    }
}
