package com.hybrid.blockchain.api;

import com.hybrid.blockchain.Blockchain;
import com.hybrid.blockchain.Config;
import com.hybrid.blockchain.Crypto;
import com.hybrid.blockchain.Transaction;
import com.hybrid.blockchain.security.RateLimiter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigInteger;

/**
 * Shared ingress path for IoT transport adapters.
 *
 * <p>Every device message — whichever transport it arrives on — flows through the same
 * gate here: <b>rate-limit → validate → build+sign+submit</b>. This removes the
 * duplication that previously let MQTT and CoAP drift apart (one adapter was a no-op
 * stub, only two ingress points were rate-limited), and guarantees a single, auditable
 * place where device input becomes a transaction.
 */
public abstract class AbstractIoTMessageAdapter implements IoTMessageAdapter {

    protected final Logger log = LoggerFactory.getLogger(getClass());

    protected final Blockchain blockchain;
    protected final RateLimiter rateLimiter;

    /** Hard cap on a single device message; well below MAX_BLOCK_SIZE to bound ingress memory. */
    protected static final int MAX_PAYLOAD_BYTES = 64 * 1024;

    /** Thrown when a source exceeds its ingress rate budget. */
    public static class RateLimitedException extends RuntimeException {
        public RateLimitedException(String message) { super(message); }
    }

    protected AbstractIoTMessageAdapter(Blockchain blockchain) {
        // Generous burst (apiLimiter: 200 burst, 100/min) — protects against floods
        // without tripping on normal device cadence.
        this(blockchain, RateLimiter.Presets.apiLimiter());
    }

    protected AbstractIoTMessageAdapter(Blockchain blockchain, RateLimiter rateLimiter) {
        this.blockchain = blockchain;
        this.rateLimiter = rateLimiter;
    }

    /** Rate-limit gate keyed by source (device id / topic / remote endpoint). */
    protected boolean allowIngress(String sourceId) {
        String id = (sourceId == null || sourceId.isEmpty()) ? "anonymous" : sourceId;
        return rateLimiter.allowRequest(id);
    }

    /** Structural payload validation shared by all transports. */
    protected void validatePayload(byte[] payload) {
        if (payload == null || payload.length == 0) {
            throw new IllegalArgumentException("Empty payload");
        }
        if (payload.length > MAX_PAYLOAD_BYTES) {
            throw new IllegalArgumentException(
                    "Payload " + payload.length + " exceeds ingress cap " + MAX_PAYLOAD_BYTES);
        }
    }

    /**
     * The single shared ingress action: rate-limit, validate, then build a signed
     * transaction from a device message and submit it to the mempool.
     *
     * <p>Serialized so the sequential per-node nonce is assigned without races between
     * concurrent messages. Signs with the node key; falls back to unsigned only when no
     * key is configured (debug/test).
     *
     * @return the submitted transaction id
     * @throws RateLimitedException     if the source is over budget
     * @throws IllegalArgumentException if the payload fails validation
     * @throws Exception                if the blockchain rejects the transaction
     */
    protected synchronized String submitDeviceTx(Transaction.Type type, String deviceId, byte[] payload)
            throws Exception {
        validatePayload(payload);
        if (!allowIngress(deviceId)) {
            throw new RateLimitedException("Ingress rate limit exceeded for " + deviceId);
        }

        String nodeAddr = Config.NODE_ID;
        long nonce = blockchain.getNonce(nodeAddr) + 1;
        Transaction.Builder builder = new Transaction.Builder()
                .type(type)
                .from(nodeAddr)
                .to(deviceId)
                .amount(0)
                .data(payload)
                .nonce(nonce);

        Transaction tx;
        try {
            BigInteger privKey = Config.getNodePrivateKey();
            tx = builder.sign(privKey, Crypto.derivePublicKey(privKey)).build();
        } catch (RuntimeException noKey) {
            tx = builder.build(); // debug/test: no node key configured
        }

        blockchain.addTransaction(tx);
        log.info("Submitted {} tx {} for device {}", type, tx.getTxid(), deviceId);
        return tx.getTxid();
    }
}
