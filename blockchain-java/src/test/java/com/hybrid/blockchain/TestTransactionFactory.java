package com.hybrid.blockchain;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Factory for creating signed transactions of various types for testing.
 */
public class TestTransactionFactory {
    
    public static Transaction createAccountTransfer(
            TestKeyPair sender, 
            String to, 
            long amount, 
            long fee, 
            long nonce) {
        
        return new Transaction.Builder()
                .type(Transaction.Type.ACCOUNT)
                .from(sender.getAddress())
                .to(to)
                .amount(amount)
                .fee(fee)
                .nonce(nonce)
                .sign(sender.getPrivateKey(), sender.getPublicKey());
    }

    public static Transaction createContractCreation(
            TestKeyPair sender,
            byte[] bytecode,
            long fee,
            long nonce) {
        
        return new Transaction.Builder()
                .type(Transaction.Type.CONTRACT)
                .from(sender.getAddress())
                .to(null) // Contract creation MUST have null 'to' address
                .amount(0)
                .fee(fee)
                .nonce(nonce)
                .data(bytecode)
                .sign(sender.getPrivateKey(), sender.getPublicKey());
    }

    public static Transaction createContractCall(
            TestKeyPair sender,
            String contractAddress,
            byte[] callData,
            long amount,
            long fee,
            long nonce) {
        
        return new Transaction.Builder()
                .type(Transaction.Type.CONTRACT)
                .from(sender.getAddress())
                .to(contractAddress)
                .amount(amount)
                .fee(fee)
                .nonce(nonce)
                .data(callData)
                .sign(sender.getPrivateKey(), sender.getPublicKey());
    }

    public static Transaction createIoTEvent(
            TestKeyPair device,
            String eventData,
            long fee,
            long nonce) {
        
        return new Transaction.Builder()
                .type(Transaction.Type.IOT_MANAGEMENT)
                .from(device.getAddress())
                .to("0x0") // Special address for events?
                .amount(0)
                .fee(fee)
                .nonce(nonce)
                .data(eventData.getBytes(StandardCharsets.UTF_8))
                .sign(device.getPrivateKey(), device.getPublicKey());
    }

    public static Transaction createTokenRegister(
            TestKeyPair owner,
            String tokenId,
            String name,
            String symbol,
            long maxSupply,
            long fee,
            long nonce) {
        
        try {
            java.util.Map<String, Object> metadata = new java.util.HashMap<>();
            metadata.put("tokenId", tokenId);
            metadata.put("name", name);
            metadata.put("symbol", symbol);
            metadata.put("decimals", 18);
            metadata.put("maxSupply", maxSupply);
            byte[] data = new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsBytes(metadata);
            
            return new Transaction.Builder()
                    .type(Transaction.Type.TOKEN_REGISTER)
                    .from(owner.getAddress())
                    .to("")
                    .amount(0)
                    .fee(fee)
                    .nonce(nonce)
                    .data(data)
                    .sign(owner.getPrivateKey(), owner.getPublicKey());
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
