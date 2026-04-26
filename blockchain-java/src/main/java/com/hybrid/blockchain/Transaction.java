package com.hybrid.blockchain;

import java.nio.charset.StandardCharsets;
import java.math.BigInteger;
import java.util.*;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public final class Transaction {

    public enum Type {
        ACCOUNT, UTXO, CONTRACT, IOT_MANAGEMENT, MINT, BURN, TOKEN_TRANSFER, TELEMETRY,
        TOKEN_REGISTER, TOKEN_MINT, TOKEN_BURN,
        /** Federated-learning local weight update submitted by a node. */
        FEDERATED_UPDATE,
        /** Aggregated model committed on-chain by the PBFT leader. */
        FEDERATED_COMMIT
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
    private int networkId;
    private byte[] data;
    private final long validUntilBlock;

    private final List<UTXOInput> inputs;
    private final List<UTXOOutput> outputs;

    private byte[] pubKey;
    private byte[] signature;
    @JsonProperty("dilithiumPublicKey")
    private byte[] dilithiumPublicKey;
    @JsonProperty("dilithiumSignature")
    private byte[] dilithiumSignature;
    private String txid;

    @JsonCreator
    public Transaction(
            @JsonProperty("type") Type type,
            @JsonProperty("from") String from,
            @JsonProperty("to") String to,
            @JsonProperty("amount") long amount,
            @JsonProperty("fee") long fee,
            @JsonProperty("nonce") long nonce,
            @JsonProperty("timestamp") long timestamp,
            @JsonProperty("networkId") int networkId,
            @JsonProperty("data") byte[] data,
            @JsonProperty("validUntilBlock") long validUntilBlock,
            @JsonProperty("inputs") List<UTXOInput> inputs,
            @JsonProperty("outputs") List<UTXOOutput> outputs,
            @JsonProperty("pubKey") byte[] pubKey,
            @JsonProperty("signature") byte[] signature,
            @JsonProperty("dilithiumPublicKey") byte[] dilithiumPublicKey,
            @JsonProperty("dilithiumSignature") byte[] dilithiumSignature) {
        this.type = type;
        this.from = from;
        this.to = to;
        this.amount = amount;
        this.fee = fee;
        this.nonce = nonce;
        this.timestamp = timestamp;
        this.networkId = networkId;
        this.data = data == null ? new byte[0] : data;
        this.validUntilBlock = validUntilBlock;
        this.inputs = inputs == null ? List.of() : List.copyOf(inputs);
        this.outputs = outputs == null ? List.of() : List.copyOf(outputs);
        this.pubKey = pubKey;
        this.signature = signature;
        this.dilithiumPublicKey = dilithiumPublicKey;
        this.dilithiumSignature = dilithiumSignature;
        this.txid = Crypto.bytesToHex(Crypto.hash(serializeCanonical()));
    }

    private Transaction(Builder b) {
        this(b.type, b.from, b.to, b.amount, b.fee, b.nonce, b.timestamp, b.networkId, b.data, b.validUntilBlock, b.inputs, b.outputs, b.pubKey, b.signature, b.dilithiumPublicKey, b.dilithiumSignature);
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
        private byte[] dilithiumPublicKey;
        private byte[] dilithiumSignature;

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

        public Builder timestamp(long t) {
            this.timestamp = t;
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

        /** Sets the raw public key bytes directly (used when reconstructing a transaction without re-signing). */
        public Builder publicKey(byte[] pk) {
            this.pubKey = pk;
            return this;
        }

        /** Sets the raw signature bytes directly (used when reconstructing a transaction without re-signing). */
        public Builder signature(byte[] sig) {
            this.signature = sig;
            return this;
        }

        public Builder addInput(String txid, int index) {
            this.inputs.add(new UTXOInput(txid, index));
            return this;
        }

        public Builder addOutput(String address, long amount) {
            this.outputs.add(new UTXOOutput(address, amount));
            return this;
        }

        public Transaction sign(BigInteger privateKey, byte[] publicKey) {
            this.pubKey = publicKey;
            if (this.from == null) {
                this.from = Crypto.deriveAddress(publicKey);
            }
            Transaction unsigned = new Transaction(this);
            byte[] msg = unsigned.signingPayload();
            this.signature = Crypto.sign(msg, privateKey);
            return this.build();
        }

        public Transaction signHybrid(BigInteger ecPrivKey, byte[] ecPubKey, java.security.PrivateKey dilithiumPrivKey, java.security.PublicKey dilithiumPubKey) {
            this.pubKey = ecPubKey;
            if (this.dilithiumPublicKey == null) {
                this.dilithiumPublicKey = dilithiumPubKey.getEncoded();
            }
            if (this.from == null) {
                this.from = Crypto.deriveAddress(ecPubKey);
            }
            Transaction unsigned = new Transaction(this);
            byte[] msg = unsigned.signingPayload();
            this.signature = Crypto.sign(msg, ecPrivKey);
            try {
                this.dilithiumSignature = com.hybrid.blockchain.security.QuantumResistantCrypto.signDilithium(msg, dilithiumPrivKey);
            } catch (Exception e) {
                throw new RuntimeException("Dilithium signing failed", e);
            }
            return this.build();
        }

        public Transaction build() {
            return new Transaction(this);
        }
    }

    /**
     * Identity method allowing chaining: {@code builder.sign(...).build()} still works
     * even though {@code sign()} now returns Transaction directly.
     *
     * @return {@code this}
     */
    public Transaction build() {
        return this;
    }

    private byte[] signingPayload() {
        byte[] body = serializeCanonical();
        byte[] payload = new byte[DOMAIN_PREFIX.length + body.length];
        System.arraycopy(DOMAIN_PREFIX, 0, payload, 0, DOMAIN_PREFIX.length);
        System.arraycopy(body, 0, payload, DOMAIN_PREFIX.length, body.length);
        return Crypto.hash(payload);
    }

    public byte[] serializeCanonical() {
        try {
            java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
            java.io.DataOutputStream dos = new java.io.DataOutputStream(baos);

            dos.writeInt(version);
            dos.writeInt(type.ordinal());
            dos.writeInt(networkId);
            dos.writeLong(nonce);
            dos.writeLong(timestamp);
            dos.writeLong(validUntilBlock);

            writeString(dos, from);
            writeString(dos, to);
            dos.writeLong(amount);
            dos.writeLong(fee);
            dos.writeInt(data.length);
            dos.write(data);

            dos.writeInt(inputs.size());
            for (UTXOInput i : inputs) {
                writeString(dos, i.getTxid());
                dos.writeInt(i.getIndex());
            }

            dos.writeInt(outputs.size());
            for (UTXOOutput o : outputs) {
                writeString(dos, o.getAddress());
                dos.writeLong(o.getAmount());
            }

            dos.flush();
            return baos.toByteArray();
        } catch (java.io.IOException e) {
            throw new RuntimeException("Serialization failed", e);
        }
    }

    public void sign(BigInteger privateKey) {
        this.pubKey = Crypto.derivePublicKey(privateKey);
        this.signature = Crypto.sign(signingPayload(), privateKey);
        this.txid = Crypto.bytesToHex(Crypto.hash(serializeCanonical()));
    }

    public void signHybrid(BigInteger ecPrivKey, java.security.PrivateKey dilithiumPrivKey, java.security.PublicKey dilithiumPubKey) {
        this.pubKey = Crypto.derivePublicKey(ecPrivKey);
        this.dilithiumPublicKey = dilithiumPubKey.getEncoded();
        byte[] payload = signingPayload();
        this.signature = Crypto.sign(payload, ecPrivKey);
        try {
            this.dilithiumSignature = com.hybrid.blockchain.security.QuantumResistantCrypto.signDilithium(payload, dilithiumPrivKey);
        } catch (Exception e) {
            throw new RuntimeException("Dilithium signing failed", e);
        }
        this.txid = Crypto.bytesToHex(Crypto.hash(serializeCanonical()));
    }

    private static void writeString(java.io.DataOutputStream dos, String s) throws java.io.IOException {
        if (s == null) {
            dos.writeInt(0);
        } else {
            byte[] b = s.getBytes(StandardCharsets.UTF_8);
            dos.writeInt(b.length);
            dos.write(b);
        }
    }

    public boolean verify() {
        if (signature == null || pubKey == null)
            return false;
        if (!Crypto.deriveAddress(pubKey).equals(from))
            return false;
        
        byte[] payload = signingPayload();
        boolean ecValid = Crypto.verify(payload, signature, pubKey);
        if (!ecValid) return false;

        // FIX 2: Enforce Dilithium hybrid signature check if required
        if (Config.REQUIRE_QUANTUM_SIG) {
            if (dilithiumSignature == null || dilithiumPublicKey == null) return false;
            try {
                // To avoid storing raw byte-arrays where Keys are needed, we assume 
                // QuantumResistantCrypto provides a way, or we reconstruct the key:
                java.security.KeyFactory kf = java.security.KeyFactory.getInstance("Dilithium", "BCPQC");
                java.security.PublicKey dPubKey = kf.generatePublic(new java.security.spec.X509EncodedKeySpec(dilithiumPublicKey));
                return com.hybrid.blockchain.security.QuantumResistantCrypto.verifyDilithium(payload, dilithiumSignature, dPubKey);
            } catch (Exception e) {
                return false;
            }
        }
        return true;
    }

    public String getTxid() {
        return txid;
    }

    @JsonIgnore
    public String getId() {
        return txid;
    }

    @JsonIgnore
    public String getTxId() {
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

    public void setData(byte[] data) {
        this.data = data;
    }

    public byte[] getSignature() {
        return signature;
    }

    public byte[] getPubKey() {
        return pubKey;
    }

    /** Alias for {@link #getPubKey()} used by tests that call {@code getPublicKey()}. */
    public byte[] getPublicKey() {
        return pubKey;
    }

    public byte[] getDilithiumSignature() {
        return dilithiumSignature;
    }

    public byte[] getDilithiumPublicKey() {
        return dilithiumPublicKey;
    }

    @JsonIgnore
    public String digest() {
        return txid;
    }
}
