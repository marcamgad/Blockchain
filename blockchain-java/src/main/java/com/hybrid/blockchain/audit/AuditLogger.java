package com.hybrid.blockchain.audit;

import com.hybrid.blockchain.Crypto;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Comprehensive Audit Logging System for Blockchain Operations
 * 
 * Provides tamper-evident logging of all critical operations:
 * - Transaction submissions
 * - Block creation and validation
 * - Device lifecycle events
 * - SSI operations (DID registration, credential issuance)
 * - Private data access
 * - Consensus decisions
 * 
 * Features:
 * - Cryptographic chaining (each log entry references previous)
 * - Immutable append-only log
 * - Structured event data
 * - Query and export capabilities
 */
public class AuditLogger {

    private final Queue<AuditEntry> auditLog;
    private String lastEntryHash;
    private final String nodeId;

    public AuditLogger(String nodeId) {
        this.auditLog = new ConcurrentLinkedQueue<>();
        this.lastEntryHash = "0";
        this.nodeId = nodeId;
    }

    /**
     * Log an audit event
     */
    public synchronized void log(AuditEventType eventType, String actor, String details, Map<String, Object> metadata) {
        AuditEntry entry = new AuditEntry(
                eventType,
                actor,
                details,
                metadata,
                lastEntryHash,
                nodeId);

        auditLog.add(entry);
        lastEntryHash = entry.getHash();

        // Print to console for immediate visibility
        System.out.println("[AUDIT] " + entry.toString());
    }

    /**
     * Log with minimal parameters
     */
    public void log(AuditEventType eventType, String actor, String details) {
        log(eventType, actor, details, new HashMap<>());
    }

    /**
     * Get all audit entries
     */
    public List<AuditEntry> getAllEntries() {
        return new ArrayList<>(auditLog);
    }

    /**
     * Get entries by event type
     */
    public List<AuditEntry> getEntriesByType(AuditEventType eventType) {
        List<AuditEntry> filtered = new ArrayList<>();
        for (AuditEntry entry : auditLog) {
            if (entry.getEventType() == eventType) {
                filtered.add(entry);
            }
        }
        return filtered;
    }

    /**
     * Get entries by actor
     */
    public List<AuditEntry> getEntriesByActor(String actor) {
        List<AuditEntry> filtered = new ArrayList<>();
        for (AuditEntry entry : auditLog) {
            if (entry.getActor().equals(actor)) {
                filtered.add(entry);
            }
        }
        return filtered;
    }

    /**
     * Get entries within time range
     */
    public List<AuditEntry> getEntriesByTimeRange(long startTime, long endTime) {
        List<AuditEntry> filtered = new ArrayList<>();
        for (AuditEntry entry : auditLog) {
            if (entry.getTimestamp() >= startTime && entry.getTimestamp() <= endTime) {
                filtered.add(entry);
            }
        }
        return filtered;
    }

    /**
     * Verify audit log integrity
     * Checks that all entries are properly chained
     */
    public boolean verifyIntegrity() {
        String expectedHash = "0";
        for (AuditEntry entry : auditLog) {
            if (!entry.getPreviousHash().equals(expectedHash)) {
                System.err.println("[AUDIT] Integrity violation: expected previous hash " +
                        expectedHash + " but got " + entry.getPreviousHash());
                return false;
            }

            // Verify entry's own hash
            if (!entry.getHash().equals(entry.calculateHash())) {
                System.err.println("[AUDIT] Integrity violation: entry hash mismatch");
                return false;
            }

            expectedHash = entry.getHash();
        }
        return true;
    }

    /**
     * Get statistics
     */
    public Map<String, Object> getStats() {
        Map<String, Object> stats = new HashMap<>();
        stats.put("totalEntries", auditLog.size());
        stats.put("nodeId", nodeId);
        stats.put("lastEntryHash", lastEntryHash);

        // Count by event type
        Map<AuditEventType, Integer> typeCounts = new HashMap<>();
        for (AuditEntry entry : auditLog) {
            typeCounts.put(entry.getEventType(),
                    typeCounts.getOrDefault(entry.getEventType(), 0) + 1);
        }
        stats.put("eventTypeCounts", typeCounts);

        return stats;
    }

    /**
     * Export audit log to JSON
     */
    public List<Map<String, Object>> exportToJSON() {
        List<Map<String, Object>> export = new ArrayList<>();
        for (AuditEntry entry : auditLog) {
            export.add(entry.toMap());
        }
        return export;
    }

