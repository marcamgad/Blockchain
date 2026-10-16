package com.hybrid.blockchain.audit;

import com.hybrid.blockchain.Blockchain;
import com.hybrid.blockchain.Crypto;
import com.hybrid.blockchain.TestKeyPair;
import com.hybrid.blockchain.Transaction;
import com.hybrid.blockchain.api.MQTTAdapter;
import com.hybrid.blockchain.consensus.PBFTConsensus;
import com.hybrid.blockchain.security.CertificateAuthority;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.cert.X509Certificate;
import java.security.spec.ECGenParameterSpec;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Proof-of-concept tests for the HybridChain security audit (docs/audit/HYBRIDCHAIN_AUDIT.md).
 *
 * <p>Every test here asserts the CORRECT behaviour. While a finding is unfixed its test
 * FAILS — that failure is the proof. When the fix lands, the test turns green and becomes
 * the regression test.
 *
 * <p>Tagged {@code audit-poc} and excluded from the default build so the main suite stays
 * green. Run them with:
 * <pre>
 *   mvn -o test -Dtest=HybridChainAuditPoCTest -Daudit.excluded=none
 * </pre>
 */
@Tag("audit-poc")
public class HybridChainAuditPoCTest {

    // ─────────────────────────────────────────────────────────────────────────
    // HC-001  Gossip starvation: a 4-validator network can never send a message
    // ─────────────────────────────────────────────────────────────────────────

    /** Run inside a FRESH JVM with NO -DMIN_PEERS override — i.e. exactly as production. */
    public static final class HC001Probe {
        public static void main(String[] a) {
            com.hybrid.blockchain.p2p.PeerManager pm = new com.hybrid.blockchain.p2p.PeerManager();
            // PBFT's own minimum is N=4 validators -> each node has N-1 = 3 peers.
            for (int i = 1; i <= 3; i++) pm.addPeer("peer-" + i, "10.0.0." + i, 6001);
            System.out.println("TARGETS=" + pm.selectGossipPeers(3, null).size());
        }
    }

