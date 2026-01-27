package com.hybrid.blockchain;

import com.hybrid.blockchain.audit.AuditLogger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for Audit Logging System
 */
public class AuditLoggerTest {

    private AuditLogger auditLogger;
    private String nodeId = "test-node-001";

    @BeforeEach
    public void setUp() {
        auditLogger = new AuditLogger(nodeId);
    }

    @Test
    public void testBasicLogging() {
        auditLogger.log(
                AuditLogger.AuditEventType.TRANSACTION_SUBMITTED,
                "alice",
                "Transaction tx-001 submitted");

        List<AuditLogger.AuditEntry> entries = auditLogger.getAllEntries();
        assertEquals(1, entries.size());

        AuditLogger.AuditEntry entry = entries.get(0);
        assertEquals(AuditLogger.AuditEventType.TRANSACTION_SUBMITTED, entry.getEventType());
        assertEquals("alice", entry.getActor());
        assertTrue(entry.getDetails().contains("tx-001"));
    }

    @Test
    public void testLoggingWithMetadata() {
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("txid", "tx-001");
        metadata.put("amount", 1000);
        metadata.put("from", "alice");
        metadata.put("to", "bob");

        auditLogger.log(
                AuditLogger.AuditEventType.TRANSACTION_VALIDATED,
                "validator-1",
                "Transaction validated successfully",
                metadata);

        List<AuditLogger.AuditEntry> entries = auditLogger.getAllEntries();
        assertEquals(1, entries.size());

        AuditLogger.AuditEntry entry = entries.get(0);
        assertEquals(1000, entry.getMetadata().get("amount"));
        assertEquals("alice", entry.getMetadata().get("from"));
    }

    @Test
    public void testAuditLogChaining() {
        // Log multiple events
        auditLogger.log(AuditLogger.AuditEventType.NODE_STARTED, "system", "Node started");
        auditLogger.log(AuditLogger.AuditEventType.BLOCK_CREATED, "miner-1", "Block 1 created");
        auditLogger.log(AuditLogger.AuditEventType.BLOCK_VALIDATED, "validator-1", "Block 1 validated");

        List<AuditLogger.AuditEntry> entries = auditLogger.getAllEntries();
        assertEquals(3, entries.size());

        // Verify chaining
        assertEquals("0", entries.get(0).getPreviousHash());
        assertEquals(entries.get(0).getHash(), entries.get(1).getPreviousHash());
        assertEquals(entries.get(1).getHash(), entries.get(2).getPreviousHash());
    }

    @Test
    public void testIntegrityVerification() {
        // Log several events
        auditLogger.log(AuditLogger.AuditEventType.DID_REGISTERED, "alice", "DID registered");
        auditLogger.log(AuditLogger.AuditEventType.CREDENTIAL_ISSUED, "issuer", "Credential issued");
        auditLogger.log(AuditLogger.AuditEventType.DEVICE_PROVISIONED, "manufacturer", "Device provisioned");

        // Verify integrity
        assertTrue(auditLogger.verifyIntegrity());
    }

    @Test
    public void testFilterByEventType() {
        auditLogger.log(AuditLogger.AuditEventType.TRANSACTION_SUBMITTED, "alice", "TX 1");
        auditLogger.log(AuditLogger.AuditEventType.BLOCK_CREATED, "miner", "Block 1");
        auditLogger.log(AuditLogger.AuditEventType.TRANSACTION_SUBMITTED, "bob", "TX 2");
        auditLogger.log(AuditLogger.AuditEventType.BLOCK_CREATED, "miner", "Block 2");

        List<AuditLogger.AuditEntry> txEntries = auditLogger.getEntriesByType(
                AuditLogger.AuditEventType.TRANSACTION_SUBMITTED);
        assertEquals(2, txEntries.size());

        List<AuditLogger.AuditEntry> blockEntries = auditLogger.getEntriesByType(
                AuditLogger.AuditEventType.BLOCK_CREATED);
        assertEquals(2, blockEntries.size());
    }

