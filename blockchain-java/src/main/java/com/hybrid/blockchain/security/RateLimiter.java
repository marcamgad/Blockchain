package com.hybrid.blockchain.security;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Rate Limiting and DoS Protection System
 * 
 * Implements token bucket algorithm for rate limiting:
 * - Per-address transaction rate limiting
 * - Per-IP connection rate limiting
 * - Burst protection
 * - Automatic cleanup of expired entries
 * 
 * Use cases:
 * - Prevent transaction spam
 * - Protect against DoS attacks
 * - Enforce fair resource usage
 */
public class RateLimiter {

    private final Map<String, TokenBucket> buckets;
    private final long refillIntervalMs;
    private final int maxTokens;
    private final int refillAmount;

    /**
     * Create rate limiter
     * 
     * @param maxTokens        Maximum tokens in bucket
     * @param refillAmount     Tokens added per refill interval
     * @param refillIntervalMs Milliseconds between refills
     */
    public RateLimiter(int maxTokens, int refillAmount, long refillIntervalMs) {
        this.buckets = new ConcurrentHashMap<>();
        this.maxTokens = maxTokens;
        this.refillAmount = refillAmount;
        this.refillIntervalMs = refillIntervalMs;
    }

    /**
     * Check if request is allowed
     * 
     * @param identifier Unique identifier (address, IP, etc.)
     * @return true if request is allowed, false if rate limit exceeded
     */
    public boolean allowRequest(String identifier) {
        return allowRequest(identifier, 1);
    }

    /**
     * Check if request is allowed with custom token cost
     * 
     * @param identifier Unique identifier
     * @param tokens     Number of tokens to consume
     * @return true if request is allowed
     */
    public boolean allowRequest(String identifier, int tokens) {
        TokenBucket bucket = buckets.computeIfAbsent(identifier,
                k -> new TokenBucket(maxTokens, refillAmount, refillIntervalMs));

        return bucket.tryConsume(tokens);
    }

    /**
     * Get current token count for identifier
     */
    public int getAvailableTokens(String identifier) {
        TokenBucket bucket = buckets.get(identifier);
        if (bucket == null) {
            return maxTokens;
        }
        return bucket.getAvailableTokens();
    }

    /**
     * Reset rate limit for identifier
     */
    public void reset(String identifier) {
        buckets.remove(identifier);
    }

    /**
     * Clear all rate limits
     */
    public void resetAll() {
        buckets.clear();
    }

    /**
     * Get statistics
     */
    public Map<String, Object> getStats() {
        Map<String, Object> stats = new ConcurrentHashMap<>();
        stats.put("totalIdentifiers", buckets.size());
        stats.put("maxTokens", maxTokens);
        stats.put("refillAmount", refillAmount);
        stats.put("refillIntervalMs", refillIntervalMs);

        int totalTokens = 0;
        for (TokenBucket bucket : buckets.values()) {
            totalTokens += bucket.getAvailableTokens();
        }
        stats.put("totalAvailableTokens", totalTokens);

        return stats;
    }

    /**
     * Cleanup expired buckets (optional maintenance)
     */
    public void cleanup(long inactiveThresholdMs) {
        long now = System.currentTimeMillis();
        buckets.entrySet().removeIf(entry -> (now - entry.getValue().getLastRefillTime()) > inactiveThresholdMs);
    }

    /**
     * Token Bucket implementation
     */
    private static class TokenBucket {
        private final AtomicInteger tokens;
        private final int maxTokens;
        private final int refillAmount;
        private final long refillIntervalMs;
        private volatile long lastRefillTime;

        public TokenBucket(int maxTokens, int refillAmount, long refillIntervalMs) {
            this.maxTokens = maxTokens;
            this.refillAmount = refillAmount;
            this.refillIntervalMs = refillIntervalMs;
            this.tokens = new AtomicInteger(maxTokens);
            this.lastRefillTime = System.currentTimeMillis();
        }

        public synchronized boolean tryConsume(int tokensToConsume) {
            refill();

            int currentTokens = tokens.get();
            if (currentTokens >= tokensToConsume) {
                tokens.addAndGet(-tokensToConsume);
                return true;
            }
            return false;
        }

        private void refill() {
            long now = System.currentTimeMillis();
            long timeSinceLastRefill = now - lastRefillTime;

            if (timeSinceLastRefill >= refillIntervalMs) {
                int refills = (int) (timeSinceLastRefill / refillIntervalMs);
                int tokensToAdd = refills * refillAmount;

                int currentTokens = tokens.get();
                int newTokens = Math.min(maxTokens, currentTokens + tokensToAdd);
                tokens.set(newTokens);

                lastRefillTime = now;
            }
        }

        public int getAvailableTokens() {
            return tokens.get();
        }

        public long getLastRefillTime() {
            return lastRefillTime;
        }
    }

    /**
     * Pre-configured rate limiters for common use cases
     */
    public static class Presets {

        /**
         * Transaction rate limiter: 10 tx/second, burst of 20
         */
        public static RateLimiter transactionLimiter() {
            return new RateLimiter(20, 10, 1000);
        }

        /**
         * API rate limiter: 100 requests/minute, burst of 200
         */
        public static RateLimiter apiLimiter() {
            return new RateLimiter(200, 100, 60000);
        }

        /**
         * Connection rate limiter: 5 connections/second, burst of 10
         */
        public static RateLimiter connectionLimiter() {
            return new RateLimiter(10, 5, 1000);
        }

        /**
         * Strict limiter: 1 request/second, no burst
         */
        public static RateLimiter strictLimiter() {
            return new RateLimiter(1, 1, 1000);
        }
    }
}
