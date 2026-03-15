package com.hybrid.blockchain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@Tag("unit")
class UTXOTest {

    @Test
    @DisplayName("Added UTXO output is reported as unspent")
    void addOutputThenUnspentTrue() {
        UTXOSet set = new UTXOSet();
        set.addOutput("tx1", 0, "hb-a", 10);
        assertTrue(set.isUnspent("tx1", 0), "Newly added UTXO output must be marked unspent");
    }

    @Test
    @DisplayName("Spent UTXO output is reported as spent")
    void spendThenUnspentFalse() throws Exception {
        UTXOSet set = new UTXOSet();
        set.addOutput("tx1", 0, "hb-a", 10);
        set.spendOutput("tx1", 0);
        assertFalse(set.isUnspent("tx1", 0), "Spent UTXO output must no longer be marked unspent");
    }

    @Test
    @DisplayName("Spending non-existent output throws")
    void spendingNonExistentThrows() {
        UTXOSet set = new UTXOSet();
        Exception ex = assertThrows(Exception.class, () -> set.spendOutput("none", 0), "Spending a non-existent UTXO must throw");
        assertTrue(ex.getMessage().toLowerCase().contains("not found"), "Error message must explain missing UTXO");
    }

    @Test
    @DisplayName("Double-spending same output throws on second spend")
    void doubleSpendThrows() throws Exception {
        UTXOSet set = new UTXOSet();
        set.addOutput("tx1", 0, "hb-a", 10);
        set.spendOutput("tx1", 0);
        assertThrows(Exception.class, () -> set.spendOutput("tx1", 0), "Attempting to spend same UTXO twice must throw");
    }

    @Test
    @DisplayName("UTXO input sum invariant rejects outputs exceeding inputs plus fee")
    void utxoInputOutputSumInvariant() throws Exception {
        Storage storage = new Storage(java.nio.file.Files.createTempDirectory("utxo-chain-").toString(), HexUtils.decode("00112233445566778899aabbccddeeff00112233445566778899aabbccddeeff"));
        Blockchain chain = new Blockchain(storage, new Mempool(), new PoAConsensus(java.util.List.of(
                new Validator("v1", Crypto.derivePublicKey(java.math.BigInteger.valueOf(11))),
                new Validator("v2", Crypto.derivePublicKey(java.math.BigInteger.valueOf(12))),
                new Validator("v3", Crypto.derivePublicKey(java.math.BigInteger.valueOf(13))),
                new Validator("v4", Crypto.derivePublicKey(java.math.BigInteger.valueOf(14)))
        )));
        chain.init();

        chain.utxo.addOutput("fund", 0, "hb-a", 10);

        Transaction tx = new Transaction.Builder()
                .type(Transaction.Type.UTXO)
            .from(null)
                .to("hb-b")
                .inputs(java.util.List.of(new UTXOInput("fund", 0)))
                .outputs(java.util.List.of(new UTXOOutput("hb-b", 11)))
                .fee(0)
                .build();

        Exception ex = assertThrows(Exception.class, () -> chain.validateTransaction(tx), "UTXO transaction must reject outputs that exceed input sum");
        assertTrue(ex.getMessage().toLowerCase().contains("insufficient") || ex.getMessage().toLowerCase().contains("utxo"), "Invariant failure must indicate UTXO input/output insufficiency");
        chain.shutdown();
    }

    @Test
    @DisplayName("getBalance sums unspent outputs correctly across 100 outputs")
    void getBalanceAccumulatesOutputs() {
        UTXOSet set = new UTXOSet();
        long expected = 0;
        for (int i = 0; i < 100; i++) {
            long amount = i + 1;
            expected += amount;
            set.addOutput("tx" + i, 0, "hb-acc", amount);
        }
        assertEquals(expected, set.getBalance("hb-acc"), "UTXO balance must equal sum of all unspent outputs for address");
    }

    @Test
    @DisplayName("findSpendable returns enough outputs when balance is sufficient")
    void findSpendableSufficient() {
        UTXOSet set = new UTXOSet();
        set.addOutput("a", 0, "hb-acc", 5);
        set.addOutput("b", 0, "hb-acc", 7);

        UTXOSet.Spendable spendable = set.findSpendable("hb-acc", 10);
        assertTrue(spendable.getTotal() >= 10, "Selected spendable outputs must cover requested amount");
        assertFalse(spendable.getUtxos().isEmpty(), "Spendable output set must not be empty when funds are sufficient");
    }

    @Test
    @DisplayName("findSpendable returns empty when balance is insufficient")
    void findSpendableInsufficient() {
        UTXOSet set = new UTXOSet();
        set.addOutput("a", 0, "hb-acc", 3);

        UTXOSet.Spendable spendable = set.findSpendable("hb-acc", 10);
        assertTrue(spendable.getTotal() < 10, "Returned spendable total must indicate insufficiency when requested amount cannot be covered");
    }

    @Test
    @DisplayName("UTXOSet serialization round-trip preserves balances")
    void utxoRoundTrip() {
        UTXOSet set = new UTXOSet();
        set.addOutput("a", 0, "hb-a", 3);
        set.addOutput("b", 1, "hb-a", 7);
        set.addOutput("c", 0, "hb-b", 4);

        Map<String, Object> json = set.toJSON();
        UTXOSet restored = UTXOSet.fromMap(json);

        assertEquals(set.getBalance("hb-a"), restored.getBalance("hb-a"), "Round-trip must preserve hb-a balance");
        assertEquals(set.getBalance("hb-b"), restored.getBalance("hb-b"), "Round-trip must preserve hb-b balance");
    }
}
