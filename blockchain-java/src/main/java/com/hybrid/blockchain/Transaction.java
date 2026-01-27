package com.hybrid.blockchain;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.math.BigInteger;
import java.util.*;

public final class Transaction {

    public enum Type {
        ACCOUNT, UTXO, CONTRACT
    }

    private static final byte[] DOMAIN_PREFIX = "TX\0".getBytes(StandardCharsets.UTF_8);
    private final int version = 1;
    private final Type type;
    private final String from;
    private final String to;
    private final long amount;
    private final long fee;
    private final long nonce;
    private final long timestamp;
    private final int networkId;
    private final byte[] data;
    private final long validUntilBlock;

    private final List<UTXOInput> inputs;
    private final List<UTXOOutput> outputs;

    private final byte[] pubKey;
    private final byte[] signature;
    private final String txid;

    private Transaction(Builder b) {
        this.type = b.type;
        this.from = b.from;
        this.to = b.to;
        this.amount = b.amount;
        this.fee = b.fee;
        this.nonce = b.nonce;
        this.timestamp = b.timestamp;
        this.networkId = b.networkId;
        this.data = b.data == null ? new byte[0] : b.data;
        this.validUntilBlock = b.validUntilBlock;
        this.inputs = b.inputs == null ? List.of() : List.copyOf(b.inputs);
        this.outputs = b.outputs == null ? List.of() : List.copyOf(b.outputs);
        this.pubKey = b.pubKey;
        this.signature = b.signature;
        this.txid = Crypto.bytesToHex(Crypto.hash(serializeCanonical()));
    }

    public static class Builder {
        private Type type = Type.ACCOUNT;
        private String from;
        private String to;
        private long amount;
        private long fee;
        private long nonce;
        private long timestamp = System.currentTimeMillis();
        private int networkId = Config.NETWORK_ID;
        private byte[] data = new byte[0];
        private long validUntilBlock;
        private List<UTXOInput> inputs = new ArrayList<>();
        private List<UTXOOutput> outputs = new ArrayList<>();
        private byte[] pubKey;
        private byte[] signature;

        public Builder type(Type t) {
            this.type = t;
            return this;
        }

        public Builder from(String f) {
            this.from = f;
            return this;
        }

        public Builder to(String t) {
            this.to = t;
            return this;
        }

        public Builder amount(long a) {
            this.amount = a;
            return this;
        }

        public Builder fee(long f) {
            this.fee = f;
            return this;
        }

        public Builder nonce(long n) {
            this.nonce = n;
            return this;
        }

        public Builder networkId(int n) {
            this.networkId = n;
            return this;
        }

        public Builder data(byte[] d) {
            this.data = d;
            return this;
        }

        public Builder validUntilBlock(long v) {
            this.validUntilBlock = v;
            return this;
        }

        public Builder inputs(List<UTXOInput> i) {
            this.inputs = i;
            return this;
        }

        public Builder outputs(List<UTXOOutput> o) {
            this.outputs = o;
            return this;
        }

        public Transaction sign(BigInteger privateKey, byte[] publicKey) {
            this.pubKey = publicKey;
            Transaction unsigned = new Transaction(this);
            byte[] msg = unsigned.signingPayload();
            this.signature = Crypto.sign(msg, privateKey);
            this.from = Crypto.deriveAddress(publicKey);
            return new Transaction(this);
        }

        public Transaction build() {
            return new Transaction(this);
        }
    }

    private byte[] signingPayload() {
        byte[] body = serializeCanonical();
        byte[] payload = new byte[DOMAIN_PREFIX.length + body.length];
        System.arraycopy(DOMAIN_PREFIX, 0, payload, 0, DOMAIN_PREFIX.length);
        System.arraycopy(body, 0, payload, DOMAIN_PREFIX.length, body.length);
        return Crypto.hash(payload);
    }

    public byte[] serializeCanonical() {
        ByteBuffer buf = ByteBuffer.allocate(8192).order(ByteOrder.BIG_ENDIAN);
        buf.putInt(version);
        buf.putInt(type.ordinal());
        buf.putInt(networkId);
        buf.putLong(nonce);
        buf.putLong(timestamp);
        buf.putLong(validUntilBlock);

        putString(buf, from);
        putString(buf, to);
        buf.putLong(amount);
        buf.putLong(fee);
        buf.putInt(data.length);
        buf.put(data);

        buf.putInt(inputs.size());
        for (UTXOInput i : inputs) {
            putString(buf, i.getTxid());
            buf.putInt(i.getIndex());
        }

        buf.putInt(outputs.size());
        for (UTXOOutput o : outputs) {
            putString(buf, o.getAddress());
            buf.putLong(o.getAmount());
        }

        buf.flip();
        byte[] out = new byte[buf.remaining()];
        buf.get(out);
        return out;
    }

    private static void putString(ByteBuffer buf, String s) {
        if (s == null) {
            buf.putInt(0);
        } else {
            byte[] b = s.getBytes(StandardCharsets.UTF_8);
            buf.putInt(b.length);
            buf.put(b);
        }
    }

    public boolean verify() {
        if (signature == null || pubKey == null)
            return false;
        if (!Crypto.deriveAddress(pubKey).equals(from))
            return false;
        return Crypto.verify(signingPayload(), signature, pubKey);
    }

    public String getTxid() {
        return txid;
    }

    public String getId() {
        return txid;
    }

    public String getFrom() {
        return from;
    }

    public String getTo() {
        return to;
    }

    public long getAmount() {
        return amount;
    }

    public long getFee() {
        return fee;
    }

    public long getNonce() {
        return nonce;
    }

    public long getTimestamp() {
        return timestamp;
    }

    public int getNetworkId() {
        return networkId;
    }

    public long getValidUntilBlock() {
        return validUntilBlock;
    }

    public Type getType() {
        return type;
    }

    public List<UTXOInput> getInputs() {
        return inputs;
    }

    public List<UTXOOutput> getOutputs() {
        return outputs;
    }

    public byte[] getData() {
        return data;
    }

    public String digest() {
        return txid;
    }
}
