package com.hybrid.blockchain;

import com.fasterxml.jackson.annotation.JsonIgnore;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;

public class Transaction {
    private String type;
    private String id;
    private String from;
    private String to;
    private long amount;
    private long fee;
    private long nonce;
    private long timestamp;
    private String data;
    private String signature;
    private String pubKey;
    private String networkId;
    private List<UTXOInput> inputs = new ArrayList<>();
    private List<UTXOOutput> outputs = new ArrayList<>();

    @JsonIgnore
    private static final com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();

    public Transaction() {}

    // For account/contract
    public Transaction(String type, String from, String to, long amount,
                       long fee, long nonce, String data) {
        this.type = type;
        this.from = from;
        this.to = to;
        this.amount = amount;
        this.fee = fee;
        this.nonce = nonce;
        this.timestamp = System.currentTimeMillis();
        this.data = data;
        this.networkId = String.valueOf(Config.NETWORK_ID);
        this.id = calculateId();
    }

    // For UTXO
    public Transaction(String type, List<UTXOInput> inputs, List<UTXOOutput> outputs,
                       long fee, long nonce, String data) {
        this.type = type;
        this.inputs = inputs;
        this.outputs = outputs;
        this.from = null;
        this.to = null;
        this.amount = 0;
        this.fee = fee;
        this.nonce = nonce;
        this.timestamp = System.currentTimeMillis();
        this.data = data;
        this.networkId = String.valueOf(Config.NETWORK_ID);
        this.id = calculateId();
    }

    @JsonIgnore
    public String serializeForDigest() {
        StringBuilder sb = new StringBuilder();
        sb.append(type).append("|").append(from).append("|").append(to).append("|").append(amount).append("|").append(fee).append("|")
                .append(nonce).append("|").append(timestamp).append("|").append(networkId).append("|")
                .append((data == null ? "" : data));
        if ("utxo".equals(type)) {
            for (UTXOInput inp : inputs) {
                sb.append("|").append(inp.getTxid())
                .append(":").append(inp.getIndex());
            }
            for (UTXOOutput out : outputs) {
                sb.append("|").append(out.getAddress())
                .append(":").append(out.getAmount());
            }
        }

        return sb.toString();
    }

    public String digest() {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] h = md.digest(serializeForDigest().getBytes(StandardCharsets.UTF_8));
            return bytesToHex(h);
        } catch (Exception e) { throw new RuntimeException(e); }
    }

    private String calculateId() { return digest(); }

    public void sign(PrivateKey privateKey, PublicKey publicKey) throws Exception {
        Signature sig = Signature.getInstance("SHA256withECDSA");
        sig.initSign(privateKey);
        sig.update(digest().getBytes(StandardCharsets.UTF_8));
        byte[] signatureBytes = sig.sign();
        this.signature = Base64.getEncoder().encodeToString(signatureBytes);
        this.pubKey = Base64.getEncoder().encodeToString(publicKey.getEncoded());
    }

    public boolean verify() {
        try {
            if (from == null) return true; // coinbase/coinbase-like txs
            if (signature == null || pubKey == null) return false;
            byte[] sigBytes = Base64.getDecoder().decode(signature);
            byte[] pubBytes = Base64.getDecoder().decode(pubKey);
            KeyFactory kf = KeyFactory.getInstance("EC");
            PublicKey pk = kf.generatePublic(new java.security.spec.X509EncodedKeySpec(pubBytes));
            Signature verifier = Signature.getInstance("SHA256withECDSA");
            verifier.initVerify(pk);
            verifier.update(digest().getBytes(StandardCharsets.UTF_8));
            return verifier.verify(sigBytes);
        } catch (Exception e) {
            return false;
        }
    }

    public static String deriveAddress(PublicKey pubKey) throws Exception {
        MessageDigest sha = MessageDigest.getInstance("SHA-256");
        byte[] h = sha.digest(pubKey.getEncoded());
        return bytesToHex(h).substring(0, 40);
    }

    private static String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) sb.append(String.format("%02x", b));
        return sb.toString();
    }

    public String getType() { return type; }
    public String getId() { return id; }
    public String getFrom() { return from; }
    public String getTo() { return to; }
    public long getAmount() { return amount; }
    public long getFee() { return fee; }
    public long getNonce() { return nonce; }
    public String getNetworkId() { return networkId; }
    public long getTimestamp() { return timestamp; }
    public String getData() { return data; }
    public List<UTXOInput> getInputs() { return inputs; }
    public List<UTXOOutput> getOutputs() { return outputs; }
    public void setSignature(String signature) { this.signature = signature; }
    public void setNetworkId(int networkId2) { this.networkId = networkId; }
}
