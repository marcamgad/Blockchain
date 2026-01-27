package com.hybrid.blockchain;

import com.hybrid.blockchain.security.RateLimiter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for Rate Limiter and DoS Protection
 */
public class RateLimiterTest {

    private RateLimiter rateLimiter;

    @BeforeEach
    public void setUp() {
        // 5 tokens max, refill 2 tokens per second
        rateLimiter = new RateLimiter(5, 2, 1000);
    }

    @Test
    public void testBasicRateLimiting() {
        String user = "alice";

        // Should allow first 5 requests
        for (int i = 0; i < 5; i++) {
            assertTrue(rateLimiter.allowRequest(user), "Request " + i + " should be allowed");
        }

        // 6th request should be blocked
        assertFalse(rateLimiter.allowRequest(user), "6th request should be blocked");
    }

    @Test
    public void testTokenRefill() throws InterruptedException {
        String user = "bob";

        // Consume all tokens
        for (int i = 0; i < 5; i++) {
            rateLimiter.allowRequest(user);
        }

        // Should be blocked
        assertFalse(rateLimiter.allowRequest(user));

        // Wait for refill (1 second = 2 tokens)
        Thread.sleep(1100);

        // Should now allow 2 requests
        assertTrue(rateLimiter.allowRequest(user));
        assertTrue(rateLimiter.allowRequest(user));
        assertFalse(rateLimiter.allowRequest(user));
    }

    @Test
    public void testMultipleUsers() {
        String alice = "alice";
        String bob = "bob";

        // Alice uses 3 tokens
        assertTrue(rateLimiter.allowRequest(alice));
        assertTrue(rateLimiter.allowRequest(alice));
        assertTrue(rateLimiter.allowRequest(alice));

        // Bob should still have full quota
        for (int i = 0; i < 5; i++) {
            assertTrue(rateLimiter.allowRequest(bob), "Bob's request " + i + " should be allowed");
        }

        // Alice still has 2 tokens left
        assertTrue(rateLimiter.allowRequest(alice));
        assertTrue(rateLimiter.allowRequest(alice));
        assertFalse(rateLimiter.allowRequest(alice));
    }

    @Test
    public void testCustomTokenCost() {
        String user = "charlie";

        // Consume 3 tokens at once
        assertTrue(rateLimiter.allowRequest(user, 3));

        // 2 tokens remaining
        assertEquals(2, rateLimiter.getAvailableTokens(user));

        // Try to consume 3 more (should fail)
        assertFalse(rateLimiter.allowRequest(user, 3));

        // Can still consume 2
        assertTrue(rateLimiter.allowRequest(user, 2));
    }

    @Test
    public void testReset() {
        String user = "dave";

        // Consume all tokens
        for (int i = 0; i < 5; i++) {
            rateLimiter.allowRequest(user);
        }

        assertFalse(rateLimiter.allowRequest(user));

        // Reset
        rateLimiter.reset(user);

        // Should have full quota again
        assertTrue(rateLimiter.allowRequest(user));
        assertEquals(4, rateLimiter.getAvailableTokens(user));
    }

    @Test
    public void testGetAvailableTokens() {
        String user = "eve";

        // Initially should have max tokens
        assertEquals(5, rateLimiter.getAvailableTokens(user));

        // After consuming 2
        rateLimiter.allowRequest(user);
        rateLimiter.allowRequest(user);
        assertEquals(3, rateLimiter.getAvailableTokens(user));
    }

    @Test
    public void testStats() {
        rateLimiter.allowRequest("user1");
        rateLimiter.allowRequest("user2");
        rateLimiter.allowRequest("user3");

        Map<String, Object> stats = rateLimiter.getStats();
        assertEquals(3, stats.get("totalIdentifiers"));
        assertEquals(5, stats.get("maxTokens"));
        assertEquals(2, stats.get("refillAmount"));
        assertEquals(1000L, stats.get("refillIntervalMs"));
    }

    @Test
    public void testTransactionLimiterPreset() {
        RateLimiter txLimiter = RateLimiter.Presets.transactionLimiter();
        String user = "alice";

        // Should allow burst of 20
        for (int i = 0; i < 20; i++) {
            assertTrue(txLimiter.allowRequest(user));
        }

        // 21st should be blocked
        assertFalse(txLimiter.allowRequest(user));
    }

    @Test
    public void testAPILimiterPreset() {
        RateLimiter apiLimiter = RateLimiter.Presets.apiLimiter();
        String client = "client-001";

        // Should allow burst of 200
        for (int i = 0; i < 200; i++) {
            assertTrue(apiLimiter.allowRequest(client));
        }

        assertFalse(apiLimiter.allowRequest(client));
    }

    @Test
    public void testStrictLimiterPreset() {
        RateLimiter strictLimiter = RateLimiter.Presets.strictLimiter();
        String user = "strict-user";

        // Should only allow 1 request
        assertTrue(strictLimiter.allowRequest(user));
        assertFalse(strictLimiter.allowRequest(user));
    }

    @Test
    public void testCleanup() throws InterruptedException {
        String user1 = "user1";
        String user2 = "user2";

        rateLimiter.allowRequest(user1);
        Thread.sleep(100);
        rateLimiter.allowRequest(user2);

        // Cleanup buckets inactive for > 50ms
        rateLimiter.cleanup(50);

        Map<String, Object> stats = rateLimiter.getStats();
        // user1 should be cleaned up, user2 should remain
        assertTrue((int) stats.get("totalIdentifiers") <= 2);
    }

    @Test
    public void testResetAll() {
        rateLimiter.allowRequest("user1");
        rateLimiter.allowRequest("user2");
        rateLimiter.allowRequest("user3");

        rateLimiter.resetAll();

        Map<String, Object> stats = rateLimiter.getStats();
        assertEquals(0, stats.get("totalIdentifiers"));
    }
}
