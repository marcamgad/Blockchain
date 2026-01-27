package com.hybrid.blockchain;

import org.junit.jupiter.api.Test;
import java.util.*;
import java.io.File;
import java.math.BigInteger;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Validates that multiple nodes running the same smart contract
 * reach the same hardware state and state root.
 */
public class IoTEndToEndTest {

    @Test
    public void testMultiNodeConsensus() throws Exception {
        // Cleanup old test data
        deleteDir(new File("test-data-1"));
        deleteDir(new File("test-data-2"));

        // Setup Validator Key
        BigInteger privateKey = new BigInteger("123456789");
        byte[] publicKey = Crypto.derivePublicKey(privateKey);
        Validator v1 = new Validator("V1", publicKey);
        PoAConsensus poa = new PoAConsensus(Collections.singletonList(v1));

        Mempool mem1 = new Mempool();
        Mempool mem2 = new Mempool();

        Blockchain node1 = new Blockchain(new Storage("test-data-1", Config.STORAGE_AES_KEY), mem1, poa);
        Blockchain node2 = new Blockchain(new Storage("test-data-2", Config.STORAGE_AES_KEY), mem2, poa);

        node1.init();

        // Ensure node2 starts from the exact same genesis
        node2.chain.clear();
        node2.chain.add(node1.getLatestBlock());
        // Since state is updated during genesis block creation in init, we should copy
        // it or just re-calc
        // In our case, genesis state is empty.

        // Setup a contract with capabilities
        String contractAddr = "0xCONTRACT";
        node1.getState().ensure(contractAddr);
        node1.getState().getAccountCapabilities(contractAddr).add(new Capability(Capability.Type.WRITE_ACTUATOR, 100L));
        node2.getState().ensure(contractAddr);
        node2.getState().getAccountCapabilities(contractAddr).add(new Capability(Capability.Type.WRITE_ACTUATOR, 100L));

        // PUSH 1 (Value), PUSH 100 (DeviceID), PUSH 2 (WRITE_ACTUATOR), SYSCALL
        java.nio.ByteBuffer code = java.nio.ByteBuffer.allocate(31);
        code.put(OpCode.PUSH.getByte()).putLong(1L);
        code.put(OpCode.PUSH.getByte()).putLong(100L);
        code.put(OpCode.PUSH.getByte()).putLong(2L);
        code.put(OpCode.SYSCALL.getByte());

        Transaction tx = new Transaction.Builder()
                .type(Transaction.Type.CONTRACT)
                .from("alice")
                .to(contractAddr)
                .data(code.array())
                .fee(10)
                .build();

        mem1.add(tx);
        Block b1 = node1.createBlock("V1", 10);
        poa.signBlock(b1, v1, privateKey);

        node1.applyBlock(b1);
        node2.applyBlock(b1);

        assertEquals(node1.getLatestBlock().getStateRoot(), node2.getLatestBlock().getStateRoot());

        // Finality: The block with the transaction is at index 1
        // We need 6 MORE blocks after it for it to be finalized (total chain size = 8)
        // Currently chain size is 2 (genesis + block with tx)
        // So we need 6 more blocks to reach finality
        for (int i = 0; i < 6; i++) {
            Block b = node1.createBlock("V1", 0);
            poa.signBlock(b, v1, privateKey);
            node1.applyBlock(b);
            node2.applyBlock(b);
        }

        // Now chain size is 8, so block at index 1 has 6 confirmations
        // Hardware actions should be committed
        assertEquals(1L, node1.getHardwareManager().getActuatorState(100L),
                "Actuator state should be 1 after finality");
        assertEquals(1L, node2.getHardwareManager().getActuatorState(100L),
                "Both nodes should have same actuator state");

        node1.getStorage().close();
        node2.getStorage().close();
    }

    private void deleteDir(File file) {
        File[] contents = file.listFiles();
        if (contents != null) {
            for (File f : contents) {
                deleteDir(f);
            }
        }
        file.delete();
    }
}
