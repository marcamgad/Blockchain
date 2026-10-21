// HybridChain — KeygenTool.java — Marc Amgad
package com.hybrid.blockchain.tools;

import com.hybrid.blockchain.Crypto;
import com.hybrid.blockchain.Config;
import com.hybrid.blockchain.HexUtils;
import org.bouncycastle.asn1.x9.X9ECParameters;
import org.bouncycastle.crypto.ec.CustomNamedCurves;
import org.bouncycastle.crypto.params.ECDomainParameters;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Tool to generate N secp256k1 key pairs for network setup.
 */
public class KeygenTool {

    /**
     * The i-th private key derived from {@code seed}. Public so that keys from published seeds can
     * be recognised and refused ({@link com.hybrid.blockchain.security.CompromisedKeys}).
     */
    public static BigInteger derivePrivateKey(String seed, int i) {
        BigInteger n = CustomNamedCurves.getByName(Config.EC_CURVE).getN();
        byte[] seedHash = Crypto.hash((seed + ":" + i).getBytes(StandardCharsets.UTF_8));
        return new BigInteger(1, seedHash).mod(n.subtract(BigInteger.ONE)).add(BigInteger.ONE);
    }

    public static void main(String[] args) {
        if (args.length < 1) {
            System.err.println("Usage: KeygenTool <number_of_keys> [seed]");
            System.exit(1);
        }

        int n = Integer.parseInt(args[0]);
        // [HC-010] No seed given -> 32 bytes from SecureRandom. The old default
        // "hybridchain-key-seed" made every validator key of every network set up without a seed
        // publicly computable. A seed is still accepted for reproducible TEST keys only.
        String seed;
        if (args.length >= 2) {
            seed = args[1];
            System.err.println("WARNING: deterministic keys from a supplied seed - anyone who knows the seed "
                    + "knows every private key. Use for test networks only.");
        } else {
            byte[] rnd = new byte[32];
            new java.security.SecureRandom().nextBytes(rnd);
            seed = HexUtils.encode(rnd);
        }

        X9ECParameters ecParams = CustomNamedCurves.getByName(Config.EC_CURVE);
        ECDomainParameters domainParams = new ECDomainParameters(
                ecParams.getCurve(), ecParams.getG(), ecParams.getN(), ecParams.getH());

        List<String> pubKeys = new ArrayList<>();

        for (int i = 1; i <= n; i++) {
            BigInteger privD = derivePrivateKey(seed, i);
            byte[] pubBytes = Crypto.derivePublicKey(privD);
            String address = Crypto.deriveAddress(pubBytes);
            String pubHex = HexUtils.encode(pubBytes);

            System.out.println("NODE_" + i + "_PRIVATE_KEY=" + privD.toString(16));
            System.out.println("NODE_" + i + "_PUBLIC_KEY=" + pubHex);
            System.out.println("NODE_" + i + "_ADDRESS=" + address);
            System.out.println("----------------------------------------");

            pubKeys.add(pubHex);
        }

        System.out.print("VALIDATOR_PUBKEYS=");
        System.out.println(String.join(",", pubKeys));
    }
}
