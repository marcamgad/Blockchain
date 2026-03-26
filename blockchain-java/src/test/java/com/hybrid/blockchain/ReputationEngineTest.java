package com.hybrid.blockchain;

import com.hybrid.blockchain.consensus.PBFTConsensus;
import com.hybrid.blockchain.testutil.TestKeyPair;
import com.hybrid.blockchain.lifecycle.DeviceLifecycleManager;
import com.hybrid.blockchain.lifecycle.DeviceLifecycleManager.DeviceRecord;
import com.hybrid.blockchain.lifecycle.DeviceLifecycleManager.DeviceStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

public class ReputationEngineTest {

    private Blockchain blockchain;
    private Storage storage;
    private PBFTConsensus consensus;
    private Mempool mempool;

    @BeforeEach
    void setUp() throws Exception {
        storage = new Storage("target/test-db-" + java.util.UUID.randomUUID().toString());
        mempool = new Mempool(100);
        consensus = Mockito.mock(PBFTConsensus.class);
        
        when(consensus.isValidator(any())).thenReturn(true);
        when(consensus.getValidators()).thenReturn(new ArrayList<>(Collections.singletonList(new Validator("ValidatorA", new byte[33]))));
        when(consensus.verifyBlock(any(), any())).thenReturn(true);

        blockchain = new Blockchain(storage, mempool, consensus);
        blockchain.init();
        
        com.hybrid.blockchain.AccountState stateSpy = Mockito.spy(blockchain.getState());
        java.lang.reflect.Field stateField = Blockchain.class.getDeclaredField("state");
        stateField.setAccessible(true);
        stateField.set(blockchain, stateSpy);
        Mockito.doReturn("dummy_root").when(stateSpy).calculateStateRoot();
    }

    @Test
    void testReputationActivityAndInactivity() throws Exception {
        TestKeyPair deviceKey = new TestKeyPair(1);
        String deviceId = deviceKey.getAddress();
        String owner = "0xOwnerAddress";
        
        // 1. Register device via reflection to bypass crypto/provisioning in this test
        DeviceRecord record = new DeviceRecord(deviceId, "TestMaker", "ModelX");
        record.setStatus(DeviceStatus.ACTIVE);
        record.setOwner(owner);
        record.setReputationScore(0.5);
        
        java.lang.reflect.Field registryField = DeviceLifecycleManager.class.getDeclaredField("deviceRegistry");
        registryField.setAccessible(true);
        @SuppressWarnings("unchecked")
        java.util.Map<String, DeviceRecord> registry = (java.util.Map<String, DeviceRecord>) registryField.get(blockchain.getState().getLifecycleManager());
        registry.put(deviceId, record);
        
        // Fund device account to pay fees
        blockchain.getState().credit(deviceId, 100000);
        
        // 2. Propose a block with TELEMETRY transaction
        Transaction tx = new Transaction.Builder()
                .type(Transaction.Type.TELEMETRY)
                .from(deviceId)
                .data("telemetry_payload".getBytes())
                .nonce(blockchain.getState().getNonce(deviceId) + 1)
                .fee(50000)
                .sign(deviceKey.getPrivateKey(), deviceKey.getPublicKey());
        
        Block block1 = new Block(1, System.currentTimeMillis(), Collections.singletonList(tx), 
                blockchain.getLatestBlock().getHash(), blockchain.getDifficulty(), "dummy_root");
        block1.setValidatorId("ValidatorA");
        block1.setSignature(new byte[64]);
        
        blockchain.applyBlock(block1);
        
        // Verify score increased (0.5 + 0.05 = 0.55)
        DeviceRecord updated = blockchain.getState().getLifecycleManager().getDeviceRecord(deviceId);
        assertThat(updated.getReputationScore()).isGreaterThan(0.5);
        double scoreAfterActivity = updated.getReputationScore();
        
        // 3. Propose many empty blocks to simulate inactivity
        for (int i = 0; i < 120; i++) {
            Block empty = new Block((int)blockchain.getHeight() + 1, System.currentTimeMillis(), new ArrayList<Transaction>(), 
                    blockchain.getLatestBlock().getHash(), blockchain.getDifficulty(), "dummy_root");
            empty.setValidatorId("ValidatorA");
            empty.setSignature(new byte[64]);
            blockchain.applyBlock(empty);
        }
        
        // 4. Propose ONE MORE telemetry transaction to trigger the lazy penalty calculation
        Transaction tx2 = new Transaction.Builder()
                .type(Transaction.Type.TELEMETRY)
                .from(deviceId)
                .data("telemetry_payload_2".getBytes())
                .nonce(blockchain.getState().getNonce(deviceId) + 1)
                .fee(50000)
                .sign(deviceKey.getPrivateKey(), deviceKey.getPublicKey());
        
        Block block2 = new Block((int)blockchain.getHeight() + 1, System.currentTimeMillis(), Collections.singletonList(tx2), 
                blockchain.getLatestBlock().getHash(), blockchain.getDifficulty(), "dummy_root");
        block2.setValidatorId("ValidatorA");
        block2.setSignature(new byte[64]);
        
        blockchain.applyBlock(block2);

        // Verify score decreased due to penalty overcoming the 0.01 increment
        updated = blockchain.getState().getLifecycleManager().getDeviceRecord(deviceId);
        assertThat(updated.getReputationScore()).isLessThan(scoreAfterActivity);
    }
}
