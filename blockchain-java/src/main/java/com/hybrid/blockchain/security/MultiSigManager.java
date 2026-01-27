package com.hybrid.blockchain.security;

import com.hybrid.blockchain.Crypto;
import java.math.BigInteger;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Multi-Signature (MultiSig) Control System
 * 
 * Enables multiple parties to jointly control critical operations:
 * - Device ownership transfers
 * - Firmware updates
 * - Private data collection management
 * - Smart contract execution
 * 
 * Features:
 * - M-of-N signature schemes (e.g., 2-of-3, 3-of-5)
 * - Proposal and approval workflow
 * - Time-based expiration
 * - Signature verification
 */
public class MultiSigManager {

    private final Map<String, MultiSigWallet> wallets;
    private final Map<String, Proposal> proposals;

    public MultiSigManager() {
        this.wallets = new ConcurrentHashMap<>();
        this.proposals = new ConcurrentHashMap<>();
    }

    /**
     * Create a multi-signature wallet
     * 
     * @param walletId           Unique wallet identifier
     * @param owners             List of owner addresses
     * @param requiredSignatures Number of signatures required (M in M-of-N)
     */
    public void createWallet(String walletId, List<String> owners, int requiredSignatures) {
        if (owners.size() < requiredSignatures) {
            throw new IllegalArgumentException("Required signatures cannot exceed number of owners");
        }
        if (requiredSignatures < 1) {
            throw new IllegalArgumentException("Required signatures must be at least 1");
        }

        MultiSigWallet wallet = new MultiSigWallet(walletId, owners, requiredSignatures);
        wallets.put(walletId, wallet);
    }

    /**
     * Create a proposal for multi-sig approval
     * 
     * @param walletId       Wallet that controls this operation
     * @param proposalType   Type of operation
     * @param data           Operation-specific data
     * @param expirationTime When proposal expires (Unix timestamp)
     * @return Proposal ID
     */
    public String createProposal(
            String walletId,
            ProposalType proposalType,
            Map<String, Object> data,
            long expirationTime) {
        MultiSigWallet wallet = wallets.get(walletId);
        if (wallet == null) {
            throw new IllegalArgumentException("Wallet not found: " + walletId);
        }

        String proposalId = generateProposalId(walletId, proposalType, data);
        Proposal proposal = new Proposal(
                proposalId,
                walletId,
                proposalType,
                data,
                expirationTime,
                wallet.getRequiredSignatures());

        proposals.put(proposalId, proposal);
        return proposalId;
    }

    /**
     * Sign a proposal
     * 
     * @param proposalId    Proposal to sign
     * @param signerAddress Address of signer
     * @param privateKey    Private key for signing
     * @param publicKey     Public key for verification
     */
    public void signProposal(
            String proposalId,
            String signerAddress,
            BigInteger privateKey,
            byte[] publicKey) {
        Proposal proposal = proposals.get(proposalId);
        if (proposal == null) {
            throw new IllegalArgumentException("Proposal not found: " + proposalId);
        }

        if (proposal.isExpired()) {
            throw new IllegalStateException("Proposal has expired");
        }

        if (proposal.isExecuted()) {
            throw new IllegalStateException("Proposal already executed");
        }

        MultiSigWallet wallet = wallets.get(proposal.getWalletId());
        if (!wallet.isOwner(signerAddress)) {
            throw new SecurityException("Signer is not a wallet owner");
        }

        if (proposal.hasSigned(signerAddress)) {
            throw new IllegalStateException("Already signed by this address");
        }

        // Create signature
        byte[] message = proposal.getSigningMessage();
        byte[] signature = Crypto.sign(message, privateKey);

        // Verify signature
        if (!Crypto.verify(message, signature, publicKey)) {
            throw new SecurityException("Invalid signature");
        }

        proposal.addSignature(signerAddress, signature);
    }

    /**
     * Check if proposal has enough signatures and can be executed
     */
    public boolean canExecute(String proposalId) {
        Proposal proposal = proposals.get(proposalId);
        if (proposal == null) {
            return false;
        }

        return !proposal.isExpired() &&
                !proposal.isExecuted() &&
                proposal.hasEnoughSignatures();
    }

    /**
     * Execute a proposal (if it has enough signatures)
     * 
     * @return true if executed, false if not enough signatures
     */
    public boolean executeProposal(String proposalId) {
        if (!canExecute(proposalId)) {
            return false;
        }

        Proposal proposal = proposals.get(proposalId);
        proposal.markExecuted();
        return true;
    }

    /**
     * Get proposal details
     */
    public Proposal getProposal(String proposalId) {
        return proposals.get(proposalId);
    }

    /**
     * Get wallet details
     */
    public MultiSigWallet getWallet(String walletId) {
        return wallets.get(walletId);
    }

