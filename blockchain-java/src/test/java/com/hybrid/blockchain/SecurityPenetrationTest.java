package com.hybrid.blockchain;

import com.hybrid.blockchain.p2p.GossipEngine;
import com.hybrid.blockchain.p2p.P2PMessage;
import com.hybrid.blockchain.p2p.PeerManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;

@Tag("integration")
class SecurityPenetrationTest extends TestHarness {

    private PoAConsensus poa;
    private Validator leader;
    private BigInteger leaderPriv;
    private BigInteger alicePriv;
    private byte[] alicePub;
    private String alice;

    @BeforeEach
    void setUp() throws Exception {
        System.setProperty("DEBUG", "false");
        List<Validator> validators = defaultValidators();
        poa = new PoAConsensus(validators);
        tempDir = java.nio.file.Files.createTempDirectory("sec-");
        storage = new Storage(tempDir.toString(), TEST_AES_KEY);
        blockchain = new Blockchain(storage, new Mempool(1000), poa);
        blockchain.init();

        leader = validators.get(0);
        leaderPriv = privateKey(101);

        alicePriv = privateKey(8001);
        alicePub = Crypto.derivePublicKey(alicePriv);
        alice = Crypto.deriveAddress(alicePub);
        blockchain.getState().credit(alice, 1_000_000);
    }

    private void mineOneBlock() throws Exception {
        Block b = blockchain.createBlock(leader.getId(), 100);
        poa.signBlock(b, leader, leaderPriv);
        blockchain.applyBlock(b);
    }

    @Test
    @DisplayName("Replay attack is rejected due nonce mismatch after first inclusion")
    void replayAttackRejected() throws Exception {
        Transaction tx = new Transaction.Builder().type(Transaction.Type.ACCOUNT).to("hb-bob").amount(10).fee(1).nonce(1).sign(alicePriv, alicePub);
        blockchain.addTransaction(tx);
        mineOneBlock();

        Exception ex = assertThrows(Exception.class, () -> blockchain.validateTransaction(tx), "Replaying already-applied transaction must fail due nonce mismatch");
        assertTrue(ex.getMessage().toLowerCase().contains("nonce"), "Replay rejection must explicitly identify nonce mismatch");
    }

    @Test
    @DisplayName("UTXO double-spend is rejected on second spend")
    void utxoDoubleSpendRejected() throws Exception {
        blockchain.utxo.addOutput("fund", 0, alice, 20);

        Transaction t1 = new Transaction.Builder().type(Transaction.Type.UTXO).from(null).to("hb-r1")
                .inputs(List.of(new UTXOInput("fund", 0))).outputs(List.of(new UTXOOutput("hb-r1", 10))).fee(0).build();
        Transaction t2 = new Transaction.Builder().type(Transaction.Type.UTXO).from(null).to("hb-r2")
                .inputs(List.of(new UTXOInput("fund", 0))).outputs(List.of(new UTXOOutput("hb-r2", 10))).fee(0).build();

        assertDoesNotThrow(() -> blockchain.validateTransaction(t1), "First UTXO spend should validate while input is unspent");
        blockchain.utxo.spendOutput("fund", 0);
        assertThrows(Exception.class, () -> blockchain.validateTransaction(t2), "Second spend of same UTXO must be rejected");
    }

    @Test
    @DisplayName("Same-nonce account double-spend keeps at most one candidate in mempool")
    void accountDoubleSpendSameNonceSingleCandidate() throws Exception {
        Transaction low = new Transaction.Builder().type(Transaction.Type.ACCOUNT).to("hb-a").amount(1).fee(1).nonce(1).sign(alicePriv, alicePub);
        Transaction high = new Transaction.Builder().type(Transaction.Type.ACCOUNT).to("hb-b").amount(1).fee(5).nonce(1).sign(alicePriv, alicePub);

        blockchain.addTransaction(low);
        blockchain.addTransaction(high);

        long sameNonceCount = blockchain.getMempool().toArray().stream().filter(t -> t.getFrom().equals(alice) && t.getNonce() == 1).count();
        assertEquals(1, sameNonceCount, "Mempool must keep only one transaction per sender+nonce to prevent account double-spend inclusion");
    }

