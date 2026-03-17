package com.hybrid.blockchain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import java.util.Map;
import static org.assertj.core.api.Assertions.*;

@Tag("unit")
public class UTXOTest {

    @Test
    @DisplayName("Invariant: UTXO must be addable and balance should reflect it")
    void testUTXOAdditionAndBalance() throws Exception {
        try (TestBlockchain tb = new TestBlockchain()) {
            UTXOSet utxoSet = tb.getBlockchain().getUTXOSet();
            String address = "hbAddress123";
            
            utxoSet.addOutput("tx1", 0, address, 1000);
            
            assertThat(utxoSet.getBalance(address)).isEqualTo(1000);
            assertThat(utxoSet.isUnspent("tx1", 0)).isTrue();
        }
    }

    @Test
    @DisplayName("Security: Spending an UTXO must remove it and prevent double spending")
    void testDoubleSpendPrevention() throws Exception {
        try (TestBlockchain tb = new TestBlockchain()) {
            UTXOSet utxoSet = tb.getBlockchain().getUTXOSet();
            String address = "hbAddress123";
            
            utxoSet.addOutput("tx1", 0, address, 1000);
            
            // First spend
            assertThatNoException().isThrownBy(() -> utxoSet.spendOutput("tx1", 0));
            assertThat(utxoSet.getBalance(address)).isEqualTo(0);
            assertThat(utxoSet.isUnspent("tx1", 0)).isFalse();
            
            // Second spend (double spend)
            assertThatThrownBy(() -> utxoSet.spendOutput("tx1", 0))
                .isInstanceOf(Exception.class)
                .hasMessageContaining("UTXO not found");
        }
    }

    @Test
    @DisplayName("Adversarial: Spending non-existent UTXO must fail")
    void testSpendNonExistentUTXO() throws Exception {
        try (TestBlockchain tb = new TestBlockchain()) {
            UTXOSet utxoSet = tb.getBlockchain().getUTXOSet();
            assertThatThrownBy(() -> utxoSet.spendOutput("missing_tx", 0))
                .isInstanceOf(Exception.class);
        }
    }

    @Test
    @DisplayName("Invariant: UTXO set must survive persistence (Storage integration)")
    void testUTXOPersistence() throws Exception {
        java.nio.file.Path tempPath = java.nio.file.Files.createTempDirectory("utxo-persist-test");
        String dbPath = tempPath.toString();
        String address = "hbPersistAddr";
        byte[] aesKey = HexUtils.decode("00112233445566778899001122334455");
        
        try (Storage storage = new Storage(dbPath, aesKey)) {
            UTXOSet utxoSet = new UTXOSet();
            utxoSet.addOutput("tx_persist", 0, address, 500);
            
            // Explicitly save state to storage
            storage.saveUTXO(utxoSet.toJSON());
        }
        
        // Re-open storage and check
        try (Storage storage = new Storage(dbPath, aesKey)) {
            Map<String, Object> raw = storage.loadUTXO();
            UTXOSet restoredSet = UTXOSet.fromMap(raw);
            
            assertThat(restoredSet.getBalance(address)).isEqualTo(500);
            assertThat(restoredSet.isUnspent("tx_persist", 0)).isTrue();
        } finally {
            org.apache.commons.io.FileUtils.deleteDirectory(tempPath.toFile());
        }
    }
}
