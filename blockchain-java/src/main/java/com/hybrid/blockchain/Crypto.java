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

    private static final X9ECParameters CURVE_PARAMS = CustomNamedCurves.getByName("secp256k1");
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

    public static byte[] sign(byte[] message, BigInteger privateKey) {
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
        if (signature.length != 64)
            return false;

        ECDSASigner signer = new ECDSASigner();
        ECPoint point = CURVE.getCurve().decodePoint(pubKey);
        ECPublicKeyParameters publicKey = new ECPublicKeyParameters(point, CURVE);
        signer.init(false, publicKey);

        BigInteger r = new BigInteger(1, Arrays.copyOfRange(signature, 0, 32));
        BigInteger s = new BigInteger(1, Arrays.copyOfRange(signature, 32, 64));

        // Normalize S to low-S if needed (for signatures created before normalization)
        BigInteger halfN = CURVE.getN().shiftRight(1);
        if (s.compareTo(halfN) > 0) {
            s = CURVE.getN().subtract(s);
        }

        return signer.verifySignature(message, r, s);
    }

    public static String deriveAddress(byte[] pubKey) {
        byte[] h = hash(pubKey);
        return "hb" + Hex.toHexString(Arrays.copyOfRange(h, 0, 20));
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