    @Test
    @DisplayName("HC-001: in the minimum 4-validator network a node must still have gossip targets")
    void hc001_fourValidatorNetworkCanGossip() throws Exception {
        String javaBin = System.getProperty("java.home") + "/bin/java";
        Process p = new ProcessBuilder(javaBin, "-cp", System.getProperty("java.class.path"),
                HC001Probe.class.getName())
                .redirectErrorStream(true).start();
        int targets = -1;
        try (BufferedReader r = new BufferedReader(
                new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) {
                if (line.startsWith("TARGETS=")) targets = Integer.parseInt(line.substring(8));
            }
        }
        p.waitFor();
        assertThat(targets)
                .as("HC-001: with 3 peers (N=4) selectGossipPeers returned %d targets — "
                        + "no PBFT vote, block or tx can ever leave the node", targets)
                .isGreaterThan(0);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // HC-002  Quorum = 2f+1 is unsafe when n != 3f+1 (e.g. n = 5)
    // ─────────────────────────────────────────────────────────────────────────

    private static byte[] commitSig(TestKeyPair kp, long seq, String hash) {
        PBFTConsensus.PBFTMessage m = new PBFTConsensus.PBFTMessage(
                PBFTConsensus.Phase.COMMIT, 0, seq, hash, kp.getAddress());
        m.sign(kp.getPrivateKey());
        return m.signature;
    }

    @Test
    @DisplayName("HC-002: with n=5, two honest replicas must not certify different blocks at one seq")
    void hc002_quorumUnsafeForNNotEqual3fPlus1() {
        // n = 5 -> f = (5-1)/3 = 1 -> code's quorum = 2f+1 = 3.
        // Byzantine primary B tells {h1,h2} block A and {h3,h4} block B.
        List<TestKeyPair> kps = new ArrayList<>();
        Map<String, byte[]> vals = new LinkedHashMap<>();
        for (int i = 0; i < 5; i++) {
            TestKeyPair kp = new TestKeyPair(88000 + i);
            kps.add(kp);
            vals.put(kp.getAddress(), kp.getPublicKey());
        }
        TestKeyPair byz = kps.get(0), h1 = kps.get(1), h2 = kps.get(2), h3 = kps.get(3), h4 = kps.get(4);
        PBFTConsensus replicaH1 = new PBFTConsensus(vals, h1.getAddress(), h1.getPrivateKey());
        PBFTConsensus replicaH3 = new PBFTConsensus(vals, h3.getAddress(), h3.getPrivateKey());
        try {
            long seq = 7;
            // h1's view: {byz, h1, h2} all COMMIT "A"
            for (TestKeyPair v : List.of(byz, h1, h2))
                replicaH1.addCommitVote(seq, "A", v.getAddress(), commitSig(v, seq, "A"));
            // h3's view: {byz, h3, h4} all COMMIT "B"
            for (TestKeyPair v : List.of(byz, h3, h4))
                replicaH3.addCommitVote(seq, "B", v.getAddress(), commitSig(v, seq, "B"));

            String certH1 = replicaH1.getQuorumHash(seq, PBFTConsensus.Phase.COMMIT);
            String certH3 = replicaH3.getQuorumHash(seq, PBFTConsensus.Phase.COMMIT);
            System.out.printf("[HC-002] n=5 f=1 quorum=3: h1 certifies %s, h3 certifies %s%n", certH1, certH3);

            boolean conflictingCertificates = certH1 != null && certH3 != null && !certH1.equals(certH3);
            assertThat(conflictingCertificates)
                    .as("HC-002 SAFETY: two honest replicas hold commit certificates for different "
                            + "blocks (%s vs %s) at seq %d; the two quorums overlap only in the "
                            + "Byzantine node", certH1, certH3, seq)
                    .isFalse();
        } finally {
            replicaH1.shutdown();
            replicaH3.shutdown();
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // HC-003  The mTLS CA private key is SHA-256(STORAGE_AES_KEY)
    // ─────────────────────────────────────────────────────────────────────────

    private static KeyPair p256() throws Exception {
        KeyPairGenerator g = KeyPairGenerator.getInstance("EC", "BC");
        g.initialize(new ECGenParameterSpec("secp256r1"));
        return g.generateKeyPair();
    }

    private static java.security.PrivateKey caPrivateKey(CertificateAuthority ca) throws Exception {
        java.lang.reflect.Field f = CertificateAuthority.class.getDeclaredField("caKeyPair");
        f.setAccessible(true);
        return ((KeyPair) f.get(ca)).getPrivate();
    }

    /**
     * NOTE: an earlier version of this test asserted that an attacker's forged certificate
     * verifies against the victim CA. It did not — but only because of HC-005 (the CA's
     * public key is random and matches no private key, so NOTHING verifies). The real,
     * directly provable property is that the CA SIGNING key is a pure function of
     * STORAGE_AES_KEY. Fixing HC-005 naively would make the forgery work immediately.
     */
    @Test
    @DisplayName("HC-003: the CA signing key must not be derivable from the disk-encryption key")
    void hc003_caSigningKeyDerivedFromStorageKey() throws Exception {
        byte[] aesKey = java.util.Arrays.copyOf(
                Crypto.hash("contents-of-any-operators-env-file".getBytes(StandardCharsets.UTF_8)), 16);
        CertificateAuthority victimCa = new CertificateAuthority(aesKey);
        CertificateAuthority attackerCa = new CertificateAuthority(aesKey); // attacker only knows the key

        boolean identical = java.util.Arrays.equals(
                caPrivateKey(victimCa).getEncoded(), caPrivateKey(attackerCa).getEncoded());
        assertThat(identical)
                .as("HC-003: anyone holding STORAGE_AES_KEY reconstructs the network CA's private "
                        + "signing key bit-for-bit")
                .isFalse();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // HC-005  The CA's public key does not belong to its private key
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("HC-005: a node certificate issued by the CA must verify under that CA's own certificate")
    void hc005_caIssuedCertDoesNotVerifyAgainstOwnCa() throws Exception {
        CertificateAuthority ca = new CertificateAuthority(new byte[16]);
        KeyPair node = p256();
        X509Certificate nodeCert = ca.issueNodeCertificate("hbNODE", node.getPublic(), node.getPrivate());

        String result;
        try {
            nodeCert.verify(ca.getCACertificate().getPublicKey());
            result = "VERIFIES";
        } catch (Exception e) {
            result = "FAILS (" + e.getClass().getSimpleName() + ")";
        }
        System.out.println("[HC-005] node cert vs its own CA certificate: " + result);
        assertThat(result)
                .as("HC-005: CertificateAuthority pairs a deterministic private key with the public "
                        + "key of an unrelated RANDOM key pair, so its certificates never verify")
                .isEqualTo("VERIFIES");
    }

    /** End-to-end: two nodes on the PRODUCTION (CA) TLS path, sharing STORAGE_AES_KEY. */
    @Test
    @DisplayName("HC-005: two nodes on the production CA path must be able to complete mutual TLS")
    void hc005_productionMtlsHandshakeFails() throws Exception {
        byte[] sharedStorageKey = new byte[16]; // same network secret on both nodes
        javax.net.ssl.SSLContext serverCtx = com.hybrid.blockchain.security.SSLUtils.createSSLContextWithCA(
                new CertificateAuthority(sharedStorageKey), p256(), "hbSERVER");
        javax.net.ssl.SSLContext clientCtx = com.hybrid.blockchain.security.SSLUtils.createSSLContextWithCA(
                new CertificateAuthority(sharedStorageKey), p256(), "hbCLIENT");

        try (javax.net.ssl.SSLServerSocket server =
                     (javax.net.ssl.SSLServerSocket) serverCtx.getServerSocketFactory().createServerSocket(0)) {
            server.setNeedClientAuth(true);
            java.util.concurrent.CompletableFuture<String> serverSide = java.util.concurrent.CompletableFuture.supplyAsync(() -> {
                try (javax.net.ssl.SSLSocket s = (javax.net.ssl.SSLSocket) server.accept()) {
                    s.startHandshake();
                    return "OK";
                } catch (Exception e) { return "FAIL: " + e.getMessage(); }
            });
            String clientSide;
            try (javax.net.ssl.SSLSocket c = (javax.net.ssl.SSLSocket)
                    clientCtx.getSocketFactory().createSocket("127.0.0.1", server.getLocalPort())) {
                c.setSoTimeout(5000);
                c.startHandshake();
                clientSide = "OK";
            } catch (Exception e) { clientSide = "FAIL: " + e.getMessage(); }
            String srv = serverSide.get(10, java.util.concurrent.TimeUnit.SECONDS);
            System.out.printf("[HC-005] CA-path mTLS handshake: client=%s | server=%s%n", clientSide, srv);
            assertThat(clientSide)
                    .as("HC-005: production nodes cannot establish mTLS with each other at all")
                    .isEqualTo("OK");
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // HC-006  Legacy SSL path installs a trust-all TrustManager
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("HC-006: a node must reject a TLS client presenting a certificate it has never seen")
    void hc006_legacyPathAcceptsAnyCertificate() throws Exception {
        javax.net.ssl.SSLContext serverCtx = com.hybrid.blockchain.security.SSLUtils.createSSLContext(
                p256(), "hbLEGIT", new ArrayList<>());          // exactly what PeerNode's 3/4-arg ctors pass
        javax.net.ssl.SSLContext attackerCtx = com.hybrid.blockchain.security.SSLUtils.createSSLContext(
                p256(), "hbATTACKER", new ArrayList<>());

        try (javax.net.ssl.SSLServerSocket server =
                     (javax.net.ssl.SSLServerSocket) serverCtx.getServerSocketFactory().createServerSocket(0)) {
            server.setNeedClientAuth(true);
            java.util.concurrent.CompletableFuture<String> serverSide = java.util.concurrent.CompletableFuture.supplyAsync(() -> {
                try (javax.net.ssl.SSLSocket s = (javax.net.ssl.SSLSocket) server.accept()) {
                    s.startHandshake();
                    return "ACCEPTED " + s.getSession().getPeerPrincipal().getName();
                } catch (Exception e) { return "REJECTED"; }
            });
            try (javax.net.ssl.SSLSocket c = (javax.net.ssl.SSLSocket)
                    attackerCtx.getSocketFactory().createSocket("127.0.0.1", server.getLocalPort())) {
                c.setSoTimeout(5000);
                c.startHandshake();
            } catch (Exception ignored) { }
            String srv = serverSide.get(10, java.util.concurrent.TimeUnit.SECONDS);
            System.out.println("[HC-006] server verdict on attacker's self-signed cert: " + srv);
            assertThat(srv)
                    .as("HC-006: the legacy TLS path trusts ANY certificate (empty checkClientTrusted)")
                    .isEqualTo("REJECTED");
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // HC-007  Pre-authentication handshake allocates an attacker-chosen buffer
    // ─────────────────────────────────────────────────────────────────────────

    private static byte[] handshakePrefix(int challengeLen) throws Exception {
        java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
        java.io.DataOutputStream d = new java.io.DataOutputStream(bos);
        d.writeInt(0);                                   // MsgType.HELLO.ordinal()
        d.writeInt(40);                                  // len (discarded by the node)
        d.writeInt(1);                                   // PROTOCOL_VERSION
        d.writeInt(com.hybrid.blockchain.Config.NETWORK_ID);
        d.write(new byte[32]);                           // remote nonce
        d.writeInt(1);                                   // MsgType.CHALLENGE.ordinal()
        d.writeInt(challengeLen);                        // attacker-controlled length
        d.write(new byte[33]);                           // "public key"
        d.flush();                                       // ...then the attacker stops sending
        return bos.toByteArray();
    }

    @Test
    @DisplayName("HC-007: an unauthenticated peer must not control handshake buffer allocation")
    void hc007_preAuthHandshakeUnboundedAllocation() throws Exception {
        com.hybrid.blockchain.PeerNode node = new com.hybrid.blockchain.PeerNode(
                0, Mockito.mock(Blockchain.class),
                Mockito.mock(com.hybrid.blockchain.Consensus.class), new java.math.BigInteger("7"));
        Method inbound = com.hybrid.blockchain.PeerNode.class.getDeclaredMethod("performInboundHandshake",
                java.io.DataInputStream.class, java.io.DataOutputStream.class, byte[].class);
        inbound.setAccessible(true);

        Throwable thrown = null;
        try {
            inbound.invoke(node,
                    new java.io.DataInputStream(new java.io.ByteArrayInputStream(handshakePrefix(Integer.MAX_VALUE))),
                    new java.io.DataOutputStream(new java.io.ByteArrayOutputStream()),
                    new byte[32]);
        } catch (java.lang.reflect.InvocationTargetException ite) {
            thrown = ite.getCause();
        }
        System.out.println("[HC-007] handshake with challengeLen=Integer.MAX_VALUE threw: " + thrown);
        assertThat(thrown)
                .as("HC-007: must be rejected as a protocol error (IOException) BEFORE allocating; "
                        + "instead the node attempted a ~2 GB allocation, and an Error is not caught "
                        + "by the connection handler's catch (Exception)")
                .isInstanceOf(java.io.IOException.class);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // HC-004  IoT ingress signs as Config.NODE_ID, which is not the node's address
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("HC-004: a telemetry tx built by the MQTT/CoAP ingress path must carry a valid signature")
    void hc004_iotIngressTransactionsAreUnsignable() throws Exception {
        Blockchain chain = Mockito.mock(Blockchain.class);
        Mockito.when(chain.getNonce(Mockito.anyString())).thenReturn(0L);
        MQTTAdapter adapter = new MQTTAdapter(chain);

        Method handle = MQTTAdapter.class.getDeclaredMethod("handleMessage", String.class, String.class);
        handle.setAccessible(true);
        handle.invoke(adapter, "blockchain/iot/sensor-7/telemetry", "{\"deviceId\":\"sensor-7\",\"value\":21.5}");

        ArgumentCaptor<Transaction> captor = ArgumentCaptor.forClass(Transaction.class);
        Mockito.verify(chain).addTransaction(captor.capture());
        Transaction tx = captor.getValue();
        System.out.printf("[HC-004] ingress tx from=%s verify()=%s%n", tx.getFrom(), tx.verify());

        assertThat(tx.verify())
                .as("HC-004: ingress tx has from='%s' but is signed with the node key, so "
                        + "Blockchain.validateTransaction rejects it as 'Invalid signature' — "
                        + "no MQTT/CoAP telemetry can ever be accepted", tx.getFrom())
                .isTrue();
    }
}
