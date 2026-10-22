package com.hybrid.blockchain.api;

import com.hybrid.blockchain.Crypto;
import com.hybrid.blockchain.HexUtils;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

/**
 * [HC-085] One-time login challenges for {@code POST /api/v1/auth/token}.
 *
 * <p>A challenge is 32 random bytes, valid for {@link #TTL_MS}, bound to the address it was issued
 * for, and used up by the first attempt (successful or not). Challenges are keyed by their own
 * value, not by address, so requesting challenges for someone else's address cannot cancel
 * theirs. At most {@code maxPending} are kept; expired ones are purged to make room, and when the
 * store is still full new requests are refused (the per-IP API rate limit applies before this).
 * The store is node-local, so a client must answer on the node that issued the challenge; the
 * token itself is accepted by every node that shares JWT_SECRET.</p>
 */
public final class AuthChallenges {

    public static final long TTL_MS = 120_000;
    public static final int DEFAULT_MAX_PENDING = 10_000;
    /** Signed digest: hashWithDomain(DOMAIN, address, challenge). */
    public static final String DOMAIN = "HYBRIDCHAIN-AUTH-v1";

    private record Pending(String address, long expiresAt) { }

    private final Map<String, Pending> pending = new ConcurrentHashMap<>();
    private final SecureRandom random = new SecureRandom();
    private final LongSupplier clock;
    private final int maxPending;

    public AuthChallenges() {
        this(System::currentTimeMillis, DEFAULT_MAX_PENDING);
    }

    AuthChallenges(LongSupplier clock, int maxPending) {
        this.clock = clock;
        this.maxPending = maxPending;
    }

    /** @throws IllegalStateException when too many challenges are pending */
    public synchronized byte[] issue(String address) {
        if (pending.size() >= maxPending) {
            long now = clock.getAsLong();
            pending.values().removeIf(p -> p.expiresAt() < now);
            if (pending.size() >= maxPending) throw new IllegalStateException("too many pending login challenges");
        }
        byte[] challenge = new byte[32];
        random.nextBytes(challenge);
        pending.put(HexUtils.encode(challenge), new Pending(address, clock.getAsLong() + TTL_MS));
        return challenge;
    }

    /** True once for an unexpired challenge issued to {@code address}; the challenge is removed either way. */
    public boolean consume(String address, byte[] challenge) {
        if (address == null || challenge == null || challenge.length != 32) return false;
        Pending p = pending.remove(HexUtils.encode(challenge));
        return p != null && clock.getAsLong() <= p.expiresAt()
                && MessageDigest.isEqual(p.address().getBytes(StandardCharsets.UTF_8), address.getBytes(StandardCharsets.UTF_8));
    }

    /** The 32-byte digest the client signs with its account key. */
    public static byte[] digest(String address, byte[] challenge) {
        return Crypto.hashWithDomain(DOMAIN, address.getBytes(StandardCharsets.UTF_8), challenge);
    }
}
