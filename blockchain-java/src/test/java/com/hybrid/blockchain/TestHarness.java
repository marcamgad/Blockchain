package com.hybrid.blockchain;

import org.junit.jupiter.api.AfterEach;

import java.io.IOException;
import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

abstract class TestHarness {

    protected Blockchain blockchain;
    protected Storage storage;
    protected Path tempDir;

    protected static final byte[] TEST_AES_KEY = HexUtils.decode("00112233445566778899aabbccddeeff00112233445566778899aabbccddeeff");

    protected static BigInteger privateKey(int seed) {
        return BigInteger.valueOf(seed).shiftLeft(20).add(BigInteger.valueOf(7));
    }

    protected static Validator validatorFromPrivateKey(BigInteger privateKey) {
        byte[] pub = Crypto.derivePublicKey(privateKey);
        return new Validator(Crypto.deriveAddress(pub), pub);
    }

    protected static Transaction signedAccountTx(BigInteger fromPriv, String to, long amount, long fee, long nonce) {
        return new Transaction.Builder()
                .type(Transaction.Type.ACCOUNT)
                .to(to)
                .amount(amount)
                .fee(fee)
                .nonce(nonce)
                .sign(fromPriv, Crypto.derivePublicKey(fromPriv));
    }

    protected Blockchain initPoABlockchain(List<Validator> validators) throws Exception {
        tempDir = Files.createTempDirectory("bc-test-");
        storage = new Storage(tempDir.toString(), TEST_AES_KEY);
        blockchain = new Blockchain(storage, new Mempool(10_000), new PoAConsensus(validators));
        blockchain.init();
        return blockchain;
    }

    protected List<Validator> defaultValidators() {
        List<Validator> validators = new ArrayList<>();
        validators.add(validatorFromPrivateKey(privateKey(101)));
        validators.add(validatorFromPrivateKey(privateKey(102)));
        validators.add(validatorFromPrivateKey(privateKey(103)));
        validators.add(validatorFromPrivateKey(privateKey(104)));
        return validators;
    }

    @AfterEach
    void tearDownHarness() throws Exception {
        if (blockchain != null) {
            blockchain.shutdown();
        }
        if (tempDir != null) {
            deleteRecursively(tempDir);
        }
        System.clearProperty("DEBUG");
    }

    private void deleteRecursively(Path path) throws IOException {
        if (!Files.exists(path)) {
            return;
        }
        Files.walk(path)
                .sorted((a, b) -> b.getNameCount() - a.getNameCount())
                .forEach(p -> {
                    try {
                        Files.deleteIfExists(p);
                    } catch (IOException e) {
                        throw new RuntimeException(e);
                    }
                });
    }
}
