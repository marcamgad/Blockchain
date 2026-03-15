package com.hybrid.blockchain;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.List;

public class Block {
    private static final byte[] DOMAIN_PREFIX = "BLOCK\0".getBytes(StandardCharsets.UTF_8);

    public int index;
    public long timestamp;
    public String prevHash;
    public List<Transaction> transactions;
    public long nonce;
    public int difficulty;
    public String hash;
    public String stateRoot;
    public String txRoot;
    private String validatorId;
    private byte[] signature;

    public Block() {
    }

    public Block(int index, long timestamp, List<Transaction> transactions, String prevHash, int difficulty,
            String stateRoot) {
        this.index = index;
        this.timestamp = timestamp;
        this.transactions = transactions;
        this.prevHash = prevHash;
        this.nonce = 0;
        this.difficulty = difficulty;
        this.stateRoot = stateRoot;
        this.txRoot = calculateTxRoot();
        this.hash = calculateHash();
    }

    private String calculateTxRoot() {
        if (transactions == null || transactions.isEmpty()) {
            return Crypto.bytesToHex(new byte[32]);
        }
        List<byte[]> leaves = new java.util.ArrayList<>();
        for (Transaction tx : transactions) {
            leaves.add(Crypto.hash(tx.serializeCanonical()));
        }
        return Crypto.bytesToHex(MerkleTree.computeRoot(leaves));
    }

    public String calculateHash() {
        return Crypto.bytesToHex(Crypto.hash(serializeCanonical()));
    }

    public byte[] serializeCanonical() {
        ByteBuffer buf = ByteBuffer.allocate(1024).order(ByteOrder.BIG_ENDIAN);
        buf.putInt(index);
        buf.putLong(timestamp);

        byte[] ph = HexUtils.decode(prevHash);
        buf.putInt(ph.length);
        buf.put(ph);

        buf.putLong(nonce);
        buf.putInt(difficulty);

        byte[] sr = HexUtils.decode(stateRoot);
        buf.putInt(sr.length);
        buf.put(sr);

        byte[] tr = HexUtils.decode(txRoot);
        buf.putInt(tr.length);
        buf.put(tr);

        buf.flip();
        byte[] out = new byte[buf.remaining()];
        buf.get(out);
        return out;
    }

    public void mine(int targetDifficulty, long maxNonce) {
        String targetPrefix = "0".repeat(Math.max(0, targetDifficulty));
        do {
            if (this.nonce >= maxNonce)
                throw new RuntimeException("Nonce limit exceeded");
            this.nonce++;
            this.hash = calculateHash();
        } while (!this.hash.startsWith(targetPrefix));
    }

    public boolean hasValidTransactions() {
        for (Transaction tx : transactions) {
            if (!Config.isDebug() && !tx.verify())
                return false;
        }
        return true;
    }

    public int getIndex() {
        return index;
    }

    public String getHash() {
        return hash;
    }

    public long getTimestamp() {
        return timestamp;
    }

    public String getPrevHash() {
        return prevHash;
    }

    public int getDifficulty() {
        return difficulty;
    }

    public long getNonce() {
        return nonce;
    }

    public List<Transaction> getTransactions() {
        return transactions;
    }

    public void setHash(String hash) {
        this.hash = hash;
    }

    public String getValidatorId() {
        return validatorId;
    }

    public void setValidatorId(String validatorId) {
        this.validatorId = validatorId;
    }

    public byte[] getSignature() {
        return signature;
    }

    public void setSignature(byte[] signature) {
        this.signature = signature;
    }

    public String getStateRoot() {
        return stateRoot;
    }

    public void setStateRoot(String stateRoot) {
        this.stateRoot = stateRoot;
    }

    public String getTxRoot() {
        return txRoot;
    }

    public void setTxRoot(String txRoot) {
        this.txRoot = txRoot;
    }
}
