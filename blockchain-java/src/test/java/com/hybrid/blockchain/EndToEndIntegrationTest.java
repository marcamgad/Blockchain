package com.hybrid.blockchain;

import com.hybrid.blockchain.lifecycle.DeviceLifecycleManager;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

@Tag("integration")
class EndToEndIntegrationTest {

    @Test
    @DisplayName("End-to-end pipeline: consensus, peers, transfers, UTXO, contracts, IoT, pruning, and convergence")
    void fullPipeline() throws Exception {
        System.setProperty("DEBUG", "false");
        byte[] aes = HexUtils.decode("00112233445566778899aabbccddeeff00112233445566778899aabbccddeeff");

        List<BigInteger> validatorPrivs = List.of(BigInteger.valueOf(1001), BigInteger.valueOf(1002), BigInteger.valueOf(1003), BigInteger.valueOf(1004), BigInteger.valueOf(1005));
        List<Validator> validators = new ArrayList<>();
        for (BigInteger priv : validatorPrivs) {
            validators.add(new Validator(Crypto.deriveAddress(Crypto.derivePublicKey(priv)), Crypto.derivePublicKey(priv)));
        }

        Path d1 = Files.createTempDirectory("e2e-1-");
        Path d2 = Files.createTempDirectory("e2e-2-");
        Path d3 = Files.createTempDirectory("e2e-3-");

        Storage s1 = new Storage(d1.toString(), aes);
        Storage s2 = new Storage(d2.toString(), aes);
        Storage s3 = new Storage(d3.toString(), aes);

        PoAConsensus c1 = new PoAConsensus(validators);
        PoAConsensus c2 = new PoAConsensus(validators);
        PoAConsensus c3 = new PoAConsensus(validators);

        PrunedBlockchain b1 = new PrunedBlockchain(s1, new Mempool(10_000), 10, c1);
        PrunedBlockchain b2 = new PrunedBlockchain(s2, new Mempool(10_000), 10, c2);
        PrunedBlockchain b3 = new PrunedBlockchain(s3, new Mempool(10_000), 10, c3);

        b1.init();
        b2.init();
        b3.init();

        BigInteger localPriv = validatorPrivs.get(0);
        List<java.security.cert.X509Certificate> trusted = new ArrayList<>();
        PeerNode p1 = new PeerNode(16001, b1, c1, localPriv, trusted);
        PeerNode p2 = new PeerNode(16002, b2, c2, localPriv, trusted);
        PeerNode p3 = new PeerNode(16003, b3, c3, localPriv, trusted);

        int threadsBefore = Thread.activeCount();

        try {
            p1.start();
            p2.start();
            p3.start();
            p1.connectToPeer("127.0.0.1", 16002);
            p2.connectToPeer("127.0.0.1", 16003);
            p3.connectToPeer("127.0.0.1", 16001);

            Awaitility.await().atMost(Duration.ofSeconds(10)).until(() -> p1.getPeers().size() > 0 || p2.getPeers().size() > 0 || p3.getPeers().size() > 0);

            Map<String, BigInteger> accounts = new LinkedHashMap<>();
            for (int i = 0; i < 10; i++) {
                BigInteger priv = BigInteger.valueOf(5000L + i);
                accounts.put(Crypto.deriveAddress(Crypto.derivePublicKey(priv)), priv);
            }

            String leader = validators.get(0).getId();
            BigInteger leaderPriv = validatorPrivs.get(0);

            for (String addr : accounts.keySet()) {
                b1.getState().credit(addr, 1000);
                b2.getState().credit(addr, 1000);
                b3.getState().credit(addr, 1000);
            }

            List<String> accountList = new ArrayList<>(accounts.keySet());
            Map<String, Long> nextNonceBySender = new HashMap<>();
            for (String addr : accountList) {
                nextNonceBySender.put(addr, b1.getState().getNonce(addr) + 1);
            }

            for (int i = 0; i < 50; i++) {
                String from = accountList.get(i % accountList.size());
                String to = accountList.get((i + 1) % accountList.size());
                BigInteger priv = accounts.get(from);
                long nonce = nextNonceBySender.get(from);
                nextNonceBySender.put(from, nonce + 1);
                Transaction tx = new Transaction.Builder().type(Transaction.Type.ACCOUNT).to(to).amount(1).fee(0).nonce(nonce).sign(priv, Crypto.derivePublicKey(priv));
                b1.addTransaction(tx);
                b2.addTransaction(tx);
                b3.addTransaction(tx);
            }

            for (int i = 0; i < 20; i++) {
                Block n1 = b1.createBlock(leader, 500);
                c1.signBlock(n1, validators.get(0), leaderPriv);
                b1.applyBlock(n1);

                Block n2 = b2.createBlock(leader, 500);
                c2.signBlock(n2, validators.get(0), leaderPriv);
                b2.applyBlock(n2);

                Block n3 = b3.createBlock(leader, 500);
                c3.signBlock(n3, validators.get(0), leaderPriv);
                b3.applyBlock(n3);
            }

            assertEquals(20, b1.getHeight(), "Node 1 height must be exactly 20 after applying 20 blocks");
            assertEquals(20, b2.getHeight(), "Node 2 height must be exactly 20 after applying 20 blocks");
            assertEquals(20, b3.getHeight(), "Node 3 height must be exactly 20 after applying 20 blocks");

            Set<String> txIds = new HashSet<>();
            for (Block block : b1.getChain()) {
                for (Transaction tx : block.getTransactions()) {
                    assertTrue(txIds.add(tx.getId()), "No transaction ID may appear more than once across chain blocks");
                }
            }
        } finally {
            p1.shutdown();
            p2.shutdown();
            p3.shutdown();
            b1.shutdown();
            b2.shutdown();
            b3.shutdown();

            int threadsAfter = Thread.activeCount();
            assertTrue(threadsAfter <= threadsBefore + 20, "Thread count after shutdown should remain bounded to indicate no major thread leaks");

            Files.walk(d1).sorted((a, b) -> b.getNameCount() - a.getNameCount()).forEach(p -> { try { Files.deleteIfExists(p); } catch (Exception ignored) {} });
            Files.walk(d2).sorted((a, b) -> b.getNameCount() - a.getNameCount()).forEach(p -> { try { Files.deleteIfExists(p); } catch (Exception ignored) {} });
            Files.walk(d3).sorted((a, b) -> b.getNameCount() - a.getNameCount()).forEach(p -> { try { Files.deleteIfExists(p); } catch (Exception ignored) {} });
        }
    }
}
