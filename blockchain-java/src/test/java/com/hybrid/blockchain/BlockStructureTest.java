package com.hybrid.blockchain;

import com.hybrid.blockchain.consensus.PBFTConsensus;
import com.hybrid.blockchain.testutil.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;
import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.*;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.stream.Stream;

/**
 * Unit and structural validation tests for Blocks.
 */
@Tag("unit")
public class BlockStructureTest {

    private TestBlockchain tb;
    private Blockchain blockchain;

    @BeforeEach
    void setUp() throws Exception {
        tb = new TestBlockchain();
        blockchain = tb.getBlockchain();
    }

    @AfterEach
    void tearDown() throws Exception {
        if (tb != null) tb.close();
    }

    @Test
    @DisplayName("B1.1 — Genesis block validation")
    void testGenesisBlock() {
        Block genesis = blockchain.getStorage().loadBlockByHeight(0);
        assertThat(genesis).isNotNull();
        assertThat(genesis.getIndex()).isEqualTo(0);
        assertThat(genesis.getPreviousHash()).isEqualTo("0000000000000000000000000000000000000000000000000000000000000000");
        assertThat(genesis.getTimestamp()).isLessThanOrEqualTo(System.currentTimeMillis());
    }

    @ParameterizedTest
    @MethodSource("provideBlockMutations")
    @DisplayName("B1.2 — Block.calculateHash() changes when any field changes")
    void testBlockHashChanges(BlockMutation mutation) {
        Block b = createStandardBlock();
        String originalHash = b.getHash();
        
        mutation.apply(b);
        String newHash = b.calculateHash();
        
        assertThat(newHash).as("Hash should change after field mutation: " + mutation.description)
                .isNotEqualTo(originalHash);
    }

    static Stream<BlockMutation> provideBlockMutations() {
        return Stream.of(
            new BlockMutation("timestamp", b -> b.setTimestamp(b.getTimestamp() + 1)),
            new BlockMutation("index", b -> b.setIndex(b.getIndex() + 1)),
            new BlockMutation("previousHash", b -> b.setPreviousHash("aa".repeat(32))),
            new BlockMutation("txRoot", b -> b.setTxRoot("bb".repeat(32))),
            new BlockMutation("stateRoot", b -> b.setStateRoot("cc".repeat(32)))
        );
    }

    static class BlockMutation {
        String description;
        java.util.function.Consumer<Block> mutator;
        BlockMutation(String d, java.util.function.Consumer<Block> m) { description = d; mutator = m; }
        void apply(Block b) { mutator.accept(b); }
    }

    @Test
    @DisplayName("B1.3 — Block.hasValidTransactions() rejects tampered signature")
    void testTamperedTxSignature() {
        TestKeyPair alice = new TestKeyPair(1);
        Transaction tx = TestTransactionFactory.createAccountTransfer(alice, "bob", 100, 1, 1);
        
        Block b = new Block(1, System.currentTimeMillis(), List.of(tx), "prev", 1, "state");
        assertThat(b.hasValidTransactions()).isTrue();
        
        // Tamper signature bytes
        byte[] sig = tx.getSignature();
        sig[0] ^= 0xFF;
        assertThat(b.hasValidTransactions()).as("Should detect tampered signature").isFalse();
    }

    @Test
    @DisplayName("B1.4 — Block.serializeCanonical() is deterministic")
    void testSerializationDeterministic() {
        Block b = createStandardBlock();
        byte[] first = b.serializeCanonical();
        byte[] second = b.serializeCanonical();
        assertThat(first).containsExactly(second);
    }

    @Test
    @DisplayName("B1.5 — Block with null transactions list")
    void testNullTransactions() {
        Block b = new Block(1, System.currentTimeMillis(), null, "prev", 1, "state");
        assertThat(b.hasValidTransactions()).as("Empty (null) tx list is valid").isTrue();
    }

    @Test
    @DisplayName("B1.6 — Block timestamp must be >= prevBlock timestamp")
    void testBlockTimestampOlder() throws Exception {
        Block last = blockchain.getLatestBlock();
        Block b = new Block(last.getIndex() + 1, last.getTimestamp() - 1, List.of(), last.getHash(), 1, "state");
        b.setValidatorId(tb.getValidatorKey().getAddress());
        b.setSignature(Crypto.sign(Crypto.hash(b.serializeCanonical()), tb.getValidatorKey().getPrivateKey()));
        
        assertThatThrownBy(() -> blockchain.applyBlock(b))
                .hasMessageContaining("Block timestamp older than previous block");
    }

