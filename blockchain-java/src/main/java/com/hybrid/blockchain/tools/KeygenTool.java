// HybridChain — KeygenTool.java — Marc Amgad
package com.hybrid.blockchain.tools;

import com.hybrid.blockchain.Crypto;
import com.hybrid.blockchain.HexUtils;
import org.bouncycastle.asn1.x9.X9ECParameters;
import org.bouncycastle.crypto.AsymmetricCipherKeyPair;
import org.bouncycastle.crypto.ec.CustomNamedCurves;
import org.bouncycastle.crypto.generators.ECKeyPairGenerator;
import org.bouncycastle.crypto.params.ECDomainParameters;
import org.bouncycastle.crypto.params.ECKeyGenerationParameters;
import org.bouncycastle.crypto.params.ECPrivateKeyParameters;
import org.bouncycastle.crypto.params.ECPublicKeyParameters;

import java.math.BigInteger;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;

/**
 * Tool to generate N secp256k1 key pairs for network setup.
 */
public class KeygenTool {

    public static void main(String[] args) {
        if (args.length < 1) {
            System.err.println("Usage: KeygenTool <number_of_keys>");
            System.exit(1);
        }

        int n = Integer.parseInt(args[0]);

        X9ECParameters ecParams = CustomNamedCurves.getByName("secp256k1");
        ECDomainParameters domainParams = new ECDomainParameters(
                ecParams.getCurve(), ecParams.getG(), ecParams.getN(), ecParams.getH());

        ECKeyPairGenerator generator = new ECKeyPairGenerator();
        generator.init(new ECKeyGenerationParameters(domainParams, new SecureRandom()));

        List<String> pubKeys = new ArrayList<>();

        for (int i = 1; i <= n; i++) {
            AsymmetricCipherKeyPair kp = generator.generateKeyPair();
            ECPrivateKeyParameters priv = (ECPrivateKeyParameters) kp.getPrivate();
            ECPublicKeyParameters pub = (ECPublicKeyParameters) kp.getPublic();

            BigInteger privD = priv.getD();
            byte[] pubBytes = pub.getQ().getEncoded(true); // Compressed
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
