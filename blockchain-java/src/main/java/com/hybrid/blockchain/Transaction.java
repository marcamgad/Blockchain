package com.hybrid.blockchain;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.Base64;

public class Transaction {

    public enum TxType { ACCOUNT, UTXO, CONTRACT }

    private TxType type;
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

    @JsonIgnore
    private static final ObjectMapper mapper = new ObjectMapper();

    public Transaction() {}

    public Transaction(TxType type, String from, String to, long amount,
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

    @JsonIgnore
    public String serializeForDigest() {
        return type + "|" + from + "|" + to + "|" + amount + "|" + fee + "|" +
                nonce + "|" + timestamp + "|" + networkId + "|" +
                (data == null ? "" : data);
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

    public TxType getType() { return type; }
    public String getId() { return id; }
    public String getFrom() { return from; }
    public String getTo() { return to; }
    public long getAmount() { return amount; }
    public long getFee() { return fee; }
    public long getNonce() { return nonce; }
    public String getNetworkId() { return networkId; }
    public long getTimestamp() { return timestamp; }
    public String getData() { return data; }
}
