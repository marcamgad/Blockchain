package com.hybrid.blockchain.audit;

import com.hybrid.blockchain.AccountState;
import com.hybrid.blockchain.Block;
import com.hybrid.blockchain.Crypto;
import com.hybrid.blockchain.MerklePatriciaTrie;
import com.hybrid.blockchain.TestKeyPair;
import com.hybrid.blockchain.Transaction;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase C property tests for the Ledger module (docs/audit, batch 2).
 *
 * <p>Randomised with FIXED seeds (reproducible). jqwik is not on the offline classpath,
 * so this is plain seeded random generation, not shrinking property-based testing; a
 * failure prints the seed and iteration. These assert properties that SHOULD hold and
 * currently DO hold — they are evidence for REVIEWED-CLEAN rows, not PoCs.
 */
@Tag("audit-property")
class LedgerPropertyTest {

    private static byte[] rnd(Random r, int n) { byte[] b = new byte[n]; r.nextBytes(b); return b; }

    @Test
    void mptRootIsIndependentOfInsertionOrder() {
        for (long seed = 1; seed <= 25; seed++) {
            Random r = new Random(seed);
            int n = 1 + r.nextInt(400);
            List<String> keys = new ArrayList<>();
            List<byte[]> vals = new ArrayList<>();
            for (int i = 0; i < n; i++) { keys.add("k" + r.nextLong()); vals.add(rnd(r, 1 + r.nextInt(80))); }
            byte[] ref = null;
            for (int perm = 0; perm < 8; perm++) {
                List<Integer> idx = new ArrayList<>();
                for (int i = 0; i < n; i++) idx.add(i);
                Collections.shuffle(idx, new Random(seed * 1000 + perm));
                MerklePatriciaTrie t = new MerklePatriciaTrie();
                for (int i : idx) t.put(keys.get(i), vals.get(i));
                // overwrite a random subset with the same value: must be a no-op
                for (int i = 0; i < n / 5; i++) { int j = r.nextInt(n); t.put(keys.get(j), vals.get(j)); }
                byte[] root = t.getRootHash();
                if (ref == null) ref = root;
                assertThat(root).as("seed=%d perm=%d n=%d", seed, perm, n).isEqualTo(ref);
            }
        }
    }

    @Test
    void mptDeleteRestoresPreviousRoot() {
        for (long seed = 1; seed <= 25; seed++) {
            Random r = new Random(seed);
            MerklePatriciaTrie t = new MerklePatriciaTrie();
            int n = 1 + r.nextInt(300);
            for (int i = 0; i < n; i++) t.put("k" + r.nextLong(), rnd(r, 40));
            byte[] before = t.getRootHash();
            List<String> extra = new ArrayList<>();
            for (int i = 0; i < 1 + r.nextInt(30); i++) { String k = "x" + r.nextLong(); extra.add(k); t.put(k, rnd(r, 40)); }
            Collections.shuffle(extra, r);
            for (String k : extra) t.delete(k);
            assertThat(t.getRootHash()).as("seed=%d", seed).isEqualTo(before);
        }
    }

    @Test
    void stateRootSurvivesSerializationRoundTrip() {
        for (long seed = 1; seed <= 25; seed++) {
            Random r = new Random(seed);
            AccountState s = new AccountState();
            for (int i = 0; i < 1 + r.nextInt(200); i++) {
                String a = "addr" + r.nextInt(100);
                s.credit(a, r.nextInt(1_000_000));
                if (r.nextBoolean()) s.incrementNonce(a);
                if (r.nextInt(5) == 0) s.putStorage(a, r.nextInt(50), r.nextLong());
            }
            assertThat(s.cloneState().calculateStateRoot()).as("seed=%d", seed).isEqualTo(s.calculateStateRoot());
        }
    }

