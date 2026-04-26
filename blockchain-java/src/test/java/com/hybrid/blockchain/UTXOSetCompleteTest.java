package com.hybrid.blockchain;

import com.hybrid.blockchain.testutil.*;
import org.junit.jupiter.api.*;
import static org.assertj.core.api.Assertions.*;
import java.util.List;
import java.util.Map;

/**
 * Unit tests for UTXO set management including unspent output tracking,
 * balance summation, and block-level double-spend prevention.
 */
@Tag("unit")
public class UTXOSetCompleteTest {

    private UTXOSet utxoSet;

    @BeforeEach
    void setUp() {
        utxoSet = new UTXOSet();
    }

    @Test
    @DisplayName("U1.1-1.2 — addOutput + spendOutput")
    void testAddSpendOutput() {
        String txId = "tx1";
        UTXOSet.UTXOOutput output = new UTXOSet.UTXOOutput("alice", 100);
        
        utxoSet.addOutput(txId, 0, output);
        assertThat(utxoSet.isUnspent(txId, 0)).as("Output should be unspent after add").isTrue();
        assertThat(utxoSet.getAmount(txId, 0)).isEqualTo(100);
        
        boolean spent = utxoSet.spendOutput(txId, 0);
        assertThat(spent).as("spendOutput should return true for valid unspent").isTrue();
        assertThat(utxoSet.isUnspent(txId, 0)).as("Output should be spent").isFalse();
    }

    @Test
    @DisplayName("U1.3 — spendOutput rejection")
    void testSpendAlreadySpent() {
        utxoSet.addOutput("tx", 0, new UTXOSet.UTXOOutput("a", 10));
        utxoSet.spendOutput("tx", 0);
        
        boolean secondSpend = utxoSet.spendOutput("tx", 0);
        assertThat(secondSpend).as("Should return false when spending already-spent output").isFalse();
    }

    @Test
    @DisplayName("U1.5 — getBalance sums outputs")
    void testGetBalance() {
        utxoSet.addOutput("tx1", 0, new UTXOSet.UTXOOutput("alice", 100));
        utxoSet.addOutput("tx1", 1, new UTXOSet.UTXOOutput("alice", 50));
        utxoSet.addOutput("tx2", 0, new UTXOSet.UTXOOutput("bob", 200));
        
        assertThat(utxoSet.getBalance("alice")).as("Should sum all outputs for alice").isEqualTo(150L);
        assertThat(utxoSet.getBalance("bob")).isEqualTo(200L);
        assertThat(utxoSet.getBalance("charlie")).isEqualTo(0L);
    }

    @Test
    @DisplayName("U1.6 — toJSON + fromMap round-trip")
    @SuppressWarnings("unchecked")
    void testSerializationRoundTrip() {
        utxoSet.addOutput("tx1", 0, new UTXOSet.UTXOOutput("alice", 100));
        utxoSet.addOutput("tx2", 5, new UTXOSet.UTXOOutput("bob", 500));
        
        Map<String, Object> json = utxoSet.toJSON();
        UTXOSet restored = UTXOSet.fromMap(json);
        
        assertThat(restored.isUnspent("tx1", 0)).isTrue();
        assertThat(restored.getBalance("alice")).isEqualTo(100L);
        assertThat(restored.getBalance("bob")).isEqualTo(500L);
        assertThat(restored.getAmount("tx2", 5)).isEqualTo(500L);
    }

    @Test
    @DisplayName("U1.7 — UTXO double-spend in a single block")
    void testBlockLevelDoubleSpend() throws Exception {
        try (TestBlockchain tb = new TestBlockchain()) {
            Blockchain chain = tb.getBlockchain();
            TestKeyPair alice = new TestKeyPair(1);
            
            // Seed one UTXO
            String seedTx = "seed";
            chain.getUTXOSet().addOutput(seedTx, 0, new UTXOSet.UTXOOutput(alice.getAddress(), 1000L));
            
            // Create two transactions spending the same input
            Transaction tx1 = new Transaction.Builder()
                    .type(Transaction.Type.UTXO)
                    .addInput(seedTx, 0)
                    .addOutput("bob", 500L)
                    .fee(500L)
                    .sign(alice.getPrivateKey(), alice.getPublicKey())
                    .build();
                    
            Transaction tx2 = new Transaction.Builder()
                    .type(Transaction.Type.UTXO)
                    .addInput(seedTx, 0)
                    .addOutput("charlie", 500L)
                    .fee(500L)
                    .sign(alice.getPrivateKey(), alice.getPublicKey())
                    .build();
            
            // Attempt to apply a block with both
            // BlockApplier.createAndApplyBlock will catch the simulation error
            Block block = BlockApplier.createAndApplyBlock(tb, List.of(tx1, tx2));
            
            TransactionReceipt r1 = chain.getStorage().loadReceipt(tx1.getTxId());
            TransactionReceipt r2 = chain.getStorage().loadReceipt(tx2.getTxId());
            
            assertThat(r1.getStatus()).as("First spend should succeed").isEqualTo(TransactionReceipt.STATUS_SUCCESS);
            assertThat(r2.getStatus()).as("Second spend should fail").isEqualTo(TransactionReceipt.STATUS_FAILED);
            assertThat(r2.getError()).as("Error should indicate UTXO unavailable").contains("UTXO input not available");
        }
    }
}
