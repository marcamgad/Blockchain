package com.hybrid.blockchain;

import com.hybrid.blockchain.consensus.PBFTConsensus;
import com.hybrid.blockchain.testutil.TestTransactionFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * [S1-01] A block rejected at the state-root check must leave NO partial mutations.
 *
 * <p>applyBlockInternal mutates the live state/utxo as it applies transactions but only
 * verifies the state root at the end. Without a rollback the node was left holding a
 * partially-applied (corrupt) state — and handleFork() re-enters this same path after
 * revertTip(), so the corrupting route is reachable during ordinary fork resolution.
 */
public class StateRollbackTest {

    private Blockchain blockchain;
    private Storage storage;
    private Mempool mempool;
    private TestKeyPair sender;

    @BeforeEach
    void setUp() throws Exception {
        storage = new Storage("target/rollback-db-" + java.util.UUID.randomUUID());
        mempool = new Mempool(100);
        PBFTConsensus consensus = Mockito.mock(PBFTConsensus.class);
        when(consensus.isValidator(any())).thenReturn(true);
        when(consensus.verifyBlock(any(), any())).thenReturn(true);
        List<Validator> vals = new ArrayList<>();
        vals.add(new Validator("ValidatorA", new byte[33]));
        when(consensus.getValidators()).thenReturn(vals);

        blockchain = new Blockchain(storage, mempool, consensus);
        blockchain.init();
        blockchain = Mockito.spy(blockchain);
        Mockito.doNothing().when(blockchain).validateBlock(any());

        sender = new TestKeyPair(9001);
        blockchain.getState().credit(sender.getAddress(), 1000L);
    }

    @Test
    @DisplayName("A block with a bad state root is rejected and leaves state untouched")
    void badStateRootRollsBackState() throws Exception {
        long balanceBefore = blockchain.getState().getBalance(sender.getAddress());
        long nonceBefore = blockchain.getState().getNonce(sender.getAddress());
        int heightBefore = blockchain.getHeight();
        String rootBefore = blockchain.getState().calculateStateRoot();

        Transaction tx = TestTransactionFactory.createAccountTransfer(sender, "receiver", 10, 1, 1);
        mempool.add(tx);

        Block bad = new Block(1,
                blockchain.getLatestBlock().getTimestamp() + 1,
                java.util.Collections.singletonList(tx),
                blockchain.getLatestBlock().getHash(),
                blockchain.getDifficulty(),
                "deadbeefdeadbeefdeadbeefdeadbeefdeadbeefdeadbeefdeadbeefdeadbeef"); // wrong on purpose
        bad.setValidatorId("ValidatorA");
        bad.setSignature(new byte[64]);
        bad.setTxRoot(bad.calculateTxRoot());

        assertThatThrownBy(() -> blockchain.applyBlock(bad))
                .hasMessageContaining("Invalid state root");

        // The transfer must have been fully rolled back.
        assertThat(blockchain.getState().getBalance(sender.getAddress()))
                .as("sender balance must be unchanged after a rejected block")
                .isEqualTo(balanceBefore);
        assertThat(blockchain.getState().getNonce(sender.getAddress()))
                .as("sender nonce must be unchanged after a rejected block")
                .isEqualTo(nonceBefore);
        assertThat(blockchain.getState().getBalance("receiver"))
                .as("recipient must not have been credited")
                .isEqualTo(0L);
        assertThat(blockchain.getState().calculateStateRoot())
                .as("state root must match the pre-block root")
                .isEqualTo(rootBefore);
        assertThat(blockchain.getHeight())
                .as("chain height must not advance on a rejected block")
                .isEqualTo(heightBefore);
    }

    @Test
    @DisplayName("A rejected block does not evict its transactions from the mempool")
    void rejectedBlockKeepsMempoolIntact() throws Exception {
        Transaction tx = TestTransactionFactory.createAccountTransfer(sender, "receiver", 10, 1, 1);
        mempool.add(tx);
        int sizeBefore = mempool.size();

        Block bad = new Block(1,
                blockchain.getLatestBlock().getTimestamp() + 1,
                java.util.Collections.singletonList(tx),
                blockchain.getLatestBlock().getHash(),
                blockchain.getDifficulty(),
                "deadbeefdeadbeefdeadbeefdeadbeefdeadbeefdeadbeefdeadbeefdeadbeef");
        bad.setValidatorId("ValidatorA");
        bad.setSignature(new byte[64]);
        bad.setTxRoot(bad.calculateTxRoot());

        assertThatThrownBy(() -> blockchain.applyBlock(bad));

        assertThat(mempool.size())
                .as("transactions must survive in the mempool when their block is rejected")
                .isEqualTo(sizeBefore);
    }

    @Test
    @DisplayName("A valid block still applies normally (rollback guard is transparent)")
    void validBlockStillApplies() throws Exception {
        Transaction tx = TestTransactionFactory.createAccountTransfer(sender, "receiver", 10, 1, 1);
        mempool.add(tx);

        AccountState sim = blockchain.getState().cloneState();
        sim.setBlockHeight(1);
        Block good = new Block(1,
                blockchain.getLatestBlock().getTimestamp() + 1,
                java.util.Collections.singletonList(tx),
                blockchain.getLatestBlock().getHash(),
                blockchain.getDifficulty(),
                "");
        good.setValidatorId("ValidatorA");
        good.setSignature(new byte[64]);
        good.setTxRoot(good.calculateTxRoot());

        blockchain.applyTransactionToState(sim, blockchain.getUTXOSet(), tx, 1,
                good.getTimestamp(), good.getHash(), new ArrayList<>());
        sim.credit("ValidatorA", tx.getFee()); // fee credit applied by applyBlockInternal
        good.setStateRoot(sim.calculateStateRoot());

        blockchain.applyBlock(good);

        assertThat(blockchain.getHeight()).isEqualTo(1);
        assertThat(blockchain.getState().getBalance("receiver")).isEqualTo(10L);
        assertThat(mempool.size()).as("accepted block evicts its transactions").isEqualTo(0);
    }
}
