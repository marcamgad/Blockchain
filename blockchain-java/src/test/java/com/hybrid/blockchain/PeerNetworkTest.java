package com.hybrid.blockchain;

import com.hybrid.blockchain.p2p.GossipEngine;
import com.hybrid.blockchain.p2p.P2PMessage;
import com.hybrid.blockchain.p2p.PeerManager;
import com.hybrid.blockchain.security.RateLimiter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.math.BigInteger;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

@Tag("unit")
class PeerNetworkTest extends TestHarness {

    private P2PMessage newMessage(String payloadText) {
        BigInteger priv = BigInteger.valueOf(1234);
        return P2PMessage.create("peer-a", priv, P2PMessage.Type.TRANSACTION, payloadText.getBytes());
    }

    @Test
    @DisplayName("GossipEngine accepts a new message and returns true")
    void gossipAcceptsNewMessage() {
        GossipEngine engine = new GossipEngine(new PeerManager(), 3);
        boolean accepted = engine.validateAndProcess(newMessage("hello"));
        assertTrue(accepted, "A first-seen valid gossip message must be accepted by validation pipeline");
    }

    @Test
    @DisplayName("GossipEngine rejects duplicate message IDs")
    void gossipRejectsDuplicateMessage() {
        GossipEngine engine = new GossipEngine(new PeerManager(), 3);
        P2PMessage message = newMessage("hello");
        engine.validateAndProcess(message);

        assertFalse(engine.validateAndProcess(message), "Duplicate message IDs must be rejected by gossip deduplication cache");
    }

    @Test
    @DisplayName("GossipEngine rejects null payload messages")
    void gossipRejectsNullPayload() throws Exception {
        P2PMessage msg = new P2PMessage("peer-a", P2PMessage.Type.TRANSACTION, "x".getBytes(), new byte[64]);
        Field payloadField = P2PMessage.class.getDeclaredField("payload");
        payloadField.setAccessible(true);
        payloadField.set(msg, null);
        GossipEngine engine = new GossipEngine(new PeerManager(), 3);

        assertFalse(engine.validateAndProcess(msg), "Null payload messages must be rejected as malformed");
    }

    @Test
    @DisplayName("GossipEngine rejects payloads exceeding 1MB")
    void gossipRejectsOversizedPayload() {
        P2PMessage msg = new P2PMessage("peer-a", P2PMessage.Type.TRANSACTION, new byte[1024 * 1024 + 1], new byte[64]);
        GossipEngine engine = new GossipEngine(new PeerManager(), 3);

        assertFalse(engine.validateAndProcess(msg), "Payloads larger than 1MB must be rejected to protect memory/DoS boundaries");
    }

    @Test
    @DisplayName("Gossip deduplication calls handler once for same message repeated 100 times")
    void gossipDedupHandlerOnlyOnce() {
        GossipEngine engine = new GossipEngine(new PeerManager(), 3);
        AtomicInteger calls = new AtomicInteger();
        engine.registerHandler(P2PMessage.Type.TRANSACTION, msg -> calls.incrementAndGet());

        P2PMessage m = newMessage("dup");
        for (int i = 0; i < 100; i++) {
            engine.validateAndProcess(m);
        }

        assertEquals(1, calls.get(), "Handler must execute exactly once for repeated duplicates with same messageId");
    }

    @Test
    @DisplayName("PeerManager selectGossipPeers excludes specified peer and returns fanout size")
    void peerManagerSelectPeers() {
        PeerManager pm = new PeerManager();
        for (int i = 0; i < 10; i++) {
            pm.addPeer("peer-" + i, "10.0.0." + i, 7000 + i);
        }

        var selected = pm.selectGossipPeers(3, "peer-0");
        assertEquals(3, selected.size(), "Peer selection must return exactly requested fanout when enough peers are available");
        assertTrue(selected.stream().noneMatch(p -> p.getId().equals("peer-0")), "Excluded peer must never appear in selected gossip fanout");
    }

