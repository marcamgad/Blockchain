package com.hybrid.blockchain;
import com.fasterxml.jackson.annotation.JacksonAnnotation;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;

import javax.management.RuntimeErrorException;

public class Block {
    public long index;
    public long timestamp;
    public String prevHash;
    public List<Transaction> transactions;
    public long nonce;
    public int difficulty;
    public String hash;

    @JsonIgnore
    private static final ObjectMapper mapper = new ObjectMapper();

    public Block(){}

    public Block(long index, long timestamp, List<Transaction> transactions, String prevHash, int difficulty){
        this.index = index;
        this.timestamp = timestamp;
        this.transactions = transactions;
        this.prevHash = prevHash;
        this.nonce = 0;
        this.difficulty = difficulty;
        this.hash = calculateHash();
    }

    public String calculateHash(){
        try {
            StringBuilder sb = new StringBuilder();
            sb.append("|").append(timestamp).append("|").append(prevHash).append("|").append(nonce).append("|").append(difficulty);
            for (Transaction tx : transactions) sb.append("|").append(tx.digest());
            MessageDigest md = MessageDigest .getInstance("SHA-256");
            byte[] h = md.digest(sb.toString().getBytes(StandardCharsets.UTF_8));
            return bytesToHex(h);
        }catch(Exception e){
            throw new RuntimeException(e);
        }
    }

    public void mine(int targetDifficulty, long maxNonce){
        String targetPrefix = "0".repeat(Math.max(0, targetDifficulty));
        while(!this.hash.startsWith(targetPrefix)){
            if(this.nonce >= maxNonce) throw new RuntimeException("Nonce limit exceed");
            this.nonce++;
            this.hash = calculateHash();
        }
    }
    public boolean hasValidTransaction(){
        try{
            for(Transaction tx : transactions){
                if(!tx.verify()) return false;
            }
            return true;
        } catch (Exception e){
            return false;
        }
    }

    private static String bytesToHex(byte[] bytes){
        StringBuilder sb = new StringBuilder();
        for(byte b : bytes) sb.append(String.format("%02x", b));
        return sb.toString();
    }
    public long getIndex() { return index; }
    public String getHash() { return hash; }
    public long getTimestamp() { return timestamp; }
    public String getPrevHash() { return prevHash; }
    public int getDifficulty() { return difficulty; }
    public long getNonce() { return nonce; }
    public List<Transaction> getTransactions() { return transactions; }

}
