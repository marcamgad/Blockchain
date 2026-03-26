package com.hybrid.blockchain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatCode;

import java.util.Collections;

@Tag("unit")
public class TelemetryDeviceIdTest {

    @Test
    @DisplayName("Feature: Owner can submit telemetry for their owned devices")
    public void testOwnerTelemetrySubmission() throws Exception {
        Storage storage = new Storage("target/test-telemetry-" + java.util.UUID.randomUUID(), com.hybrid.blockchain.HexUtils.decode("00000000000000000000000000000000"));
        Mempool mempool = new Mempool(100);
        java.util.Map<String, byte[]> vp = new java.util.HashMap<>();
        vp.put("node1", new byte[]{1}); vp.put("n2", new byte[]{2}); vp.put("n3", new byte[]{3}); vp.put("n4", new byte[]{4});
        com.hybrid.blockchain.consensus.PBFTConsensus consensus = new com.hybrid.blockchain.consensus.PBFTConsensus(
            vp, "node1", new java.math.BigInteger("1")
        );
        Blockchain blockchain = new Blockchain(storage, mempool, consensus);
        blockchain.init();

        TestKeyPair owner = new TestKeyPair(1);
        TestKeyPair device = new TestKeyPair(2);

        blockchain.getState().credit(owner.getAddress(), 10000);
        
        com.hybrid.blockchain.lifecycle.DeviceLifecycleManager lcm = org.mockito.Mockito.mock(com.hybrid.blockchain.lifecycle.DeviceLifecycleManager.class);
        java.lang.reflect.Field lcmField = com.hybrid.blockchain.AccountState.class.getDeclaredField("lifecycleManager");
        lcmField.setAccessible(true);
        lcmField.set(blockchain.getState(), lcm);
        
        org.mockito.Mockito.when(lcm.isDeviceOperational(org.mockito.ArgumentMatchers.anyString())).thenReturn(false);
        com.hybrid.blockchain.lifecycle.DeviceLifecycleManager.DeviceRecord record = org.mockito.Mockito.mock(com.hybrid.blockchain.lifecycle.DeviceLifecycleManager.DeviceRecord.class);
        org.mockito.Mockito.when(record.getStatus()).thenReturn(com.hybrid.blockchain.lifecycle.DeviceLifecycleManager.DeviceStatus.ACTIVE);
        org.mockito.Mockito.when(lcm.getDevicesOwnedBy(owner.getAddress())).thenReturn(java.util.Collections.singletonList(record));

        // Submit tx from OWNER address instead of device address
        Transaction tx = new Transaction.Builder()
                .type(Transaction.Type.TELEMETRY)
                .from(owner.getAddress())
                .data("payload".getBytes())
                .fee(500)
                .nonce(1)
                .sign(owner.getPrivateKey(), owner.getPublicKey());

        assertThatCode(() -> blockchain.validateTransaction(tx))
            .doesNotThrowAnyException();
    }
}