    @Test
    @DisplayName("B1.7 — Block timestamp too far in future")
    void testBlockTimestampFuture() throws Exception {
        Block last = blockchain.getLatestBlock();
        long future = System.currentTimeMillis() + (Config.MAX_TIMESTAMP_DRIFT_MS * 2);
        Block b = new Block(last.getIndex() + 1, future, List.of(), last.getHash(), 1, "state");
        b.setValidatorId(tb.getValidatorKey().getAddress());
        b.setSignature(Crypto.sign(Crypto.hash(b.serializeCanonical()), tb.getValidatorKey().getPrivateKey()));
        
        assertThatThrownBy(() -> blockchain.applyBlock(b))
                .hasMessageContaining("Block timestamp too far in future");
    }

    @Test
    @DisplayName("B1.8 — Block with wrong prevHash is rejected")
    void testBlockPrevHashMismatch() throws Exception {
        Block last = blockchain.getLatestBlock();
        Block b = new Block(last.getIndex() + 1, System.currentTimeMillis(), List.of(), "WRONG_HASH", 1, "state");
        b.setValidatorId(tb.getValidatorKey().getAddress());
        b.setSignature(Crypto.sign(Crypto.hash(b.serializeCanonical()), tb.getValidatorKey().getPrivateKey()));
        
        assertThatThrownBy(() -> blockchain.applyBlock(b))
                .hasMessageContaining("prevHash does not chain");
    }

    @Test
    @DisplayName("B1.9 — Block size limit enforced")
    void testBlockSizeLimit() throws Exception {
        Block last = blockchain.getLatestBlock();
        List<Transaction> txs = new ArrayList<>();
        byte[] bigData = new byte[1024 * 1024]; // 1MB
        TestKeyPair alice = new TestKeyPair(1);
        
        // 3 x 1MB txs exceeds the typical 2MB block limit (Config dependency check)
        for(int i=0; i<3; i++) {
            txs.add(new Transaction.Builder()
                .type(Transaction.Type.CONTRACT)
                .from(alice.getAddress())
                .data(bigData)
                .nonce(i + 1)
                .sign(alice.getPrivateKey(), alice.getPublicKey()));
        }
        
        Block b = new Block(last.getIndex() + 1, System.currentTimeMillis(), txs, last.getHash(), 1, "state");
        b.setValidatorId(tb.getValidatorKey().getAddress());
        b.setSignature(Crypto.sign(Crypto.hash(b.serializeCanonical()), tb.getValidatorKey().getPrivateKey()));
        
        assertThatThrownBy(() -> blockchain.validateBlock(b))
                .hasMessageContaining("exceeds limit");
    }

    @Test
    @DisplayName("B1.10 — txRoot mismatch is detected")
    void testTxRootMismatch() throws Exception {
        TestKeyPair alice = new TestKeyPair(1);
        Transaction tx = TestTransactionFactory.createAccountTransfer(alice, "b", 1, 0, 1);
        Block b = new Block(1, System.currentTimeMillis(), new ArrayList<>(List.of(tx)), "prev", 1, "state");
        
        String correctRoot = b.getTxRoot();
        b.setTxRoot("corrupt_root");
        
        assertThatThrownBy(() -> blockchain.validateBlock(b))
                .hasMessageContaining("Invalid tx root");
    }

    @Test
    @DisplayName("B1.11 — stateRoot mismatch is detected")
    void testStateRootMismatch() throws Exception {
        Block last = blockchain.getLatestBlock();
        Block b = new Block(last.getIndex() + 1, System.currentTimeMillis(), List.of(), last.getHash(), 1, "WRONG_STATE");
        b.setValidatorId(tb.getValidatorKey().getAddress());
        b.setSignature(Crypto.sign(Crypto.hash(b.serializeCanonical()), tb.getValidatorKey().getPrivateKey()));
        
        assertThatThrownBy(() -> blockchain.applyBlock(b))
                .hasMessageContaining("Invalid state root");
    }

    @Test
    @DisplayName("B1.12 — Unknown validatorId is rejected")
    void testUnknownValidator() throws Exception {
        Block last = blockchain.getLatestBlock();
        Block b = new Block(last.getIndex() + 1, System.currentTimeMillis(), List.of(), last.getHash(), 1, "state");
        b.setValidatorId("INTRUDER");
        // Don't care about signature, validator check comes first
        
        assertThatThrownBy(() -> blockchain.validateBlock(b))
                .hasMessageContaining("Unknown validator");
    }

    @Test
    @DisplayName("B1.13 — Block with valid signature passes verifyBlock")
    void testValidBlockSignature() throws Exception {
        Block last = blockchain.getLatestBlock();
        Block b = new Block(last.getIndex() + 1, System.currentTimeMillis(), List.of(), last.getHash(), 1, "state");
        b.setValidatorId(tb.getValidatorKey().getAddress());
        b.setSignature(Crypto.sign(Crypto.hash(b.serializeCanonical()), tb.getValidatorKey().getPrivateKey()));
        
        // Should not throw
        blockchain.validateBlock(b);
    }

    private Block createStandardBlock() {
        return new Block(1, System.currentTimeMillis(), Collections.emptyList(), "prevHash", 1, "stateRoot");
    }
}