    @Test
    @DisplayName("Signature forgery is rejected by verify and validateTransaction")
    void forgedSignatureRejected() {
        Transaction tx = new Transaction.Builder().type(Transaction.Type.ACCOUNT).to("hb-bob").amount(5).fee(1).nonce(1).sign(alicePriv, alicePub);
        byte[] fakeSig = new byte[64];
        new java.security.SecureRandom().nextBytes(fakeSig);
        Transaction forgedTx = new Transaction(tx.getType(), tx.getFrom(), tx.getTo(), tx.getAmount(), tx.getFee(), tx.getNonce(), tx.getTimestamp(), tx.getNetworkId(), tx.getData(), tx.getValidUntilBlock(), tx.getInputs(), tx.getOutputs(), alicePub, fakeSig);

        assertFalse(forgedTx.verify(), "Forged random signature bytes must fail cryptographic verification");
        Exception ex = assertThrows(Exception.class, () -> blockchain.validateTransaction(forgedTx), "Validation must reject transaction with forged signature");
        assertTrue(ex.getMessage().toLowerCase().contains("signature"), "Forgery rejection message must mention invalid signature");
    }

    @Test
    @DisplayName("Amount overflow (Long.MAX_VALUE + fee) is rejected")
    void amountOverflowRejected() {
        Transaction tx = new Transaction.Builder().type(Transaction.Type.ACCOUNT).to("hb-bob").amount(Long.MAX_VALUE).fee(1).nonce(1).sign(alicePriv, alicePub);
        Exception ex = assertThrows(Exception.class, () -> blockchain.validateTransaction(tx), "Overflowing amount+fee must be rejected to prevent inflation attacks");
        assertTrue(ex.getMessage().toLowerCase().contains("overflow") || ex.getCause() instanceof ArithmeticException, "Overflow rejection must preserve arithmetic overflow context");
    }

    @Test
    @DisplayName("Malformed block with wrong height is rejected")
    void wrongHeightBlockRejected() {
        Block block = blockchain.createBlock(leader.getId(), 10);
        block.index = blockchain.getHeight() + 2;
        assertThrows(Exception.class, () -> {
            poa.signBlock(block, leader, leaderPriv);
            blockchain.applyBlock(block);
        }, "Blocks skipping height sequence must be rejected");
    }

    @Test
    @DisplayName("Malformed block with excessive future timestamp is rejected")
    void futureTimestampRejected() {
        Block block = blockchain.createBlock(leader.getId(), 10);
        block.timestamp = System.currentTimeMillis() + Config.MAX_TIMESTAMP_DRIFT + 5_000;
        block.setHash(block.calculateHash());
        Exception ex = assertThrows(Exception.class, () -> {
            poa.signBlock(block, leader, leaderPriv);
            blockchain.applyBlock(block);
        }, "Blocks beyond max timestamp drift must be rejected");
        assertTrue(ex.getMessage().toLowerCase().contains("timestamp"), "Future timestamp rejection must mention timestamp bounds");
    }

    @Test
    @DisplayName("Malformed block with past timestamp relative to previous block is rejected")
    void pastTimestampRejected() {
        Block block = blockchain.createBlock(leader.getId(), 10);
        block.timestamp = blockchain.getLatestBlock().getTimestamp() - 1;
        block.setHash(block.calculateHash());
        assertThrows(Exception.class, () -> {
            poa.signBlock(block, leader, leaderPriv);
            blockchain.applyBlock(block);
        }, "Blocks older than previous block timestamp must be rejected");
    }

