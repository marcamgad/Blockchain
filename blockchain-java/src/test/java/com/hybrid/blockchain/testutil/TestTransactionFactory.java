package com.hybrid.blockchain.testutil;

import com.hybrid.blockchain.Transaction;

/**
 * Standardized factory for creating deterministically verifiable transactions during tests.
 */
public class TestTransactionFactory {

    public static Transaction createAccountTransfer(TestKeyPair from, String toAddress, long amount, long fee, long nonce) {
        return new Transaction.Builder()
                .type(Transaction.Type.ACCOUNT)
                .from(from.getAddress())
                .to(toAddress)
                .amount(amount)
                .fee(fee)
                .nonce(nonce)
                .sign(from.getPrivateKey(), from.getPublicKey());
    }

    public static Transaction createContractCreation(TestKeyPair creator, byte[] bytecode, long fee, long nonce) {
        return new Transaction.Builder()
                .type(Transaction.Type.CONTRACT)
                .from(creator.getAddress())
                .to(null) // Null 'to' for contract creation
                .data(bytecode)
                .fee(fee)
                .nonce(nonce)
                .sign(creator.getPrivateKey(), creator.getPublicKey());
    }

    public static Transaction createContractCall(TestKeyPair caller, String contractAddress, byte[] callData, long value, long fee, long nonce) {
        return new Transaction.Builder()
                .type(Transaction.Type.CONTRACT)
                .from(caller.getAddress())
                .to(contractAddress)
                .amount(value)
                .data(callData)
                .fee(fee)
                .nonce(nonce)
                .sign(caller.getPrivateKey(), caller.getPublicKey());
    }
    
    public static Transaction createTokenRegister(TestKeyPair issuer, String symbol, String name, String tokenId, long initialSupply, long fee, long nonce) {
        try {
            java.util.Map<String, Object> metadata = new java.util.HashMap<>();
            metadata.put("tokenId", tokenId);
            metadata.put("name", name);
            metadata.put("symbol", symbol);
            metadata.put("decimals", 18);
            metadata.put("maxSupply", initialSupply);
            byte[] data = new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsBytes(metadata);
            
            return new Transaction.Builder()
                    .type(Transaction.Type.TOKEN_REGISTER)
                    .from(issuer.getAddress())
                    .to("")
                    .amount(0)
                    .fee(fee)
                    .nonce(nonce)
                    .data(data)
                    .sign(issuer.getPrivateKey(), issuer.getPublicKey());
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
