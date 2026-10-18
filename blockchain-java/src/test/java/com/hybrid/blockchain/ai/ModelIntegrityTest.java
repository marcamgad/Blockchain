package com.hybrid.blockchain.ai;

import com.hybrid.blockchain.Blockchain;
import com.hybrid.blockchain.PeerNode;
import com.hybrid.blockchain.TestKeyPair;
import com.hybrid.blockchain.consensus.PBFTConsensus;
import com.hybrid.blockchain.p2p.P2PMessage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** [HC-051] federated-learning model integrity and origin. */
class ModelIntegrityTest {

    @Test
    @DisplayName("a model whose weights do not hash to modelHash is refused")
    void mismatchedHashRefused() {
        FederatedLearningManager m = FederatedLearningManager.getInstance();
        m.reset();
        double[] w = {1.5, 2.5, 3.5};
        m.applyCommittedModel("ab".repeat(32), w, 1, 1, null);
        assertThat(m.getCurrentModelHash()).isNotEqualTo("ab".repeat(32));
        m.applyCommittedModel(FederatedLearningManager.modelHash(w), w, 1, 1, null);
        assertThat(m.getCurrentModelHash()).isEqualTo(FederatedLearningManager.modelHash(w));
        assertThat(m.getCurrentModel()).containsExactly(w);
        m.reset();
    }

    @Test
    @DisplayName("model broadcasts from non-validators are ignored")
    void nonValidatorBroadcastIgnored() {
        FederatedLearningManager m = FederatedLearningManager.getInstance();
        m.reset();
        TestKeyPair a = new TestKeyPair(8501), b = new TestKeyPair(8502), c = new TestKeyPair(8503), d = new TestKeyPair(8504);
        Map<String, byte[]> vs = new LinkedHashMap<>();
        for (TestKeyPair k : new TestKeyPair[]{a, b, c, d}) vs.put(k.getAddress(), k.getPublicKey());
        PBFTConsensus pbft = new PBFTConsensus(vs, a.getAddress(), a.getPrivateKey());
        try {
            PeerNode node = new PeerNode(0, Mockito.mock(Blockchain.class), pbft, a.getPrivateKey());
            double[] w = {4.0, 5.0};
            ByteBuffer buf = ByteBuffer.allocate(w.length * Double.BYTES);
            for (double x : w) buf.putDouble(x);
            byte[] payload = ("7:" + FederatedLearningManager.modelHash(w) + ":" + Base64.getEncoder().encodeToString(buf.array()))
                    .getBytes(StandardCharsets.UTF_8);
            TestKeyPair stranger = new TestKeyPair(8599);
            node.getGossipEngine().onMessageReceived(P2PMessage.create(stranger.getAddress(), stranger.getPrivateKey(),
                    P2PMessage.Type.FL_MODEL_BROADCAST, payload), stranger.getAddress());
            assertThat(m.getCurrentModelHash()).as("stranger's model ignored").isNotEqualTo(FederatedLearningManager.modelHash(w));
            node.getGossipEngine().onMessageReceived(P2PMessage.create(b.getAddress(), b.getPrivateKey(),
                    P2PMessage.Type.FL_MODEL_BROADCAST, payload), b.getAddress());
            assertThat(m.getCurrentModelHash()).as("validator's model applied").isEqualTo(FederatedLearningManager.modelHash(w));
        } finally {
            pbft.shutdown();
            m.reset();
        }
    }
}
