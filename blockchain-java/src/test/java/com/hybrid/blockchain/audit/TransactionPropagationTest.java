package com.hybrid.blockchain.audit;

import com.hybrid.blockchain.PeerNode;
import com.hybrid.blockchain.TestKeyPair;
import com.hybrid.blockchain.Transaction;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * [HC-104] Found by the live harness run of 2026-10-24: the equivocation evidence, submitted to v1
 * over REST, was never included, because a transaction submitted over REST, CoAP or MQTT only
 * entered the receiving node's own mempool. Only that node, as leader, could include it - and the
 * leader changes only on a view change. Client transactions are now gossiped to the validators.
 */
class TransactionPropagationTest {

    @Test
    @DisplayName("a transaction submitted by a client is broadcast to peers; an invalid one is not")
    void submittedTransactionsArePropagated() throws Exception {
        try (LedgerPoCFixture f = new LedgerPoCFixture()) {
            TestKeyPair alice = new TestKeyPair(10401);
            f.fund(alice.getAddress());
            PeerNode peer = mock(PeerNode.class);
            f.chain.setTransactionBroadcaster(peer::broadcastTransaction);
            long fee = Math.max(1, f.chain.getCurrentBaseFee());

            Transaction ok = LedgerPoCFixture.transfer(alice, "hb" + "00".repeat(20), 1, fee, f.chain.getNonce(alice.getAddress()) + 1);
            f.chain.submitTransaction(ok);
            verify(peer).broadcastTransaction(ok);

            Transaction overspend = LedgerPoCFixture.transfer(alice, "hb" + "00".repeat(20), Long.MAX_VALUE / 4, fee,
                    f.chain.getNonce(alice.getAddress()) + 2);
            assertThatThrownBy(() -> f.chain.submitTransaction(overspend)).isNotNull();
            verify(peer, never()).broadcastTransaction(overspend);
        }
    }
}
