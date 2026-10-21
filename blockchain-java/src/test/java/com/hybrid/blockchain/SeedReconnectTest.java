package com.hybrid.blockchain;

import com.hybrid.blockchain.consensus.PBFTConsensus;
import com.hybrid.blockchain.testutil.ChainFixture;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * [HC-080] Found by the first live Docker run: a node that started before its seed dialled it once,
 * got "Connection refused", never retried, and stayed at height 0 with no peers.
 */
class SeedReconnectTest {

    // ChainFixture needs DEBUG=false (same setup as MTLSSecureNetworkTest); restored afterwards
    private static String previousDebug, previousAes;

    @org.junit.jupiter.api.BeforeAll
    static void productionMode() {
        previousDebug = System.getProperty("DEBUG");
        previousAes = System.getProperty("STORAGE_AES_KEY");
        System.setProperty("DEBUG", "false");
        // production mode refuses the published test-profile key (O-3): use a fresh one here
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

    @Test
    @DisplayName("a node started before its seed keeps dialling and connects once the seed is up")
    void reconnectsToLateSeed() throws Exception {
        TestKeyPair[] k = {new TestKeyPair(110), new TestKeyPair(210), new TestKeyPair(310), new TestKeyPair(410)};
        Map<String, byte[]> validators = new HashMap<>();
        for (TestKeyPair t : k) validators.put(t.getAddress(), t.getPublicKey());
        com.hybrid.blockchain.security.CertificateAuthority ca =
                new com.hybrid.blockchain.security.CertificateAuthority(Crypto.hash("test-network-ca".getBytes()));

        try (ChainFixture f1 = new ChainFixture(); ChainFixture f2 = new ChainFixture()) {
            PeerNode late = new PeerNode(9042, f2.getBlockchain(),
                    new PBFTConsensus(validators, k[1].getAddress(), k[1].getPrivateKey()), k[1].getPrivateKey(), p256(), ca);
            late.start();
            late.maintainSeedConnection("127.0.0.1", 9041, 300);   // the seed is NOT listening yet

            Thread.sleep(1_000);                                    // first attempts fail: connection refused
            assertThat(late.getPeers()).isEmpty();

            PeerNode seed = new PeerNode(9041, f1.getBlockchain(),
                    new PBFTConsensus(validators, k[0].getAddress(), k[0].getPrivateKey()), k[0].getPrivateKey(), p256(), ca);
            seed.start();
            try {
                Awaitility.await().atMost(Duration.ofSeconds(15)).pollInterval(Duration.ofMillis(200))
                        .untilAsserted(() -> assertThat(late.getPeers()).as("connected after the seed came up").hasSize(1));
            } finally {
                seed.shutdown();
                late.shutdown();
            }
        }
    }
}
