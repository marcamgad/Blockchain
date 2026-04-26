package com.hybrid.blockchain;

import com.hybrid.blockchain.testutil.TestTransactionFactory;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@Tag("unit")
public class BlockTest {

    @Test
    @DisplayName("Invariant: Block hashing must be deterministic")
    void testDeterministicHashing() {
        TestKeyPair kp = new TestKeyPair(1);
        List<Transaction> txs = new ArrayList<>();
        txs.add(TestTransactionFactory.createAccountTransfer(kp, "bob", 100, 1, 1));
        
        Block b1 = new Block(1, 1000L, txs, "0000000000000000", 1, "0000000000000000");
        Block b2 = new Block(1, 1000L, txs, "0000000000000000", 1, "0000000000000000");
        
        assertThat(b1.getHash()).isEqualTo(b2.getHash());
        assertThat(b1.getTxRoot()).isEqualTo(b2.getTxRoot());
    }

    @Test
    @DisplayName("Security: Tampering with a transaction must change TxRoot and Block Hash")
    void testTamperedTransactionChangesHash() {
        TestKeyPair kp = new TestKeyPair(1);
        List<Transaction> txs = new ArrayList<>();
        txs.add(TestTransactionFactory.createAccountTransfer(kp, "bob", 100, 1, 1));
        
        Block b1 = new Block(1, 1000L, txs, "0000", 1, "0000");
        String originalTxRoot = b1.getTxRoot();
        String originalHash = b1.getHash();
        
        // Mutate transaction slightly (in reality they are immutable, so we replace it)
        List<Transaction> tamperedTxs = new ArrayList<>();
        tamperedTxs.add(TestTransactionFactory.createAccountTransfer(kp, "bob", 101, 1, 1)); // Amount changed
        
        Block b2 = new Block(1, 1000L, tamperedTxs, "0000", 1, "0000");
        
        assertThat(b2.getTxRoot()).isNotEqualTo(originalTxRoot);
        assertThat(b2.getHash()).isNotEqualTo(originalHash);
    }

    @Test
    @DisplayName("Invariant: TxRoot calculation must be correct for empty transactions")
    void testEmptyTxRoot() {
        Block b = new Block(1, 1000L, new ArrayList<>(), "0000", 1, "0000");
        String emptyHex = Crypto.bytesToHex(new byte[32]);
        assertThat(b.getTxRoot()).isEqualTo(emptyHex);
    }

    @Test
    @DisplayName("Invariant: Mining must find a hash meeting the difficulty target")
    void testMiningDifficulty() {
        Block b = new Block(1, 1000L, new ArrayList<>(), "0000", 2, "0000"); // Difficulty 2
        
        b.mine(2, 1000000); // 1,000,000 max nonce
        
        assertThat(b.getHash()).startsWith("00");
        assertThat(b.getNonce()).isGreaterThan(0);
    }

    @Test
    @DisplayName("Security: Block must reject invalid transactions in hasValidTransactions()")
    void testBlockWithInvalidTransaction() {
        TestKeyPair kp = new TestKeyPair(1);
        List<Transaction> txs = new ArrayList<>();
        Transaction validTx = TestTransactionFactory.createAccountTransfer(kp, "bob", 100, 1, 1);
        
        // Corrupt signature
        Transaction invalidTx = new Transaction(
            validTx.getType(), validTx.getFrom(), validTx.getTo(), validTx.getAmount(), 
            validTx.getFee(), validTx.getNonce(), validTx.getTimestamp(), validTx.getNetworkId(), 
            validTx.getData(), validTx.getValidUntilBlock(), validTx.getInputs(), validTx.getOutputs(), 
            validTx.getPubKey(),
            new byte[64], // Fake signature
            validTx.getDilithiumPublicKey(),
            validTx.getDilithiumSignature()
        );
        
        txs.add(invalidTx);
        
        Block b = new Block(1, 1000L, txs, "0000", 1, "0000");
        
        assertThat(b.hasValidTransactions()).isFalse();
    }
}