    /**
     * Audit Entry - immutable log record
     */
    public static class AuditEntry {
        private final long timestamp;
        private final AuditEventType eventType;
        private final String actor;
        private final String details;
        private final Map<String, Object> metadata;
        private final String previousHash;
        private final String nodeId;
        private final String hash;

        public AuditEntry(
                AuditEventType eventType,
                String actor,
                String details,
                Map<String, Object> metadata,
                String previousHash,
                String nodeId) {
            this.timestamp = Instant.now().toEpochMilli();
            this.eventType = eventType;
            this.actor = actor;
            this.details = details;
            this.metadata = new HashMap<>(metadata);
            this.previousHash = previousHash;
            this.nodeId = nodeId;
            this.hash = calculateHash();
        }

        public String calculateHash() {
            StringBuilder sb = new StringBuilder();
            sb.append(timestamp);
            sb.append(eventType.name());
            sb.append(actor);
            sb.append(details);
            sb.append(previousHash);
            sb.append(nodeId);

            // Include metadata in hash
            List<String> keys = new ArrayList<>(metadata.keySet());
            Collections.sort(keys);
            for (String key : keys) {
                sb.append(key).append("=").append(metadata.get(key));
            }

            return Crypto.bytesToHex(Crypto.hash(sb.toString().getBytes()));
        }

        public Map<String, Object> toMap() {
            Map<String, Object> map = new HashMap<>();
            map.put("timestamp", timestamp);
            map.put("eventType", eventType.name());
            map.put("actor", actor);
            map.put("details", details);
            map.put("metadata", new HashMap<>(metadata));
            map.put("previousHash", previousHash);
            map.put("nodeId", nodeId);
            map.put("hash", hash);
            return map;
        }

        @Override
        public String toString() {
            return String.format("[%s] %s by %s: %s (hash=%s...)",
                    new Date(timestamp), eventType, actor, details,
                    hash.substring(0, Math.min(8, hash.length())));
        }

        // Getters
        public long getTimestamp() {
            return timestamp;
        }

        public AuditEventType getEventType() {
            return eventType;
        }

        public String getActor() {
            return actor;
        }

        public String getDetails() {
            return details;
        }

        public Map<String, Object> getMetadata() {
            return new HashMap<>(metadata);
        }

        public String getPreviousHash() {
            return previousHash;
        }

        public String getNodeId() {
            return nodeId;
        }

        public String getHash() {
            return hash;
        }
    }

    /**
     * Audit Event Types
     */
    public enum AuditEventType {
        // Transaction events
        TRANSACTION_SUBMITTED,
        TRANSACTION_VALIDATED,
        TRANSACTION_REJECTED,
        TRANSACTION_INCLUDED_IN_BLOCK,

        // Block events
        BLOCK_CREATED,
        BLOCK_VALIDATED,
        BLOCK_REJECTED,
        BLOCK_FINALIZED,

        // Device lifecycle events
        DEVICE_PROVISIONED,
        DEVICE_ACTIVATED,
        DEVICE_SUSPENDED,
        DEVICE_RESUMED,
        DEVICE_REVOKED,
        DEVICE_DECOMMISSIONED,
        FIRMWARE_UPDATED,

        // SSI events
        DID_REGISTERED,
        DID_RESOLVED,
        DID_REVOKED,
        CREDENTIAL_ISSUED,
        CREDENTIAL_VERIFIED,
        CREDENTIAL_REVOKED,

        // Private data events
        PRIVATE_COLLECTION_CREATED,
        PRIVATE_DATA_WRITTEN,
        PRIVATE_DATA_READ,
        PRIVATE_DATA_DELETED,
        MEMBER_ADDED,
        MEMBER_REMOVED,

        // Consensus events
        CONSENSUS_PROPOSAL,
        CONSENSUS_VOTE,
        CONSENSUS_DECISION,
        VIEW_CHANGE,

        // Security events
        UNAUTHORIZED_ACCESS_ATTEMPT,
        AUTHENTICATION_FAILURE,
        SIGNATURE_VERIFICATION_FAILED,

        // System events
        NODE_STARTED,
        NODE_STOPPED,
        STATE_SNAPSHOT_CREATED,
        STATE_RESTORED
    }
}
