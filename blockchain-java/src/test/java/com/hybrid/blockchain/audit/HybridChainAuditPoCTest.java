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

    // ═════════════════════════════════════════════════════════════════════════
    // BATCH 2 — LEDGER (Blockchain.java). Real Blockchain + real PBFTConsensus, no mocks.
    // Blocks are built by LedgerPoCFixture.build(), which computes the state root exactly
    // as an honest re-executing node does, so every block below is one a single
    // signing proposer can get accepted by every honest node.
    // ═════════════════════════════════════════════════════════════════════════

    private static Transaction mintN(String to, long amount, long nonce) {
        return new Transaction.Builder().type(Transaction.Type.MINT).to(to).amount(amount).nonce(nonce).build();
    }

    @Test
    @DisplayName("HC-016: a block must not be able to carry more than one MINT")
    void hc016_multipleMintsPerBlock() throws Exception {
        try (LedgerPoCFixture f = new LedgerPoCFixture()) {
            long reward = f.scheduledReward();
            String attacker = new TestKeyPair(7701).getAddress();
            List<Transaction> txs = new ArrayList<>();
            for (int i = 0; i < 10; i++) txs.add(mintN(attacker, reward, i));
            boolean accepted;
            try { f.commitAndApply(f.build(txs, 0)); accepted = true; }
            catch (Exception e) { accepted = false; System.out.println("[HC-016] rejected: " + e.getMessage()); }
            long bal = f.chain.getState().getBalance(attacker);
            System.out.printf("[HC-016] reward=%d accepted=%s attackerBalance=%d totalMinted=%d%n",
                    reward, accepted, bal, f.chain.getTotalMinted());
            assertThat(bal).as("HC-016: one block minted %d (= %dx the scheduled reward %d) to one address",
                    bal, reward == 0 ? 0 : bal / reward, reward).isLessThanOrEqualTo(reward);
        }
    }

    @Test
    @DisplayName("HC-016b: an externally submitted MINT must never be included by an HONEST proposer")
    void hc016b_honestProposerIncludesForeignMint() throws Exception {
        try (LedgerPoCFixture f = new LedgerPoCFixture()) {
            String attacker = new TestKeyPair(7721).getAddress();
            long reward = f.scheduledReward();
            String admit;
            try { f.chain.addTransaction(mintN(attacker, reward, 42)); admit = "ACCEPTED into mempool"; }
            catch (Exception e) { admit = "rejected: " + e.getMessage(); }
            // Honest block production exactly as App.java:130/233 does it.
            com.hybrid.blockchain.Block b = f.chain.createBlock(LedgerPoCFixture.VALIDATORS[0].getAddress(), 100);
            b.setValidatorId(LedgerPoCFixture.VALIDATORS[0].getAddress());
            b.setHash(b.calculateHash());
            b.setSignature(Crypto.sign(Crypto.hash(b.serializeCanonical()), LedgerPoCFixture.VALIDATORS[0].getPrivateKey()));
            long mints = b.getTransactions().stream().filter(t -> t.getType() == Transaction.Type.MINT).count();
            String err = null;
            try { f.commitAndApply(b); } catch (Exception e) { err = e.getMessage(); }
            System.out.printf("[HC-016b] foreign MINT %s; honest block has %d MINT txs; apply=%s; attacker balance=%d%n",
                    admit, mints, err == null ? "ok" : err, f.chain.getState().getBalance(attacker));
            assertThat(f.chain.getState().getBalance(attacker))
                    .as("HC-016b: any mempool submitter receives a block reward").isZero();
        }
    }

    @Test
    @DisplayName("HC-017: fees of transactions that FAIL during apply must not be credited (supply conservation)")
    void hc017_failedTxFeeCreditedToProposer() throws Exception {
        try (LedgerPoCFixture f = new LedgerPoCFixture()) {
            TestKeyPair sender = new TestKeyPair(7702);
            long r = f.fund(sender.getAddress());
            long half = r / 2;
            String sink = new TestKeyPair(7703).getAddress();
            // Both validate against the PRE-block balance r (validation is not cumulative);
            // tx#2 cannot be applied once tx#1 has spent the balance.
            Transaction t1 = LedgerPoCFixture.transfer(sender, sink, half, half, 1);
            Transaction t2 = LedgerPoCFixture.transfer(sender, sink, half, half, 2);
            long before = LedgerPoCFixture.totalSupply(f.chain);
            String err = null;
            try { f.commitAndApply(f.build(List.of(t1, t2), 0)); } catch (Exception e) { err = e.getMessage(); }
            long after = LedgerPoCFixture.totalSupply(f.chain);
            System.out.printf("[HC-017] block %s; supply before=%d after=%d (created from nothing: %d)%n",
                    err == null ? "accepted" : "rejected (" + err + ")", before, after, after - before);
            assertThat(after).as("HC-017: total supply changed with no MINT in the block").isEqualTo(before);
        }
    }

    @Test
    @DisplayName("HC-018: an UNSIGNED UTXO tx with a negative output must be rejected (mempool and block)")
    void hc018_unsignedUtxoNegativeOutputPrintsMoney() throws Exception {
        try (LedgerPoCFixture f = new LedgerPoCFixture()) {
            String attacker = new TestKeyPair(7704).getAddress();
            String victim = new TestKeyPair(7705).getAddress();
            long amt = 1_000_000_000L;
            Transaction forged = new Transaction.Builder().type(Transaction.Type.UTXO)
                    .addOutput(victim, -amt).addOutput(attacker, amt).build();   // from=null: no signature at all
            boolean mempoolAccepted;
            try { f.chain.addTransaction(forged); mempoolAccepted = true; }
            catch (Exception e) { mempoolAccepted = false; System.out.println("[HC-018] mempool rejected: " + e.getMessage()); }
            boolean blockAccepted;
            try { f.commitAndApply(f.build(List.of(forged), 0)); blockAccepted = true; }
            catch (Exception e) { blockAccepted = false; System.out.println("[HC-018] block rejected: " + e.getMessage()); }
            System.out.printf("[HC-018] mempoolAccepted=%s blockAccepted=%s attacker getBalance=%d utxoTotal=%d%n",
                    mempoolAccepted, blockAccepted, f.chain.getBalance(attacker), LedgerPoCFixture.utxoTotal(f.chain));
            assertThat(mempoolAccepted).as("HC-018: unsigned UTXO tx admitted to the mempool (gossip/REST ingress)").isFalse();
            assertThat(f.chain.getBalance(attacker)).as("HC-018: attacker spendable balance created from nothing").isZero();
        }
    }

    @Test
    @DisplayName("HC-019: node-local equivocation evidence must not change the state transition")
    void hc019_localSlashingDivergesStateRoot() throws Exception {
        try (LedgerPoCFixture f = new LedgerPoCFixture()) {
            TestKeyPair v2 = LedgerPoCFixture.VALIDATORS[1];
            f.commitAndApply(f.build(List.of(mintN(v2.getAddress(), f.scheduledReward(), 0)), 0));
            // Block 2 as built (and validated) by every node that did NOT see v2 equivocate.
            com.hybrid.blockchain.Block b2 = f.build(List.of(), 0);
            // This node alone receives two conflicting PREPAREs from v2 for some sequence.
            long seq = 99;
            for (String h : List.of("aa", "bb")) {
                PBFTConsensus.PBFTMessage m = new PBFTConsensus.PBFTMessage(
                        PBFTConsensus.Phase.PREPARE, f.consensus.getViewNumber(), seq, h, v2.getAddress());
                m.sign(v2.getPrivateKey());
                f.consensus.addPrepareVote(seq, h, v2.getAddress(), m.signature);
            }
            System.out.println("[HC-019] locally slashed set = " + f.consensus.getSlashedValidators());
            String err = null;
            try { f.commitAndApply(b2); } catch (Exception e) { err = e.getMessage(); }
            System.out.println("[HC-019] apply of the network's committed block 2: "
                    + (err == null ? "accepted" : "REJECTED: " + err));
            assertThat(err).as("HC-019: this node rejects a committed block every other node accepts -> fork/halt").isNull();
        }
    }

    @Test
    @DisplayName("HC-020: two independently initialised nodes must share one genesis block")
    void hc020_genesisDependsOnWallClock() throws Exception {
        try (LedgerPoCFixture a = new LedgerPoCFixture()) {
            Thread.sleep(25);
            try (LedgerPoCFixture b = new LedgerPoCFixture()) {
                String ga = a.chain.getChain().get(0).getHash(), gb = b.chain.getChain().get(0).getHash();
                String err = null;
                try {
                    b.commitAndApply(a.build(List.of(mintN(new TestKeyPair(7706).getAddress(), a.scheduledReward(), 0)), 0));
                } catch (Exception e) { err = e.getMessage(); }
                System.out.printf("[HC-020] genesis A=%s B=%s; B applying A's block 1: %s%n", ga, gb, err == null ? "ok" : err);
                assertThat(gb).as("HC-020: genesis hash depends on System.currentTimeMillis() at init").isEqualTo(ga);
            }
        }
    }

    @Test
    @DisplayName("HC-021: an invalid same-height block must not revert the current tip")
    void hc021_forkPathRevertsBeforeValidating() throws Exception {
        try (LedgerPoCFixture f = new LedgerPoCFixture()) {
            String to = new TestKeyPair(7707).getAddress();
            f.commitAndApply(f.build(List.of(mintN(to, f.scheduledReward(), 0)), 0));
            f.commitAndApply(f.build(List.of(mintN(to, f.scheduledReward(), 1)), 0));
            com.hybrid.blockchain.Block tip = f.chain.getLatestBlock();
            com.hybrid.blockchain.Block junk = new com.hybrid.blockchain.Block(tip.getIndex(), tip.getTimestamp(),
                    new ArrayList<>(), tip.getPrevHash(), 0, "00");
            junk.setHash("z".repeat(64));             // any string that sorts above the real tip hash
            junk.setValidatorId("not-a-validator");   // no signature, unknown validator, wrong hash
            String err = null;
            try { f.chain.applyBlock(junk); } catch (Exception e) { err = e.getMessage(); }
            System.out.printf("[HC-021] before: height=%d hash=%s | after junk: height=%d hash=%s | error=%s%n",
                    tip.getIndex(), tip.getHash(), f.chain.getHeight(), f.chain.getLatestBlock().getHash(), err);
            assertThat(f.chain.getLatestBlock().getHash())
                    .as("HC-021: committed tip was reverted by an unvalidated block").isEqualTo(tip.getHash());
        }
    }

    @Test
    @DisplayName("HC-025: a valid pending ACCOUNT transfer must be included by the block producer")
    void hc025_createBlockNeverIncludesTransfers() throws Exception {
        try (LedgerPoCFixture f = new LedgerPoCFixture()) {
            TestKeyPair sender = new TestKeyPair(7710);
            f.commitAndApply(f.build(List.of(mintN(sender.getAddress(), f.scheduledReward(), 0)), 0));
            Transaction t = LedgerPoCFixture.transfer(sender, new TestKeyPair(7711).getAddress(), 10, 1, 1);
            f.chain.addTransaction(t);
            com.hybrid.blockchain.Block b = f.chain.createBlock(LedgerPoCFixture.VALIDATORS[0].getAddress(), 100);
            boolean included = b.getTransactions().stream().anyMatch(x -> x.getId().equals(t.getId()));
            String why = null;
            try { f.chain.validateTransaction(t); } catch (Exception e) { why = e.getMessage(); }
            System.out.printf("[HC-025] mempool=%d blockTxs=%d includedTransfer=%s; validateTransaction(pending tx) -> %s%n",
                    f.chain.getMempool().size(), b.getTransactions().size(), included, why);
            assertThat(included).as("HC-025: createBlock dropped a valid pending transfer").isTrue();
        }
    }

    @Test
    @DisplayName("HC-026: the TELEMETRY state transition must not depend on node-local detector history")
    void hc026_telemetryApplyDependsOnProcessLocalHistory() throws Exception {
        try (LedgerPoCFixture f = new LedgerPoCFixture()) {
            com.hybrid.blockchain.ai.TelemetryAnomalyDetector det =
                    com.hybrid.blockchain.ai.TelemetryAnomalyDetector.getInstance();
            TestKeyPair dev = new TestKeyPair(7715);
            Transaction tx = new Transaction.Builder().type(Transaction.Type.TELEMETRY).from(dev.getAddress())
                    .to(dev.getAddress()).fee(1000).nonce(1).data("500.0".getBytes(StandardCharsets.UTF_8))
                    .sign(dev.getPrivateKey(), dev.getPublicKey());
            String[] roots = new String[2];
            for (int run = 0; run < 2; run++) {
                det.clearDevice(dev.getAddress());
                // run 1 = a node that has seen 20 earlier readings from this device (e.g. it was
                // up longer, or its REST/MQTT ingress observed them); run 0 = a freshly restarted node.
                if (run == 1) for (int i = 0; i < 20; i++) det.check(dev.getAddress(), 10.0 + (i % 3), 0L);
                com.hybrid.blockchain.AccountState s = f.chain.getState().cloneState();
                s.credit(dev.getAddress(), 1_000_000);
                LedgerPoCFixture.applyTx(f.chain, s, com.hybrid.blockchain.UTXOSet.fromMap(new java.util.HashMap<>()),
                        tx, 1, 1L, "H");
                roots[run] = s.calculateStateRoot();
                System.out.printf("[HC-026] node %s: device balance after the SAME tx = %d, stateRoot=%s%n",
                        run == 0 ? "fresh" : "warm ", s.getBalance(dev.getAddress()), roots[run]);
            }
            det.clearDevice(dev.getAddress());
            assertThat(roots[1]).as("HC-026: identical tx + identical pre-state -> different post-state").isEqualTo(roots[0]);
        }
    }

    @Test
    @DisplayName("HC-027: after a restart the mempool must judge nonce gaps against the LIVE state")
    void hc027_mempoolStateRefGoesStale() throws Exception {
        try (LedgerPoCFixture f = new LedgerPoCFixture()) {
            TestKeyPair s = new TestKeyPair(7716);
            String sink = new TestKeyPair(7717).getAddress();
            for (int m = 0; m < 5; m++) f.commitAndApply(f.build(List.of(mintN(s.getAddress(), f.scheduledReward(), m)), 0));
            List<Transaction> txs = new ArrayList<>();
            for (int n = 1; n <= com.hybrid.blockchain.Config.MAX_NONCE_GAP + 1; n++)
                txs.add(LedgerPoCFixture.transfer(s, sink, 0, 2, n));
            f.commitAndApply(f.build(txs, 0));
            Blockchain restarted = new Blockchain(f.storage, new com.hybrid.blockchain.Mempool(1000), f.consensus);
            restarted.init();
            long next = restarted.getNonce(s.getAddress()) + 1;
            String err = null;
            try { restarted.addTransaction(LedgerPoCFixture.transfer(s, sink, 0, 2, next)); }
            catch (Exception e) { err = e.getMessage(); }
            System.out.printf("[HC-027] live nonce=%d, submitting nonce %d after restart -> %s%n",
                    restarted.getNonce(s.getAddress()), next, err == null ? "accepted" : "REJECTED: " + err);
            assertThat(err).as("HC-027: correctly-nonced tx rejected by a stale mempool stateRef").isNull();
        }
    }

    @Test
    @DisplayName("HC-028: the state root must commit to every consensus-relevant account field")
    void hc028_stateRootOmitsTokenBalancesAndCommitments() {
        String a = new TestKeyPair(7718).getAddress();
        com.hybrid.blockchain.AccountState s1 = new com.hybrid.blockchain.AccountState();
        s1.credit(a, 100);
        com.hybrid.blockchain.AccountState s2 = s1.cloneState();
        s2.creditToken(a, "USDX", 1_000_000_000L);            // custom-token balance
        com.hybrid.blockchain.AccountState s3 = s1.cloneState();
        s3.setCommitment(a, new byte[]{1, 2, 3});                // ZK ownership commitment
        System.out.printf("[HC-028] root(base)=%s%n[HC-028] root(+1e9 USDX)=%s%n[HC-028] root(+commitment)=%s%n",
                s1.calculateStateRoot(), s2.calculateStateRoot(), s3.calculateStateRoot());
        assertThat(s2.calculateStateRoot()).as("HC-028: token balances are not part of the state root")
                .isNotEqualTo(s1.calculateStateRoot());
        assertThat(s3.calculateStateRoot()).as("HC-028: ZK commitments are not part of the state root")
                .isNotEqualTo(s1.calculateStateRoot());
    }

    @Test
    @DisplayName("HC-029: a tx id must be derived from content, never taken from the wire")
    void hc029_peerSuppliedTxidIsTrusted() throws Exception {
        try (LedgerPoCFixture f = new LedgerPoCFixture()) {
            TestKeyPair s = new TestKeyPair(7719);
            Transaction honest = LedgerPoCFixture.transfer(s, new TestKeyPair(7720).getAddress(), 5, 1, 1);
            com.fasterxml.jackson.databind.node.ObjectNode json = (com.fasterxml.jackson.databind.node.ObjectNode)
                    new com.fasterxml.jackson.databind.ObjectMapper().readTree(f.chain.serializeTransaction(honest));
            String victimId = "00".repeat(32);
            json.put("txid", victimId);
            Transaction wire = f.chain.deserializeTransaction(json.toString().getBytes(StandardCharsets.UTF_8));
            String recomputed = Crypto.bytesToHex(Crypto.hash(wire.serializeCanonical()));
            System.out.printf("[HC-029] content hash=%s, getId() after deserialize=%s, verify()=%s%n",
                    recomputed, wire.getId(), wire.verify());
            assertThat(wire.getId()).as("HC-029: txid taken from attacker JSON").isEqualTo(recomputed);
        }
    }

    @Test
    @DisplayName("HC-030: two different transaction lists must never share a block hash")
    void hc030_merkleDuplicateLeafMalleability() {
        Transaction a = mintN(new TestKeyPair(7722).getAddress(), 50, 0);
        Transaction b = new Transaction.Builder().type(Transaction.Type.UTXO).addOutput("x", 0).nonce(1).build();
        Transaction c = new Transaction.Builder().type(Transaction.Type.UTXO).addOutput("y", 0).nonce(2).build();
        com.hybrid.blockchain.Block b3 = new com.hybrid.blockchain.Block(1, 1000L, new ArrayList<>(List.of(a, b, c)), "00".repeat(32), 1, "00".repeat(32));
        com.hybrid.blockchain.Block b4 = new com.hybrid.blockchain.Block(1, 1000L, new ArrayList<>(List.of(a, b, c, c)), "00".repeat(32), 1, "00".repeat(32));
        System.out.printf("[HC-030] 3 txs: txRoot=%s hash=%s%n[HC-030] 4 txs (last duplicated): txRoot=%s hash=%s%n",
                b3.getTxRoot(), b3.getHash(), b4.getTxRoot(), b4.getHash());
        assertThat(b4.getHash()).as("HC-030: MerkleTree duplicates the odd leaf (CVE-2012-2459 pattern)")
                .isNotEqualTo(b3.getHash());
    }

    @Test
    @DisplayName("HC-031: PrunedBlockchain must actually delete pruned blocks (and storage must stay bounded)")
    void hc031_pruningDeletesNothing() throws Exception {
        try (LedgerPoCFixture f = new LedgerPoCFixture(2)) {
            String to = new TestKeyPair(7724).getAddress();
            List<String> hashes = new ArrayList<>();
            for (int i = 0; i < 6; i++) {
                com.hybrid.blockchain.Block b = f.build(List.of(mintN(to, f.scheduledReward(), i)), 0);
                f.commitAndApply(b);
                hashes.add(b.getHash());
            }
            boolean oldBlockStillStored = f.storage.loadBlockByHash(hashes.get(0)) != null;
            boolean heightIndexGone = f.storage.loadBlockByHeight(1) == null;
            int snapshots = 0;
            for (int h = 0; h <= 6; h++) if (f.storage.get("snapshot:" + h, java.util.Map.class) != null) snapshots++;
            System.out.printf("[HC-031] maxBlocks=2 after 6 blocks: block#1 body still on disk=%s, height index for #1 deleted=%s, "
                    + "full-state snapshots kept=%d%n", oldBlockStillStored, heightIndexGone, snapshots);
            assertThat(oldBlockStillStored).as("HC-031: prune deletes 'block:'+hash but blocks live under 'block:'+H('block:'+hash)")
                    .isFalse();
        }
    }

    @Test
    @DisplayName("HC-023: a node restarted from its own storage must restore totalMinted")
    void hc023_restartLosesTotalMinted() throws Exception {
        try (LedgerPoCFixture f = new LedgerPoCFixture()) {
            String to = new TestKeyPair(7712).getAddress();
            f.commitAndApply(f.build(List.of(mintN(to, f.scheduledReward(), 0)), 0));
            f.commitAndApply(f.build(List.of(mintN(to, f.scheduledReward(), 1)), 0));
            Blockchain restarted = new Blockchain(f.storage, new com.hybrid.blockchain.Mempool(10), f.consensus);
            restarted.init();
            System.out.printf("[HC-023] before restart: height=%d totalMinted=%d | after restart: height=%d "
                            + "totalMinted=%d chainInMemory=%d blocks%n", f.chain.getHeight(), f.chain.getTotalMinted(),
                    restarted.getHeight(), restarted.getTotalMinted(), restarted.getChain().size());
            assertThat(restarted.getTotalMinted()).as("HC-023: snapshot init path never restores totalMinted")
                    .isEqualTo(f.chain.getTotalMinted());
        }
    }

    @Test
    @DisplayName("HC-024: a REJECTED block must leave no receipts / tx-index entries behind")
    void hc024_rejectedBlockPersistsReceipts() throws Exception {
        try (LedgerPoCFixture f = new LedgerPoCFixture()) {
            TestKeyPair sender = new TestKeyPair(7713);
            f.commitAndApply(f.build(List.of(mintN(sender.getAddress(), f.scheduledReward(), 0)), 0));
            Transaction t = LedgerPoCFixture.transfer(sender, new TestKeyPair(7714).getAddress(), 10, 1, 1);
            com.hybrid.blockchain.Block good = f.build(List.of(t), 0);
            com.hybrid.blockchain.Block bad = new com.hybrid.blockchain.Block(good.getIndex(), good.getTimestamp(),
                    good.getTransactions(), good.getPrevHash(), good.getDifficulty(), "ab".repeat(32)); // wrong root
            bad.setValidatorId(good.getValidatorId());
            bad.setSignature(Crypto.sign(Crypto.hash(bad.serializeCanonical()),
                    LedgerPoCFixture.VALIDATORS[0].getPrivateKey()));
            String err = null;
            try { f.commitAndApply(bad); } catch (Exception e) { err = e.getMessage(); }
            com.hybrid.blockchain.TransactionReceipt rc = f.storage.loadReceipt(t.getId());
            System.out.printf("[HC-024] block rejected=%s (%s); receipt=%s status=%s blockHash=%s; tip height=%d%n",
                    err != null, err, rc != null, rc == null ? null : rc.getStatus(),
                    rc == null ? null : rc.getBlockHash(), f.chain.getHeight());
            assertThat(err).isNotNull();
            assertThat(rc).as("HC-024: a SUCCESS receipt exists for a tx whose block was rejected").isNull();
        }
    }

    @Test
    @DisplayName("HC-024b: a REJECTED block must not register tokens (token-id squatting)")
    void hc024b_rejectedBlockRegistersToken() throws Exception {
        try (LedgerPoCFixture f = new LedgerPoCFixture()) {
            TestKeyPair squatter = new TestKeyPair(7723);
            f.commitAndApply(f.build(List.of(mintN(squatter.getAddress(), f.scheduledReward(), 0)), 0));
            Transaction reg = com.hybrid.blockchain.testutil.TestTransactionFactory
                    .createTokenRegister(squatter, "USDX", "US Dollar X", "USDX", 1_000_000, 1, 1);
            com.hybrid.blockchain.Block good = f.build(List.of(reg), 0);
            com.hybrid.blockchain.Block bad = new com.hybrid.blockchain.Block(good.getIndex(), good.getTimestamp(),
                    good.getTransactions(), good.getPrevHash(), good.getDifficulty(), "cd".repeat(32));
            bad.setValidatorId(good.getValidatorId());
            bad.setSignature(Crypto.sign(Crypto.hash(bad.serializeCanonical()),
                    LedgerPoCFixture.VALIDATORS[0].getPrivateKey()));
            String err = null;
            try { f.commitAndApply(bad); } catch (Exception e) { err = e.getMessage(); }
            boolean exists = f.chain.getTokenRegistry().tokenExists("USDX");
            System.out.printf("[HC-024b] block rejected=%s; tip height=%d; token USDX registered=%s owner=%s%n",
                    err != null, f.chain.getHeight(), exists,
                    exists ? f.chain.getTokenRegistry().getTokenInfo("USDX").owner : null);
            assertThat(err).isNotNull();
            assertThat(exists).as("HC-024b: token registered by a block that was rejected").isFalse();
        }
    }

    @Test
    @DisplayName("HC-022: after fork resolution totalMinted must equal the MINTs on the canonical chain")
    void hc022_revertTipLeavesTotalMintedStale() throws Exception {
        try (LedgerPoCFixture f = new LedgerPoCFixture()) {
            String to = new TestKeyPair(7708).getAddress();
            f.commitAndApply(f.build(List.of(mintN(to, f.scheduledReward(), 0)), 0));
            long r2 = f.scheduledReward();
            long ts = f.chain.getLatestBlock().getTimestamp();
            com.hybrid.blockchain.Block a = LedgerPoCFixture.build(f.chain, List.of(mintN(to, r2, 1)), 0, ts + 1);
            com.hybrid.blockchain.Block b = LedgerPoCFixture.build(f.chain, List.of(mintN(to, r2, 2)), 0, ts + 2);
            com.hybrid.blockchain.Block low = a.getHash().compareTo(b.getHash()) < 0 ? a : b;
            com.hybrid.blockchain.Block high = low == a ? b : a;
            long baseFeeAtParent = f.chain.getCurrentBaseFee();
            // what a node that only ever saw `high` holds after applying it (1 tx in the block):
            long expectedBaseFee = f.chain.getFeeMarket().calculateNextBaseFee(baseFeeAtParent, 1,
                    com.hybrid.blockchain.Config.TARGET_GAS_PER_BLOCK);
            f.commitAndApply(low);
            f.commitAndApply(high);                    // same height, higher hash -> handleFork -> revertTip
            System.out.printf("[HC-022] baseFee parent=%d, after fork on this node=%d, on a node that never forked=%d%n",
                    baseFeeAtParent, f.chain.getCurrentBaseFee(), expectedBaseFee);
            long stateSupply = LedgerPoCFixture.totalSupply(f.chain);
            System.out.printf("[HC-022] tip=%s (expected %s) totalMinted=%d stateSupply=%d nextReward=%d%n",
                    f.chain.getLatestBlock().getHash(), high.getHash(), f.chain.getTotalMinted(), stateSupply,
                    f.scheduledReward());
            assertThat(f.chain.getLatestBlock().getHash()).isEqualTo(high.getHash());
            assertThat(f.chain.getTotalMinted()).as("HC-022: totalMinted still counts the reverted block's MINT; "
                    + "this node's next scheduled reward differs from a node that never saw the fork")
                    .isEqualTo(stateSupply);
        }
    }

    @Test
    @DisplayName("HC-022b: after fork resolution the consensus-critical baseFee must equal a non-forked node's")
    void hc022b_revertTipLeavesBaseFeeStale() throws Exception {
        try (LedgerPoCFixture f = new LedgerPoCFixture()) {
            // Blocks above the fee-market target (TARGET_GAS_PER_BLOCK txs) move baseFee off its floor.
            TestKeyPair s = new TestKeyPair(7725);
            for (int m = 0; m < 45; m++) f.commitAndApply(f.build(List.of(mintN(s.getAddress(), f.scheduledReward(), m)), 0));
            int n = com.hybrid.blockchain.Config.TARGET_GAS_PER_BLOCK + 1;
            long fee = f.chain.getCurrentBaseFee() + 1;
            List<Transaction> ta = new ArrayList<>(), tb = new ArrayList<>();
            for (int k = 1; k <= n; k++) {
                ta.add(LedgerPoCFixture.transfer(s, "sinkA", 0, fee, k));
                tb.add(LedgerPoCFixture.transfer(s, "sinkB", 0, fee, k));
            }
            long ts = f.chain.getLatestBlock().getTimestamp();
            com.hybrid.blockchain.Block a = LedgerPoCFixture.build(f.chain, ta, 0, ts + 1);
            com.hybrid.blockchain.Block b = LedgerPoCFixture.build(f.chain, tb, 0, ts + 2);
            com.hybrid.blockchain.Block low = a.getHash().compareTo(b.getHash()) < 0 ? a : b;
            com.hybrid.blockchain.Block high = low == a ? b : a;
            long parent = f.chain.getCurrentBaseFee();
            long expected = f.chain.getFeeMarket().calculateNextBaseFee(parent, n,
                    com.hybrid.blockchain.Config.TARGET_GAS_PER_BLOCK);
            f.commitAndApply(low);
            f.commitAndApply(high);
            assertThat(f.chain.getLatestBlock().getHash()).isEqualTo(high.getHash());
            System.out.printf("[HC-022b] baseFee parent=%d forkedNode=%d nonForkedNode=%d%n",
                    parent, f.chain.getCurrentBaseFee(), expected);
            assertThat(f.chain.getCurrentBaseFee()).as("HC-022b: baseFee advanced twice across the fork; "
                    + "blocks with fee == baseFee are valid on one node and invalid on the other").isEqualTo(expected);
        }
    }

    // ═════════════════════════════════════════════════════════════════════════
    // BATCH 2 — CRYPTO / ZK / STORAGE
    // ═════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("HC-040: an ownership-transfer signature must bind the NEW owner")
    void hc040_rawMessageSigningCoversOnly32Bytes() {
        TestKeyPair owner = new TestKeyPair(7801);
        com.hybrid.blockchain.identity.SSIManager ssi = new com.hybrid.blockchain.identity.SSIManager();
        String did = ssi.registerDID("thermostat-0001", owner.getPublicKey(), owner.getAddress());
        // (1) The owner signs the transfer to alice exactly as a wallet would.
        byte[] sig = Crypto.sign(com.hybrid.blockchain.identity.SSIManager.transferOwnershipDigest(did, "alice"),
                owner.getPrivateKey());
        String err = null;
        try { ssi.transferOwnership(did, "mallory", sig); } catch (Exception e) { err = e.getMessage(); }
        // (2) A long message must never verify (truncation) — no API may sign/verify a non-digest.
        byte[] longMsg = ("transfer:" + did + ":" + "alice").getBytes(StandardCharsets.UTF_8);
        boolean longVerifies = Crypto.verify(longMsg, sig, owner.getPublicKey());
        System.out.printf("[HC-040] alice-signature replayed for mallory -> %s; verify(%d-byte raw message)=%s%n",
                err == null ? "ACCEPTED" : "rejected (" + err + ")", longMsg.length, longVerifies);
        assertThat(err).as("HC-040: signature for 'alice' moved ownership to 'mallory'").isNotNull();
        assertThat(ssi.resolveDID(did).getController()).isNotEqualTo("mallory");
        assertThat(longVerifies).as("HC-040: a non-digest message verified").isFalse();
    }

    @Test
    @DisplayName("HC-041: an encrypted value must not decrypt successfully under a different storage key")
    void hc041_storageCiphertextNotBoundToKey() throws Exception {
        java.nio.file.Path dir = java.nio.file.Files.createTempDirectory("audit-storage-");
        com.hybrid.blockchain.Storage st = new com.hybrid.blockchain.Storage(dir.toString(),
                com.hybrid.blockchain.HexUtils.decode("00112233445566778899001122334455"));
        try {
            st.putMeta("totalMinted", 1_000L);
            st.putMeta("baseFee", 999_999L);
            java.lang.reflect.Field f = com.hybrid.blockchain.Storage.class.getDeclaredField("db");
            f.setAccessible(true);
            org.iq80.leveldb.DB db = (org.iq80.leveldb.DB) f.get(st);
            byte[] fromKey = "meta:baseFee".getBytes(StandardCharsets.UTF_8);
            byte[] toKey = "meta:totalMinted".getBytes(StandardCharsets.UTF_8);
            db.put(toKey, db.get(fromKey));     // offline copy of one record over another, no key material used
            st.close();
            com.hybrid.blockchain.Storage reopened = new com.hybrid.blockchain.Storage(dir.toString(),
                    com.hybrid.blockchain.HexUtils.decode("00112233445566778899001122334455"));
            Object v;
            try { v = reopened.getMeta("totalMinted"); } finally { reopened.close(); }
            boolean plaintextKeys = new String(fromKey, StandardCharsets.UTF_8).startsWith("meta:");
            System.out.printf("[HC-041] after moving the 'meta:baseFee' record onto 'meta:totalMinted': totalMinted=%s "
                    + "(no authentication error); keys stored in plaintext=%s%n", v, plaintextKeys);
            assertThat(v).as("HC-041: AES-GCM record accepted under a different key (no AAD binding)").isNotEqualTo(999_999L);
        } finally {
            org.apache.commons.io.FileUtils.deleteDirectory(dir.toFile());
        }
    }

    @Test
    @DisplayName("HC-042: a handshake signature must not be valid for the raw peer-chosen 32-byte value")
    void hc042_handshakeIsNotASigningOracle() throws Exception {
        TestKeyPair node = new TestKeyPair(7802);
        // A peer-chosen "nonce" that happens to be a transaction signing digest.
        byte[] chosen = Crypto.hash("TX  any 32-byte protocol digest".getBytes(StandardCharsets.UTF_8));
        Method m = com.hybrid.blockchain.PeerNode.class.getDeclaredMethod("handshakeDigest", byte[].class, String.class, int.class);
        m.setAccessible(true);
        byte[] digest = (byte[]) m.invoke(null, chosen, node.getAddress(), com.hybrid.blockchain.Config.NETWORK_ID);
        byte[] handshakeSig = Crypto.sign(digest, node.getPrivateKey());   // what the node now returns to the peer
        boolean reusable = Crypto.verify(chosen, handshakeSig, node.getPublicKey());
        System.out.printf("[HC-042] handshake signature valid for the peer-chosen digest itself = %s%n", reusable);
        assertThat(reusable).as("HC-042: handshake acts as a signing oracle for 32-byte digests").isFalse();
    }
}