    /** S1-01 rollback guard: a block rejected at the state-root check leaves state untouched. */
    @Test
    void rejectedBlockLeavesStateRootAndBalancesUnchanged() throws Exception {
        for (long seed = 1; seed <= 10; seed++) {
            Random r = new Random(seed);
            try (LedgerPoCFixture f = new LedgerPoCFixture()) {
                TestKeyPair[] users = {new TestKeyPair(9100 + (int) seed), new TestKeyPair(9200 + (int) seed)};
                for (TestKeyPair u : users) f.fund(u.getAddress());   // HC-016: no minting to arbitrary users
                String rootBefore = f.chain.getState().calculateStateRoot();
                long b0 = f.chain.getState().getBalance(users[0].getAddress());
                List<Transaction> txs = new ArrayList<>();
                long nonce = 1;
                for (int i = 0; i < 1 + r.nextInt(5); i++)
                    txs.add(LedgerPoCFixture.transfer(users[0], users[1].getAddress(), r.nextInt(5), 1, nonce++));
                Block good = f.build(txs, 0);
                Block bad = new Block(good.getIndex(), good.getTimestamp(), good.getTransactions(), good.getPrevHash(),
                        good.getDifficulty(), Crypto.bytesToHex(rnd(r, 32)));
                bad.setValidatorId(good.getValidatorId());
                bad.setSignature(Crypto.sign(bad.signingDigest(), LedgerPoCFixture.VALIDATORS[0].getPrivateKey()));
                try { f.commitAndApply(bad); } catch (Exception expected) { }
                assertThat(f.chain.getState().calculateStateRoot()).as("seed=%d", seed).isEqualTo(rootBefore);
                assertThat(f.chain.getState().getBalance(users[0].getAddress())).isEqualTo(b0);
                // and the correct block still applies afterwards
                f.commitAndApply(good);
                assertThat(f.chain.getHeight()).isEqualTo(good.getIndex());
            }
        }
    }

    /** [HC-035] same UTXO entries, different insertion history / capacity -> same commitment. */
    @Test
    void utxoCanonicalHashIndependentOfHistory() {
        for (long seed = 1; seed <= 20; seed++) {
            Random r = new Random(seed);
            com.hybrid.blockchain.UTXOSet a = new com.hybrid.blockchain.UTXOSet();
            List<String> kept = new ArrayList<>();
            for (int i = 0; i < 500; i++) {                     // grow the table, then delete most
                String tx = "t" + r.nextLong();
                a.addOutput(tx, 0, "addr" + r.nextInt(50), 1 + r.nextInt(1000));
                if (i % 40 == 0) kept.add(tx); else a.spendOutput(tx, 0);
            }
            com.hybrid.blockchain.UTXOSet restarted = com.hybrid.blockchain.UTXOSet.fromMap(a.toJSON());
            com.hybrid.blockchain.UTXOSet reversed = new com.hybrid.blockchain.UTXOSet();
            List<String> keys = new ArrayList<>(a.toJSON().keySet());
            Collections.reverse(keys);
            for (String k : keys) {
                @SuppressWarnings("unchecked")
                java.util.Map<String, Object> u = (java.util.Map<String, Object>) a.toJSON().get(k);
                String[] p = k.split(":");
                reversed.addOutput(p[0], Integer.parseInt(p[1]), (String) u.get("address"), ((Number) u.get("amount")).longValue());
            }
            assertThat(restarted.canonicalHash()).as("seed=%d", seed).isEqualTo(a.canonicalHash());
            assertThat(reversed.canonicalHash()).as("seed=%d", seed).isEqualTo(a.canonicalHash());
        }
    }

    @Test
    void txRootIsStableForSameOrderedList() {
        Random r = new Random(7);
        for (int it = 0; it < 50; it++) {
            List<Transaction> txs = new ArrayList<>();
            for (int i = 0; i < 1 + r.nextInt(20); i++)
                txs.add(new Transaction.Builder().type(Transaction.Type.MINT).to("a" + r.nextInt()).amount(1)
                        .nonce(r.nextLong()).timestamp(1L).build());
            Block a = new Block(1, 1L, new ArrayList<>(txs), "00".repeat(32), 1, "00".repeat(32));
            Block b = new Block(1, 1L, new ArrayList<>(txs), "00".repeat(32), 1, "00".repeat(32));
            assertThat(a.getHash()).isEqualTo(b.getHash());
            assertThat(new String(a.serializeCanonical(), StandardCharsets.ISO_8859_1))
                    .isEqualTo(new String(b.serializeCanonical(), StandardCharsets.ISO_8859_1));
        }
    }
}
