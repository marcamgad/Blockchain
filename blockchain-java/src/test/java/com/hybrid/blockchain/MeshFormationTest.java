package com.hybrid.blockchain;

import com.hybrid.blockchain.consensus.PBFTConsensus;
import com.hybrid.blockchain.p2p.PeerManager;
import com.hybrid.blockchain.testutil.ChainFixture;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * [HC-084] Found by the live leader-crash scenario: every node connected only to the seed (v1),
 * discovered peers were never dialled, and inbound peers were advertised with their ephemeral
 * port. When the seed was also the leader and was killed, the survivors had no connection to each
 * other and could not form a quorum.
 */
class MeshFormationTest {

    private static String previousDebug, previousAes;

    @org.junit.jupiter.api.BeforeAll
    static void productionMode() {
        previousDebug = System.getProperty("DEBUG");
        previousAes = System.getProperty("STORAGE_AES_KEY");
        System.setProperty("DEBUG", "false");
        byte[] aes = new byte[16];
        new java.security.SecureRandom().nextBytes(aes);
        System.setProperty("STORAGE_AES_KEY", HexUtils.encode(aes));
        System.setProperty("NODE_PRIVATE_KEY", "1111111111111111111111111111111111111111111111111111111111111111");
    }

    @org.junit.jupiter.api.AfterAll
    static void restoreMode() {
        if (previousDebug == null) System.clearProperty("DEBUG"); else System.setProperty("DEBUG", previousDebug);
        if (previousAes == null) System.clearProperty("STORAGE_AES_KEY"); else System.setProperty("STORAGE_AES_KEY", previousAes);
    }

    private static java.security.KeyPair p256() throws Exception {
        java.security.KeyPairGenerator g = java.security.KeyPairGenerator.getInstance("EC", "BC");
        g.initialize(new java.security.spec.ECGenParameterSpec("secp256r1"));
        return g.generateKeyPair();
    }

    private static java.util.Set<String> peerIds(PeerNode n) {
        return n.getPeers().stream().map(PeerManager.PeerInfo::getId).collect(Collectors.toSet());
    }

    @Test
    @DisplayName("two nodes that only know the seed find and connect to each other, and stay connected without it")
    void meshSurvivesSeedLoss() throws Exception {
        TestKeyPair[] k = {new TestKeyPair(120), new TestKeyPair(220), new TestKeyPair(320), new TestKeyPair(420)};
        Map<String, byte[]> validators = new HashMap<>();
        for (TestKeyPair t : k) validators.put(t.getAddress(), t.getPublicKey());
        com.hybrid.blockchain.security.CertificateAuthority ca =
                new com.hybrid.blockchain.security.CertificateAuthority(Crypto.hash("test-network-ca".getBytes()));

        try (ChainFixture fs = new ChainFixture(); ChainFixture fa = new ChainFixture(); ChainFixture fb = new ChainFixture()) {
            PeerNode seed = new PeerNode(9061, fs.getBlockchain(),
                    new PBFTConsensus(validators, k[0].getAddress(), k[0].getPrivateKey()), k[0].getPrivateKey(), p256(), ca);
            PeerNode a = new PeerNode(9062, fa.getBlockchain(),
                    new PBFTConsensus(validators, k[1].getAddress(), k[1].getPrivateKey()), k[1].getPrivateKey(), p256(), ca);
            PeerNode b = new PeerNode(9063, fb.getBlockchain(),
                    new PBFTConsensus(validators, k[2].getAddress(), k[2].getPrivateKey()), k[2].getPrivateKey(), p256(), ca);
            for (ChainFixture f : new ChainFixture[]{fs, fa, fb}) f.getBlockchain().init();   // genesis
            try {
                seed.start();
                a.start();
                b.start();
                a.maintainSeedConnection("127.0.0.1", 9061, 2_000);
                b.maintainSeedConnection("127.0.0.1", 9061, 2_000);
                for (PeerNode n : new PeerNode[]{seed, a, b}) n.maintainMesh(300);

                Awaitility.await().atMost(Duration.ofSeconds(25)).pollInterval(Duration.ofMillis(300))
                        .untilAsserted(() -> {
                            for (PeerNode n : new PeerNode[]{seed, a, b}) n.broadcastPeerList();   // the 30 s task in App
                            assertThat(peerIds(a)).as("A is connected to B directly").contains(k[2].getAddress());
                        });

                seed.shutdown();
                Thread.sleep(1_000);
                assertThat(peerIds(a)).as("A keeps B after the seed is gone").contains(k[2].getAddress());
            } finally {
                a.shutdown();
                b.shutdown();
            }
        }
    }
}
