package com.hybrid.blockchain;

import java.security.*;
import java.util.*;

public class TestBlockchain {

    public static void main(String[] args) throws Exception {

        // --- Setup storage and mempool ---
        Storage storage = new Storage("data", "1234567890123456".getBytes()); // 16-byte key
        Mempool mempool = new Mempool();

        // --- Generate validators ---
        List<Validator> validators = new ArrayList<>();
        List<KeyPair> keyPairs = new ArrayList<>();

        for (int i = 0; i < 3; i++) {
            KeyPair kp = KeyPairGenerator.getInstance("RSA").generateKeyPair();
            keyPairs.add(kp);
            validators.add(new Validator("validator" + i, kp.getPublic())); // only id + public
        }

        // --- Setup PoA consensus ---
        PoAConsensus poa = new PoAConsensus(validators);

        // --- Create pruned blockchain ---
        PrunedBlockchain blockchain = new PrunedBlockchain(storage, mempool, 5, poa);

        // --- Initialize blockchain (load or genesis) ---
        blockchain.init();

        System.out.println("[INFO] Blockchain initialized. Current height: " + blockchain.getHeight());

        // --- Create a test block ---
        Block newBlock = blockchain.createBlock("miner1", 10);

        // --- Sign block with first validator's private key ---
        KeyPair firstValidatorKP = keyPairs.get(0);
        poa.signBlock(newBlock, validators.get(0), firstValidatorKP.getPrivate());

        // --- Apply block to blockchain ---
        blockchain.applyBlock(newBlock);

        System.out.println("[INFO] New block added. Height: " + blockchain.getHeight());
        System.out.println("[INFO] Block hash: " + newBlock.getHash());
        System.out.println("[INFO] Block signed by validator: " + newBlock.getValidatorId());

        // --- Test UTXO and account states ---
        System.out.println("[INFO] Miner balance: " + blockchain.getBalance("miner1"));

        // --- Add more blocks to test pruning ---
        for (int i = 0; i < 10; i++) {
            Block b = blockchain.createBlock("miner1", 5);
            poa.signBlock(b, validators.get(i % validators.size()), keyPairs.get(i % keyPairs.size()).getPrivate());
            blockchain.applyBlock(b);
        }

        System.out.println("[INFO] Final blockchain height after pruning: " + blockchain.getHeight());
    }
}
