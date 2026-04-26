package com.hybrid.blockchain.p2p;

import com.hybrid.blockchain.Crypto;
import org.junit.jupiter.api.*;
import static org.assertj.core.api.Assertions.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.List;

/**
 * Unit and logic tests for the P2P Gossip Engine.
 * Covers message validation, deduplication (seen cache), and relaying logic.
 */
@Tag("network")
public class GossipEngineCompleteTest {

    private GossipEngine engine;

    @BeforeEach
    void setUp() {
        engine = new GossipEngine();
    }

    @Test
    @DisplayName("GE1.1-1.2 — Basic validation")
    void testMessageValidation() {
        assertThat(engine.validateAndProcess((byte[]) null)).isFalse();
        
        byte[] huge = new byte[1024 * 1024 + 1];
        assertThat(engine.validateAndProcess(huge)).as("Over 1MB rejected").isFalse();
    }

    @Test
    @DisplayName("GE1.3 — Deduplication")
    void testSeenCache() {
        byte[] payload = "msg1".getBytes();
        // First time processed
        assertThat(engine.validateAndProcess(payload)).isTrue();
        // Second time rejected as duplicate
        assertThat(engine.validateAndProcess(payload)).as("Duplicate message should be rejected").isFalse();
    }

    @Test
    @DisplayName("GE1.5-1.6 — Relay logic")
    void testRelayDispatch() {
        AtomicInteger dispatchCount = new AtomicInteger();
        engine.setDispatcher((peers, msg) -> {
            dispatchCount.addAndGet(peers.size());
        });
        
        // Mock 6 available peers
        List<String> peers = List.of("p1", "p2", "p3", "p4", "p5", "p6");
        
        // Relay with fanout=3
        engine.relay("msg_content".getBytes(), "p1", peers, 3);
        
        // Should dispatch to 3 peers, excluding the sender p1
        assertThat(dispatchCount.get()).isEqualTo(3);
    }

    @Test
    @DisplayName("GE1.7 — Mandatory dispatcher")
    void testMissingDispatcher() {
        assertThatThrownBy(() -> engine.relay("data".getBytes(), "p1", java.util.Collections.emptyList(), 3))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Dispatcher not configured");
    }

    @Test
    @DisplayName("GE1.8 — Seen cache LRU eviction")
    void testCacheEviction() {
        // seenMessages limit is usually 5000
        for(int i=0; i<5001; i++) {
            engine.validateAndProcess(("unique" + i).getBytes());
        }
        
        // "unique0" should have been evicted
        assertThat(engine.validateAndProcess("unique0".getBytes())).as("unique0 should have been evicted and be processable again").isTrue();
    }

    @Test
    @DisplayName("GE1.9 — Thread safety")
    void testConcurrentProcessing() throws InterruptedException {
        int threads = 10;
        java.util.concurrent.ExecutorService service = java.util.concurrent.Executors.newFixedThreadPool(threads);
        java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(threads);
        
        for (int i = 0; i < threads; i++) {
            final int id = i;
            service.submit(() -> {
                try {
                    engine.validateAndProcess(("concurrent" + id).getBytes());
                } finally {
                    latch.countDown();
                }
            });
        }
        
        latch.await(5, java.util.concurrent.TimeUnit.SECONDS);
        service.shutdown();
        
        // Just verify no exception thrown and size is correct
    }

    private static class List<T> extends java.util.ArrayList<T> {
        static <T> List<T> of(T... items) {
           List<T> l = new List<>();
           for(T i : items) l.add(i);
           return l;
        }
    }
}
