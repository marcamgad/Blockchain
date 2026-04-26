package com.hybrid.blockchain.p2p;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.hybrid.blockchain.Crypto;
import com.hybrid.blockchain.HexUtils;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

/**
 * Container for all network traffic in the Gossip P2P Protocol.
 * Each message is signed by the sender and verifiable by any receiver.
 */
public class P2PMessage {

    public enum Type {
        TRANSACTION,
        BLOCK,
        CONSENSUS,
        PEER_DISCOVERY,
        PING,
        PONG,
        REQUEST_BLOCKS,
        BLOCKS_RESPONSE,
        CHECKPOINT,
        GOSSIP
    }

    private final String messageId;
    private final String senderId;
    private final Type type;
    private final byte[] payload;
    private final byte[] signature;
    private final long timestamp;

    @JsonCreator
    public P2PMessage(
            @JsonProperty("senderId") String senderId,
            @JsonProperty("type") Type type,
            @JsonProperty("payload") byte[] payload,
            @JsonProperty("signature") byte[] signature) {
        this.senderId = senderId;
        this.type = type;
        this.payload = payload;
        this.signature = signature;
        this.timestamp = System.currentTimeMillis();
        this.messageId = calculateId();
    }

    /**
     * Constructor for test compatibility.
     */
    public P2PMessage(String senderId, String dummy, Type type, byte[] payload) {
        this(senderId, type, payload, new byte[64]);
    }

    private String calculateId() {
        if (this.type == Type.TRANSACTION) {
            // For transactions, the message ID should be the hash of the transaction itself
            // to allow global deduplication across the gossip network.
            return HexUtils.encode(Crypto.hash(payload));
        }

        // For other messages, include sender to prevent replay attacks across different senders
        byte[] senderBytes = senderId.getBytes(StandardCharsets.UTF_8);
        byte[] typeBytes = type.name().getBytes(StandardCharsets.UTF_8);
        
        ByteBuffer buffer = ByteBuffer.allocate(senderBytes.length + typeBytes.length + payload.length);
        buffer.put(senderBytes);
        buffer.put(typeBytes);
        buffer.put(payload);
        
        return HexUtils.encode(Crypto.hash(buffer.array()));
    }

    public static P2PMessage create(String senderId, java.math.BigInteger privateKey, Type type, byte[] payload) {
        byte[] sig = Crypto.sign(payload, privateKey);
        return new P2PMessage(senderId, type, payload, sig);
    }

    public boolean verify(byte[] publicKey) {
        return Crypto.verify(payload, signature, publicKey);
    }

    // Getters
    public String getMessageId() { return messageId; }
    public String getSenderId() { return senderId; }
    public Type getType() { return type; }
    public byte[] getPayload() { return payload; }
    public byte[] getSignature() { return signature; }
    public long getTimestamp() { return timestamp; }

    @Override
    public String toString() {
        return String.format("P2PMessage[id=%s, type=%s, sender=%s]", 
            messageId.substring(0, 8), type, senderId);
    }
}