    /**
     * Get all proposals for a wallet
     */
    public List<Proposal> getProposalsForWallet(String walletId) {
        List<Proposal> walletProposals = new ArrayList<>();
        for (Proposal proposal : proposals.values()) {
            if (proposal.getWalletId().equals(walletId)) {
                walletProposals.add(proposal);
            }
        }
        return walletProposals;
    }

    /**
     * Get statistics
     */
    public Map<String, Object> getStats() {
        Map<String, Object> stats = new HashMap<>();
        stats.put("totalWallets", wallets.size());
        stats.put("totalProposals", proposals.size());

        int activeProposals = 0;
        int executedProposals = 0;
        int expiredProposals = 0;

        for (Proposal proposal : proposals.values()) {
            if (proposal.isExecuted()) {
                executedProposals++;
            } else if (proposal.isExpired()) {
                expiredProposals++;
            } else {
                activeProposals++;
            }
        }

        stats.put("activeProposals", activeProposals);
        stats.put("executedProposals", executedProposals);
        stats.put("expiredProposals", expiredProposals);

        return stats;
    }

    private String generateProposalId(String walletId, ProposalType type, Map<String, Object> data) {
        String combined = walletId + type.name() + System.currentTimeMillis() + data.hashCode();
        return Crypto.bytesToHex(Crypto.hash(combined.getBytes()));
    }

    /**
     * Multi-Signature Wallet
     */
    public static class MultiSigWallet {
        private final String walletId;
        private final List<String> owners;
        private final int requiredSignatures;

        public MultiSigWallet(String walletId, List<String> owners, int requiredSignatures) {
            this.walletId = walletId;
            this.owners = new ArrayList<>(owners);
            this.requiredSignatures = requiredSignatures;
        }

        public boolean isOwner(String address) {
            return owners.contains(address);
        }

        public String getWalletId() {
            return walletId;
        }

        public List<String> getOwners() {
            return new ArrayList<>(owners);
        }

        public int getRequiredSignatures() {
            return requiredSignatures;
        }

        public int getTotalOwners() {
            return owners.size();
        }
    }

    /**
     * Multi-Signature Proposal
     */
    public static class Proposal {
        private final String proposalId;
        private final String walletId;
        private final ProposalType proposalType;
        private final Map<String, Object> data;
        private final long expirationTime;
        private final int requiredSignatures;
        private final Map<String, byte[]> signatures;
        private boolean executed;

        public Proposal(
                String proposalId,
                String walletId,
                ProposalType proposalType,
                Map<String, Object> data,
                long expirationTime,
                int requiredSignatures) {
            this.proposalId = proposalId;
            this.walletId = walletId;
            this.proposalType = proposalType;
            this.data = new HashMap<>(data);
            this.expirationTime = expirationTime;
            this.requiredSignatures = requiredSignatures;
            this.signatures = new ConcurrentHashMap<>();
            this.executed = false;
        }

        public byte[] getSigningMessage() {
            StringBuilder sb = new StringBuilder();
            sb.append(proposalId);
            sb.append(walletId);
            sb.append(proposalType.name());

            // Include data in deterministic order
            List<String> keys = new ArrayList<>(data.keySet());
            Collections.sort(keys);
            for (String key : keys) {
                sb.append(key).append("=").append(data.get(key));
            }

            return Crypto.hash(sb.toString().getBytes());
        }

        public void addSignature(String signer, byte[] signature) {
            signatures.put(signer, signature);
        }

        public boolean hasSigned(String address) {
            return signatures.containsKey(address);
        }

        public boolean hasEnoughSignatures() {
            return signatures.size() >= requiredSignatures;
        }

        public boolean isExpired() {
            return System.currentTimeMillis() > expirationTime;
        }

        public void markExecuted() {
            this.executed = true;
        }

        // Getters
        public String getProposalId() {
            return proposalId;
        }

        public String getWalletId() {
            return walletId;
        }

        public ProposalType getProposalType() {
            return proposalType;
        }

        public Map<String, Object> getData() {
            return new HashMap<>(data);
        }

        public long getExpirationTime() {
            return expirationTime;
        }

        public int getRequiredSignatures() {
            return requiredSignatures;
        }

        public int getCurrentSignatures() {
            return signatures.size();
        }

        public boolean isExecuted() {
            return executed;
        }

        public Set<String> getSigners() {
            return new HashSet<>(signatures.keySet());
        }
    }

    /**
     * Proposal Types
     */
    public enum ProposalType {
        TRANSFER_OWNERSHIP,
        UPDATE_FIRMWARE,
        MODIFY_COLLECTION,
        EXECUTE_CONTRACT,
        ADD_WALLET_OWNER,
        REMOVE_WALLET_OWNER,
        CHANGE_THRESHOLD,
        REVOKE_DEVICE,
        EMERGENCY_STOP
    }
}
