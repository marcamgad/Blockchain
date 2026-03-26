package com.hybrid.blockchain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

@Tag("unit")
public class AppWiringTest {

    @Test
    @DisplayName("Wiring: Verify all core components can be injected into Blockchain")
    public void testBlockchainComponentWiring() throws Exception {
        Storage storage = mock(Storage.class);
        Mempool mempool = mock(Mempool.class);
        com.hybrid.blockchain.consensus.PBFTConsensus consensus = mock(com.hybrid.blockchain.consensus.PBFTConsensus.class);

        Blockchain blockchain = new Blockchain(storage, mempool, consensus);
        
        com.hybrid.blockchain.monitoring.BlockchainMonitor monitor = new com.hybrid.blockchain.monitoring.BlockchainMonitor("testNode");
        com.hybrid.blockchain.audit.AuditLogger auditLogger = new com.hybrid.blockchain.audit.AuditLogger("testNode");
        com.hybrid.blockchain.api.EventBus eventBus = new com.hybrid.blockchain.api.EventBus();

        blockchain.setMonitor(monitor);
        blockchain.setAuditLogger(auditLogger);
        blockchain.setEventBus(eventBus);

        assertThat(blockchain.getMonitor()).isNotNull().isSameAs(monitor);
        assertThat(blockchain.getAuditLogger()).isNotNull().isSameAs(auditLogger);
        assertThat(blockchain.getEventBus()).isNotNull().isSameAs(eventBus);
    }
}
