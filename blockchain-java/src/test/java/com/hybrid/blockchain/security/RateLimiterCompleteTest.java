package com.hybrid.blockchain.security;

import org.junit.jupiter.api.*;
import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.*;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Unit tests for the custom Token Bucket Rate Limiter.
 */
@Tag("security")
public class RateLimiterCompleteTest {

    @Test
    @DisplayName("RL1.1-1.2 — Basic token bucket behavior")
    void testBasicRateLimit() {
        RateLimiter limiter = new RateLimiter(5, 1, 1000); // 5 tokens, 1s interval
        
        for (int i = 0; i < 5; i++) {
            assertThat(limiter.allowRequest("A")).isTrue();
        }
        assertThat(limiter.allowRequest("A")).as("6th request should be blocked").isFalse();
    }

    @Test
    @DisplayName("RL1.3 — Token refill")
    void testRefill() {
        RateLimiter limiter = new RateLimiter(1, 1, 50); // 1 token, 50ms refill
        
        assertThat(limiter.allowRequest("B")).isTrue();
        assertThat(limiter.allowRequest("B")).isFalse();
        
        await().atMost(Duration.ofMillis(200)).until(() -> limiter.allowRequest("B"));
    }

    @Test
    @DisplayName("RL1.4 — Custom costs")
    void testCustomCosts() {
        RateLimiter limiter = new RateLimiter(10, 1, 1000);
        assertThat(limiter.allowRequest("C", 6)).isTrue();
        assertThat(limiter.allowRequest("C", 5)).as("11th total token should be rejected").isFalse();
        assertThat(limiter.allowRequest("C", 4)).isTrue();
    }

    @Test
    @DisplayName("RL1.5 — Independent identifiers")
    void testIndependentBuckets() {
        RateLimiter limiter = new RateLimiter(1, 1, 1000);
        limiter.allowRequest("user1");
        assertThat(limiter.allowRequest("user2")).as("user2 should be unaffected by user1").isTrue();
    }

    @Test
    @DisplayName("RL1.7 — Concurrent safety")
    void testConcurrentLimiting() throws InterruptedException {
        RateLimiter limiter = new RateLimiter(10, 1, 10000);
        int threads = 20;
        java.util.concurrent.ExecutorService service = java.util.concurrent.Executors.newFixedThreadPool(threads);
        CountDownLatch latch = new CountDownLatch(threads);
        AtomicInteger allowed = new AtomicInteger();
        
        for (int i = 0; i < threads; i++) {
            service.submit(() -> {
                try {
                    if (limiter.allowRequest("shared")) {
                        allowed.incrementAndGet();
                    }
                } finally {
                    latch.countDown();
                }
            });
        }
        
        latch.await(5, java.util.concurrent.TimeUnit.SECONDS);
        service.shutdown();
        
        assertThat(allowed.get()).as("Exactly maxTokens should be allowed concurrently").isEqualTo(10);
    }
}
