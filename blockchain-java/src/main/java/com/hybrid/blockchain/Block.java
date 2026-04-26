package com.hybrid.blockchain;

import java.nio.charset.StandardCharsets;
import java.util.List;

public class Block {
    private static final byte[] DOMAIN_PREFIX = "BLOCK\0".getBytes(StandardCharsets.UTF_8);

    public int index;
    public long timestamp;
    public String prevHash;
    public String getPreviousHash() { return prevHash; }
    public List<Transaction> transactions;
    public long nonce;
    public int difficulty;
    public String hash;
    public String stateRoot;
    public String txRoot;
    private String validatorId;
    private byte[] signature;
    private int blockSizeBytes;

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

    public String calculateTxRoot() {
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
        try {
            java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
            java.io.DataOutputStream dos = new java.io.DataOutputStream(baos);

            dos.writeInt(index);
            dos.writeLong(timestamp);

            byte[] ph = HexUtils.decode(prevHash);
            dos.writeInt(ph.length);
            dos.write(ph);

            dos.writeLong(nonce);
            dos.writeInt(difficulty);

            byte[] sr = HexUtils.decode(stateRoot);
            dos.writeInt(sr.length);
            dos.write(sr);

            byte[] tr = HexUtils.decode(txRoot);
            dos.writeInt(tr.length);
            dos.write(tr);

            dos.flush();
            return baos.toByteArray();
        } catch (java.io.IOException e) {
            throw new RuntimeException("Serialization failed", e);
        }
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
        if (transactions == null) return true;
        for (Transaction tx : transactions) {
            if (!tx.verify())
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
        this.hash = calculateHash();
    }

    public String getTxRoot() {
        return txRoot;
    }

    public void setTxRoot(String txRoot) {
        this.txRoot = txRoot;
        this.hash = calculateHash();
    }

    public int getBlockSizeBytes() {
        return blockSizeBytes;
    }

    public void setBlockSizeBytes(int blockSizeBytes) {
        this.blockSizeBytes = blockSizeBytes;
    }

    public void setTimestamp(long timestamp) {
        this.timestamp = timestamp;
        this.hash = calculateHash();
    }

    public void setIndex(int index) {
        this.index = index;
        this.hash = calculateHash();
    }

    public void setPreviousHash(String prevHash) {
        this.prevHash = prevHash;
        this.hash = calculateHash();
    }
}