    @Test
    @DisplayName("RateLimiter token bucket allows maxTokens then blocks one extra request")
    void rateLimiterTokenBucketLimit() {
        RateLimiter limiter = new RateLimiter(5, 5, 1000);
        for (int i = 0; i < 5; i++) {
            assertTrue(limiter.allowRequest("id"), "Requests within token capacity must be allowed");
        }
        assertFalse(limiter.allowRequest("id"), "Request beyond token capacity must be rejected");
    }

    @Test
    @DisplayName("RateLimiter refills after refill interval")
    void rateLimiterRefill() {
        RateLimiter limiter = new RateLimiter(1, 1, 10);
        assertTrue(limiter.allowRequest("id"), "First request should consume initial token");
        assertFalse(limiter.allowRequest("id"), "Second immediate request should be throttled");

        org.awaitility.Awaitility.await().atMost(java.time.Duration.ofSeconds(1)).until(() -> limiter.allowRequest("id"));
        assertTrue(true, "After refill interval elapses, token bucket should permit new request");
    }

    @Test
    @DisplayName("RateLimiter concurrency does not allow more than maxTokens total")
    void rateLimiterConcurrency() throws Exception {
        int maxTokens = 100;
        RateLimiter limiter = new RateLimiter(maxTokens, 0, 100000);
        AtomicInteger successes = new AtomicInteger();

        ExecutorService executor = Executors.newFixedThreadPool(10);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(10);

        for (int t = 0; t < 10; t++) {
            executor.submit(() -> {
                try {
                    start.await();
                    for (int i = 0; i < 100; i++) {
                        if (limiter.allowRequest("shared")) {
                            successes.incrementAndGet();
                        }
                    }
                } catch (InterruptedException ignored) {
                } finally {
                    done.countDown();
                }
            });
        }

        start.countDown();
        assertTrue(done.await(10, TimeUnit.SECONDS), "Concurrent rate limit test threads must complete within timeout");
        executor.shutdownNow();

        assertTrue(successes.get() <= maxTokens, "Total concurrent successes must never exceed configured token bucket capacity");
    }

    @Test
    @DisplayName("RateLimiter cleanup removes inactive buckets")
    void rateLimiterCleanup() throws Exception {
        RateLimiter limiter = new RateLimiter(2, 1, 1000);
        for (int i = 0; i < 1000; i++) {
            limiter.allowRequest("id-" + i);
        }

        org.awaitility.Awaitility.await().atMost(java.time.Duration.ofSeconds(1)).until(() -> {
            limiter.cleanup(1);
            Field bucketsField = RateLimiter.class.getDeclaredField("buckets");
            bucketsField.setAccessible(true);
            Map<?, ?> buckets = (Map<?, ?>) bucketsField.get(limiter);
            return buckets.size() < 50;
        });

        Field bucketsField = RateLimiter.class.getDeclaredField("buckets");
        bucketsField.setAccessible(true);
        Map<?, ?> buckets = (Map<?, ?>) bucketsField.get(limiter);
        assertTrue(buckets.size() < 50, "Cleanup with tiny threshold should evict nearly all inactive buckets");
    }

    @Test
    @DisplayName("Blockchain.addTransaction enforces per-address rate limiting")
    void blockchainAddTransactionRateLimitIntegration() throws Exception {
        initPoABlockchain(defaultValidators());
        BigInteger priv = privateKey(9090);
        byte[] pub = Crypto.derivePublicKey(priv);
        String from = Crypto.deriveAddress(pub);
        blockchain.getState().credit(from, 10_000);

        int accepted = 0;
        int rejected = 0;
        for (int i = 0; i < 200; i++) {
            Transaction tx = new Transaction.Builder().type(Transaction.Type.ACCOUNT).to("hb-r").amount(1).fee(0).nonce(i + 1).sign(priv, pub);
            try {
                blockchain.addTransaction(tx);
                accepted++;
            } catch (Exception e) {
                if (e.getMessage().toLowerCase().contains("rate limit")) {
                    rejected++;
                }
            }
        }

        assertTrue(accepted > 0, "At least some transactions should be accepted before rate limiter is exhausted");
        assertTrue(rejected > 0, "Rate limiter integration must eventually reject over-limit transactions with explicit rate-limit errors");
    }
}
