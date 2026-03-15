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

    public static void main(String[] args) {
        if (args.length < 1) {
            System.err.println("Usage: KeygenTool <number_of_keys> [seed]");
            System.exit(1);
        }

        int n = Integer.parseInt(args[0]);
        String seed = args.length >= 2 ? args[1] : "hybridchain-key-seed";

        X9ECParameters ecParams = CustomNamedCurves.getByName(Config.EC_CURVE);
        ECDomainParameters domainParams = new ECDomainParameters(
                ecParams.getCurve(), ecParams.getG(), ecParams.getN(), ecParams.getH());

        List<String> pubKeys = new ArrayList<>();

        for (int i = 1; i <= n; i++) {
            byte[] seedHash = Crypto.hash((seed + ":" + i).getBytes(StandardCharsets.UTF_8));
            BigInteger privD = new BigInteger(1, seedHash)
                    .mod(domainParams.getN().subtract(BigInteger.ONE))
                    .add(BigInteger.ONE);
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
