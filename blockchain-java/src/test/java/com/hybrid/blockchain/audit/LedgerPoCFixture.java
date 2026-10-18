package com.hybrid.blockchain.audit;

import com.hybrid.blockchain.AccountState;
import com.hybrid.blockchain.Block;
import com.hybrid.blockchain.Blockchain;
import com.hybrid.blockchain.Crypto;
import com.hybrid.blockchain.HexUtils;
import com.hybrid.blockchain.Mempool;
import com.hybrid.blockchain.Storage;
import com.hybrid.blockchain.TestKeyPair;
import com.hybrid.blockchain.Transaction;
import com.hybrid.blockchain.UTXOSet;
import com.hybrid.blockchain.consensus.PBFTConsensus;
import org.apache.commons.io.FileUtils;

import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Audit fixture: a real {@link Blockchain} with a real 4-validator {@link PBFTConsensus}
 * (keys = TestKeyPair seeds 1..4) and NO mocks/spies, so PoCs exercise the production
 * {@code applyBlock -> applyBlockInternal} path.
 *
 * <p>{@link #build} computes the post-state root exactly the way
 * {@code Blockchain.applyBlockInternal} does (same tx sort, per-tx exceptions swallowed,
 * every tx's fee credited to the proposer), i.e. what an honest re-executing validator
 * would compute. A block built here is therefore what a Byzantine-but-valid-signature
 * proposer can get accepted. {@link #commitAndApply} records the commit certificate the
 * way {@code PeerNode.applyBlockAtSequence} does, then calls {@code applyBlock}.
 */
final class LedgerPoCFixture implements AutoCloseable {
    static final TestKeyPair[] VALIDATORS = {
            new TestKeyPair(1), new TestKeyPair(2), new TestKeyPair(3), new TestKeyPair(4)};

    final Path dir;
    final Storage storage;
    final PBFTConsensus consensus;
    final Blockchain chain;

    LedgerPoCFixture() throws Exception { this(0); }

    /** @param pruneMax &gt;0 builds the production PrunedBlockchain (App.java:92, env MAX_BLOCKS). */
    LedgerPoCFixture(int pruneMax) throws Exception {
        dir = Files.createTempDirectory("audit-ledger-");
        storage = new Storage(dir.toString(), HexUtils.decode("00112233445566778899001122334455"));
        Map<String, byte[]> vs = new LinkedHashMap<>();
        for (TestKeyPair k : VALIDATORS) vs.put(k.getAddress(), k.getPublicKey());
        consensus = new PBFTConsensus(vs, VALIDATORS[0].getAddress(), VALIDATORS[0].getPrivateKey());
        chain = pruneMax > 0
                ? new com.hybrid.blockchain.PrunedBlockchain(storage, new Mempool(1000), pruneMax, (com.hybrid.blockchain.Consensus) consensus)
                : new Blockchain(storage, new Mempool(1000), consensus);
        chain.init();
    }

    static Transaction mint(String to, long amount) {
        return new Transaction.Builder().type(Transaction.Type.MINT).to(to).amount(amount).build();
    }

    static Transaction transfer(TestKeyPair from, String to, long amount, long fee, long nonce) {
        return new Transaction.Builder().type(Transaction.Type.ACCOUNT).from(from.getAddress()).to(to)
                .amount(amount).fee(fee).nonce(nonce).sign(from.getPrivateKey(), from.getPublicKey());
    }

    long scheduledReward() {
        return com.hybrid.blockchain.Tokenomics.getCurrentReward(chain.getHeight() + 1, chain.getTotalMinted());
    }

    /** Reflective access to the package-private state-transition function. */
    static Object applyTx(Blockchain c, AccountState s, UTXOSet u, Transaction tx, long idx, long ts, String hash)
            throws Exception {
        Method m = Blockchain.class.getDeclaredMethod("applyTransactionToState", AccountState.class, UTXOSet.class,
                Transaction.class, long.class, long.class, String.class, List.class);
        m.setAccessible(true);
        try {
            return m.invoke(c, s, u, tx, idx, ts, hash, new ArrayList<>());
        } catch (java.lang.reflect.InvocationTargetException e) {
            throw (Exception) e.getCause();
        }
    }

    /** Builds a correctly rooted, signed next block proposed by {@code VALIDATORS[proposer]}. */
    Block build(List<Transaction> txs, int proposer) throws Exception {
        return build(chain, txs, proposer, chain.getLatestBlock().getTimestamp() + 1);
    }

    static Block build(Blockchain c, List<Transaction> txs, int proposer, long ts) throws Exception {
        Block prev = c.getLatestBlock();
        int idx = prev.getIndex() + 1;
        AccountState s = c.getState().cloneState();
        UTXOSet u = UTXOSet.fromMap(c.getUTXOSet().toJSON());
        s.setBlockHeight(idx);
        List<Transaction> sorted = new ArrayList<>(txs);
        sorted.sort(Comparator.comparingInt((Transaction t) -> t.getType() == Transaction.Type.MINT ? 0 : 1)
                .thenComparing(t -> t.getFrom() == null ? "" : t.getFrom())
                .thenComparingLong(Transaction::getNonce));
        long fees = 0;
        for (Transaction t : sorted) {
            try { applyTx(c, s, u, t, idx, ts, "SIM"); } catch (Exception ignored) { }
            fees += t.getFee();          // mirrors Blockchain.applyBlockInternal:781
        }
        TestKeyPair v = VALIDATORS[proposer];
        if (fees > 0) s.credit(v.getAddress(), fees);
        Block b = new Block(idx, ts, new ArrayList<>(txs), prev.getHash(), c.getDifficulty(), s.calculateStateRoot());
        b.setValidatorId(v.getAddress());
        b.setSignature(Crypto.sign(Crypto.hash(b.serializeCanonical()), v.getPrivateKey()));
        return b;
    }

    /**
     * Funds {@code address} the way the protocol allows after HC-016: the proposer
     * (VALIDATORS[0]) mints its own reward in one block, then transfers it in the next.
     * Returns the amount credited.
     */
    long fund(String address) throws Exception {
        TestKeyPair v = VALIDATORS[0];
        commitAndApply(build(List.of(mint(v.getAddress(), scheduledReward())), 0));
        long fee = Math.max(1, chain.getCurrentBaseFee());
        long amount = chain.getState().getBalance(v.getAddress()) - fee;
        commitAndApply(build(List.of(transfer(v, address, amount, fee, chain.getNonce(v.getAddress()) + 1)), 0));
        return amount;
    }

    void commitAndApply(Block b) throws Exception {
        consensus.markCommitted(b.getHash(), b.getIndex(), b.getValidatorId());
        chain.applyBlock(b);
    }

    /** Sum of every account balance plus every unspent UTXO amount. */
    static long totalSupply(Blockchain c) {
        long sum = 0;
        for (String a : c.getState().getAllAddresses()) sum += c.getState().getBalance(a);
        return sum + utxoTotal(c);
    }

    @SuppressWarnings("unchecked")
    static long utxoTotal(Blockchain c) {
        long sum = 0;
        for (Object v : c.getUTXOSet().toJSON().values()) {
            if (v instanceof Map) {
                Object amt = ((Map<String, Object>) v).get("amount");
                if (amt instanceof Number) sum += ((Number) amt).longValue();
            }
        }
        return sum;
    }

    @Override
    public void close() throws Exception {
        try { chain.shutdown(); } catch (Exception ignored) { }
        try { FileUtils.deleteDirectory(dir.toFile()); } catch (Exception ignored) { }
    }
}
