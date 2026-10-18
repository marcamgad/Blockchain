package com.hybrid.blockchain.audit;

import com.hybrid.blockchain.Crypto;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/** Phase C regression guards for crypto hypotheses that were tested and DISPROVED (docs/audit, Corrections). */
@Tag("audit-property")
class CryptoGuardTest {

    @Test
    @DisplayName("guard: the point at infinity is not accepted as a public key (disproved hypothesis, kept as regression)")
    void identityPointPublicKeyRejected() {
        org.bouncycastle.asn1.x9.X9ECParameters p =
                org.bouncycastle.crypto.ec.CustomNamedCurves.getByName(com.hybrid.blockchain.Config.EC_CURVE);
        java.math.BigInteger n = p.getN();
        byte[] msg = Crypto.hash("any message".getBytes(StandardCharsets.UTF_8));
        java.math.BigInteger e = new java.math.BigInteger(1, msg);
        java.math.BigInteger s = java.math.BigInteger.valueOf(12345);
        java.math.BigInteger u1 = e.multiply(s.modInverse(n)).mod(n);
        java.math.BigInteger r = p.getG().multiply(u1).normalize().getAffineXCoord().toBigInteger().mod(n);
        byte[] sig = new byte[64];
        byte[] rb = org.bouncycastle.util.BigIntegers.asUnsignedByteArray(32, r);
        byte[] sb = org.bouncycastle.util.BigIntegers.asUnsignedByteArray(32, s);
        System.arraycopy(rb, 0, sig, 0, 32); System.arraycopy(sb, 0, sig, 32, 32);
        byte[] infinity = {0x00};
        boolean ok = Crypto.verify(msg, sig, infinity);
        System.out.printf("[guard-infinity] verify(msg, sig computed without any private key, pubKey=0x00) = %s; address=%s%n",
                ok, Crypto.deriveAddress(infinity));
        assertThat(ok).as("identity-point key must be rejected").isFalse();
    }
}
