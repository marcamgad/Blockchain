package com.hybrid.blockchain.audit;

import com.hybrid.blockchain.Block;
import com.hybrid.blockchain.TestKeyPair;
import com.hybrid.blockchain.Transaction;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Phase 1, task 1.10 (docs/audit/independent-2026-10-24, H). Money conservation was only checked
 * with hand-picked examples. This runs random histories and checks the invariant after every step:
 *
 * <pre>  Σ account balances + Σ unspent UTXO amounts == total minted</pre>
 *
 * (fees move from senders to the proposer; nothing is burned), plus: an overspend is rejected, and
 * reverting the tip restores both sides of the equation.
 */
class SupplyInvariantPropertyTest {

    @Test
    @DisplayName("random histories of mints, transfers, rejected overspends and tip reverts conserve supply")
    void supplyIsConserved() throws Exception {
        int transferBlocks = 0, mintBlocks = 0, overspends = 0, reverts = 0;
        for (long seed = 0; seed < 20; seed++) {
            Random rnd = new Random(seed);
            try (LedgerPoCFixture f = new LedgerPoCFixture()) {
                TestKeyPair[] users = {new TestKeyPair(11001), new TestKeyPair(11002), new TestKeyPair(11003)};
                for (TestKeyPair u : users) f.fund(u.getAddress());
                check(f, seed, "after funding");

                for (int step = 0; step < 25; step++) {
                    int op = rnd.nextInt(10);
                    if (op < 6) {   // a block of 1-3 random valid transfers (plus its fees)
                        List<Transaction> txs = new ArrayList<>();
                        java.util.Map<String, Long> spent = new java.util.HashMap<>();
                        for (int k = 0, n = 1 + rnd.nextInt(3); k < n; k++) {
                            TestKeyPair from = users[rnd.nextInt(users.length)];
                            TestKeyPair to = users[rnd.nextInt(users.length)];
                            long fee = Math.max(1, f.chain.getCurrentBaseFee()) + rnd.nextInt(3);
                            long available = f.chain.getState().getBalance(from.getAddress()) - spent.getOrDefault(from.getAddress(), 0L) - fee;
                            if (available <= 0) continue;
                            long amount = rnd.nextInt((int) Math.min(available, 1_000) + 1);
                            long nonce = f.chain.getNonce(from.getAddress()) + 1
                                    + txs.stream().filter(t -> t.getFrom().equals(from.getAddress())).count();
                            txs.add(LedgerPoCFixture.transfer(from, to.getAddress(), amount, fee, nonce));
                            spent.merge(from.getAddress(), amount + fee, Long::sum);
                        }
                        if (!txs.isEmpty()) { f.commitAndApply(f.build(txs, 0)); transferBlocks++; }
                    } else if (op < 8) {   // a block with only the scheduled mint
                        f.commitAndApply(f.build(List.of(LedgerPoCFixture.mint(LedgerPoCFixture.VALIDATORS[0].getAddress(), f.scheduledReward())), 0));
                        mintBlocks++;
                    } else if (op == 8) {   // an overspend never gets in
                        TestKeyPair from = users[rnd.nextInt(users.length)];
                        long balance = f.chain.getState().getBalance(from.getAddress());
                        Transaction over = LedgerPoCFixture.transfer(from, users[0].getAddress(), balance + 1, 1, f.chain.getNonce(from.getAddress()) + 1);
                        assertThatThrownBy(() -> f.chain.validateTransaction(over)).as("seed=%d step=%d overspend", seed, step).isNotNull();
                        overspends++;
                    } else if (f.chain.getHeight() > 2) {   // revert the tip (fork handling path)
                        f.chain.revertTipForTest();
                        reverts++;
                    }
                    check(f, seed, "step " + step);
                }
            }
        }
        // not vacuous: every kind of step happened
        assertThat(transferBlocks).isGreaterThan(50);
        assertThat(mintBlocks).isGreaterThan(10);
        assertThat(overspends).isGreaterThan(5);
        assertThat(reverts).isGreaterThan(5);
    }

    private static void check(LedgerPoCFixture f, long seed, String where) {
        assertThat(LedgerPoCFixture.totalSupply(f.chain)).as("seed=%d %s: balances + UTXO == total minted", seed, where)
                .isEqualTo(f.chain.getTotalMinted());
    }
}