    @Test
    @DisplayName("State root tampering before applyBlock is rejected")
    void stateRootManipulationRejected() throws Exception {
        Block block = blockchain.createBlock(leader.getId(), 10);
        poa.signBlock(block, leader, leaderPriv);
        block.setStateRoot("abcd");

        Exception ex = assertThrows(Exception.class, () -> blockchain.applyBlock(block), "Tampered state root must invalidate block application");
        assertTrue(ex.getMessage().toLowerCase().contains("state root") || ex.getMessage().toLowerCase().contains("signature"), "State-root tampering must fail via state-root or signature-integrity validation");
    }

    @Test
    @DisplayName("Unauthorized validator block is rejected")
    void unauthorizedValidatorRejected() throws Exception {
        Block block = blockchain.createBlock(leader.getId(), 10);
        BigInteger roguePriv = privateKey(8999);
        block.setValidatorId(Crypto.deriveAddress(Crypto.derivePublicKey(roguePriv)));
        block.setSignature(Crypto.sign(Crypto.hash(block.serializeCanonical()), roguePriv));

        Exception ex = assertThrows(Exception.class, () -> blockchain.applyBlock(block), "Block signed by validator outside authorized set must be rejected");
        assertTrue(ex.getMessage().toLowerCase().contains("unknown validator"), "Unauthorized validator rejection must be explicit");
    }

    @Test
    @DisplayName("P2P flooding retains seenMessages bounded to 5000 entries")
    void p2pFloodBoundedCache() throws Exception {
        GossipEngine engine = new GossipEngine(new PeerManager(), 3);
        BigInteger priv = BigInteger.valueOf(123456);
        for (int i = 0; i < 10_000; i++) {
            P2PMessage m = P2PMessage.create("peer", priv, P2PMessage.Type.TRANSACTION, ("payload-" + i).getBytes());
            engine.validateAndProcess(m);
        }

        java.lang.reflect.Field seen = GossipEngine.class.getDeclaredField("seenMessages");
        seen.setAccessible(true);
        Map<?, ?> seenMessages = (Map<?, ?>) seen.get(engine);
        assertTrue(seenMessages.size() <= 5000, "Gossip seen-message cache must stay bounded at configured LRU cap during flooding");
    }

    @Test
    @DisplayName("Block containing null transaction is rejected with controlled exception")
    void nullTransactionInBlockRejected() throws Exception {
        Block block = blockchain.createBlock(leader.getId(), 10);
        block.getTransactions().add(null);
        block.setHash(block.calculateHash());
        poa.signBlock(block, leader, leaderPriv);

        assertThrows(Exception.class, () -> blockchain.applyBlock(block), "Blocks with null transaction entries must be rejected explicitly");
    }

    @Test
    @DisplayName("Negative nonce transaction is rejected")
    void negativeNonceRejected() {
        Transaction tx = new Transaction.Builder().type(Transaction.Type.ACCOUNT).to("hb-bob").amount(1).fee(1).nonce(-1).sign(alicePriv, alicePub);
        Exception ex = assertThrows(Exception.class, () -> blockchain.validateTransaction(tx), "Negative nonce must be rejected by transaction validation");
        assertTrue(ex.getMessage().toLowerCase().contains("nonce"), "Negative nonce rejection must mention nonce validation");
    }

    @Test
    @DisplayName("Infinite-loop bytecode gas exhaustion test is not feasible because jump opcodes are unimplemented")
    void gasExhaustionLoopLimitationDocumented() {
        CompletableFuture<Boolean> future = CompletableFuture.supplyAsync(() -> {
            byte[] code = new byte[]{OpCode.JUMP.getByte()};
            Interpreter vm = new Interpreter(code, 100, new Interpreter.BlockchainContext(System.currentTimeMillis(), 1, alice, "c", 0, new AccountState(), new HardwareManager(), "h"));
            try {
                vm.execute();
                return false;
            } catch (Exception e) {
                return true;
            }
        });

        assertTimeoutPreemptively(java.time.Duration.ofSeconds(2), () -> {
            assertTrue(future.get(2, TimeUnit.SECONDS), "Current VM implementation raises unknown-opcode before any loop can form because JUMP is declared but not implemented");
        }, "Smart-contract execution must not hang test thread even under adversarial bytecode");
    }
}
