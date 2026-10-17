package com.hybrid.blockchain.audit;

import com.hybrid.blockchain.Block;
import com.hybrid.blockchain.Crypto;
import com.hybrid.blockchain.TestKeyPair;
import com.hybrid.blockchain.Transaction;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * [HC-025] End-to-end through the PRODUCTION path: addTransaction -> createBlock -> applyBlock.
 * Existing block tests use BlockApplier, which bypasses createBlock; this is why HC-025 (no
 * transfer was ever included by the producer) went unnoticed.
 */
class BlockProductionTest {

    @Test
    void producerIncludesChainedTransfersAndBalancesAreCorrect() throws Exception {
        try (LedgerPoCFixture f = new LedgerPoCFixture()) {
            TestKeyPair alice = new TestKeyPair(9301);
            String bob = new TestKeyPair(9302).getAddress();
            long funded = f.fund(alice.getAddress());
            long fee = Math.max(1, f.chain.getCurrentBaseFee());

            Transaction t1 = LedgerPoCFixture.transfer(alice, bob, 5, fee, 1);
            Transaction t2 = LedgerPoCFixture.transfer(alice, bob, 7, fee, 2);
            f.chain.addTransaction(t1);
            f.chain.addTransaction(t2);                // admitted: nonce follows the pending t1

            TestKeyPair producer = LedgerPoCFixture.VALIDATORS[0];
            Block b = f.chain.createBlock(producer.getAddress(), 100);
            b.setSignature(Crypto.sign(Crypto.hash(b.serializeCanonical()), producer.getPrivateKey()));
            assertThat(b.getTransactions()).extracting(Transaction::getId).contains(t1.getId(), t2.getId());

            f.commitAndApply(b);
            assertThat(f.chain.getState().getBalance(bob)).isEqualTo(12);
            assertThat(f.chain.getState().getBalance(alice.getAddress())).isEqualTo(funded - 12 - 2 * fee);
            assertThat(f.chain.getNonce(alice.getAddress())).isEqualTo(2);
            assertThat(f.chain.getMempool().size()).isZero();
        }
    }

    @Test
    void producerSkipsTransferThatOverspendsCumulatively() throws Exception {
        try (LedgerPoCFixture f = new LedgerPoCFixture()) {
            TestKeyPair alice = new TestKeyPair(9303);
            long funded = f.fund(alice.getAddress());
            long fee = Math.max(1, f.chain.getCurrentBaseFee());
            long half = (funded - 2 * fee) / 2 + 1;              // two of these exceed the balance
            f.chain.addTransaction(LedgerPoCFixture.transfer(alice, "x", half, fee, 1));
            try { f.chain.addTransaction(LedgerPoCFixture.transfer(alice, "x", half, fee, 2)); }
            catch (Exception rejectedAtAdmission) { /* fine: pending-spend check */ }

            TestKeyPair producer = LedgerPoCFixture.VALIDATORS[0];
            Block b = f.chain.createBlock(producer.getAddress(), 100);
            b.setSignature(Crypto.sign(Crypto.hash(b.serializeCanonical()), producer.getPrivateKey()));
            f.commitAndApply(b);                               // must apply: no inapplicable tx included
            assertThat(f.chain.getState().getBalance("x")).isEqualTo(half);
        }
    }
}
