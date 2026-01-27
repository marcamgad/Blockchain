package com.hybrid.blockchain;

import java.math.BigInteger;
import java.util.*;
import org.bouncycastle.asn1.x9.X9ECParameters;
import org.bouncycastle.crypto.ec.CustomNamedCurves;
import org.bouncycastle.crypto.generators.ECKeyPairGenerator;
import org.bouncycastle.crypto.params.ECDomainParameters;
import org.bouncycastle.crypto.params.ECKeyGenerationParameters;
import org.bouncycastle.crypto.params.ECPrivateKeyParameters;
import org.bouncycastle.crypto.params.ECPublicKeyParameters;
import org.bouncycastle.crypto.AsymmetricCipherKeyPair;
import java.security.SecureRandom;

public class TestBlockchain {

    public static void main(String[] args) throws Exception {

        // --- Setup storage and mempool ---
        Storage storage = new Storage("data", "1234567890123456".getBytes());
        Mempool mempool = new Mempool();

        // --- Generate secp256k1 validators ---
        X9ECParameters ecParams = CustomNamedCurves.getByName("secp256k1");
        ECDomainParameters domainParams = new ECDomainParameters(ecParams.getCurve(), ecParams.getG(), ecParams.getN(),
                ecParams.getH());
        ECKeyPairGenerator generator = new ECKeyPairGenerator();
        generator.init(new ECKeyGenerationParameters(domainParams, new SecureRandom()));

        List<Validator> validators = new ArrayList<>();
        List<BigInteger> privateKeys = new ArrayList<>();

        for (int i = 0; i < 3; i++) {
            AsymmetricCipherKeyPair kp = generator.generateKeyPair();
            ECPublicKeyParameters pub = (ECPublicKeyParameters) kp.getPublic();
            ECPrivateKeyParameters priv = (ECPrivateKeyParameters) kp.getPrivate();

            byte[] pubKeyBytes = pub.getQ().getEncoded(true);
            privateKeys.add(priv.getD());
            validators.add(new Validator("validator" + i, pubKeyBytes));
        }

        // --- Setup PoA consensus ---
        PoAConsensus poa = new PoAConsensus(validators);

        // --- Create pruned blockchain ---
        PrunedBlockchain blockchain = new PrunedBlockchain(storage, mempool, 5, poa);
        blockchain.init();

        System.out.println("[INFO] Blockchain initialized. Current height: " + blockchain.getHeight());

        // --- Create a test block ---
        Block newBlock = blockchain.createBlock("hb5d41402abc4b2a76b9719d911017c5921c1433", 10);

        // --- Sign block with first validator's private key ---
        poa.signBlock(newBlock, validators.get(0), privateKeys.get(0));

        // --- Apply block to blockchain ---
        blockchain.applyBlock(newBlock);

        System.out.println("[INFO] New block added. Height: " + blockchain.getHeight());
        System.out.println("[INFO] Block hash: " + newBlock.getHash());

        // --- Add more blocks to test pruning ---
        for (int i = 0; i < 10; i++) {
            Block b = blockchain.createBlock("hb5d41402abc4b2a76b9719d911017c5921c1433", 5);
            poa.signBlock(b, validators.get(i % validators.size()), privateKeys.get(i % privateKeys.size()));
            blockchain.applyBlock(b);
        }

        System.out.println("[INFO] Final blockchain height after pruning: " + blockchain.getHeight());
    }
}