    @Test
    public void testFilterByActor() {
        auditLogger.log(AuditLogger.AuditEventType.TRANSACTION_SUBMITTED, "alice", "TX 1");
        auditLogger.log(AuditLogger.AuditEventType.TRANSACTION_SUBMITTED, "bob", "TX 2");
        auditLogger.log(AuditLogger.AuditEventType.TRANSACTION_SUBMITTED, "alice", "TX 3");

        List<AuditLogger.AuditEntry> aliceEntries = auditLogger.getEntriesByActor("alice");
        assertEquals(2, aliceEntries.size());

        List<AuditLogger.AuditEntry> bobEntries = auditLogger.getEntriesByActor("bob");
        assertEquals(1, bobEntries.size());
    }

    @Test
    public void testFilterByTimeRange() throws InterruptedException {
        long startTime = System.currentTimeMillis();

        auditLogger.log(AuditLogger.AuditEventType.NODE_STARTED, "system", "Start");
        Thread.sleep(10);
        long midTime = System.currentTimeMillis();
        Thread.sleep(10);
        auditLogger.log(AuditLogger.AuditEventType.BLOCK_CREATED, "miner", "Block");
        Thread.sleep(10);
        long endTime = System.currentTimeMillis();

        List<AuditLogger.AuditEntry> allEntries = auditLogger.getEntriesByTimeRange(startTime, endTime);
        assertEquals(2, allEntries.size());

        List<AuditLogger.AuditEntry> firstHalf = auditLogger.getEntriesByTimeRange(startTime, midTime);
        assertEquals(1, firstHalf.size());
    }

    @Test
    public void testStats() {
        auditLogger.log(AuditLogger.AuditEventType.TRANSACTION_SUBMITTED, "alice", "TX 1");
        auditLogger.log(AuditLogger.AuditEventType.TRANSACTION_SUBMITTED, "bob", "TX 2");
        auditLogger.log(AuditLogger.AuditEventType.BLOCK_CREATED, "miner", "Block 1");

        Map<String, Object> stats = auditLogger.getStats();
        assertEquals(3, stats.get("totalEntries"));
        assertEquals(nodeId, stats.get("nodeId"));

        @SuppressWarnings("unchecked")
        Map<AuditLogger.AuditEventType, Integer> typeCounts = (Map<AuditLogger.AuditEventType, Integer>) stats
                .get("eventTypeCounts");
        assertEquals(2, typeCounts.get(AuditLogger.AuditEventType.TRANSACTION_SUBMITTED));
        assertEquals(1, typeCounts.get(AuditLogger.AuditEventType.BLOCK_CREATED));
    }

    @Test
    public void testExportToJSON() {
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("deviceId", "sensor-001");

        auditLogger.log(
                AuditLogger.AuditEventType.DEVICE_PROVISIONED,
                "manufacturer",
                "Device provisioned",
                metadata);

        List<Map<String, Object>> export = auditLogger.exportToJSON();
        assertEquals(1, export.size());

        Map<String, Object> entry = export.get(0);
        assertEquals("DEVICE_PROVISIONED", entry.get("eventType"));
        assertEquals("manufacturer", entry.get("actor"));
        assertNotNull(entry.get("hash"));
        assertNotNull(entry.get("timestamp"));
    }

    @Test
    public void testSecurityEvents() {
        auditLogger.log(
                AuditLogger.AuditEventType.UNAUTHORIZED_ACCESS_ATTEMPT,
                "unknown-user",
                "Attempted to access private collection without authorization");

        auditLogger.log(
                AuditLogger.AuditEventType.SIGNATURE_VERIFICATION_FAILED,
                "alice",
                "Transaction signature verification failed");

        List<AuditLogger.AuditEntry> entries = auditLogger.getAllEntries();
        assertEquals(2, entries.size());

        assertTrue(entries.stream()
                .anyMatch(e -> e.getEventType() == AuditLogger.AuditEventType.UNAUTHORIZED_ACCESS_ATTEMPT));
    }
}
