package com.hybrid.blockchain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import java.util.ArrayList;
import java.util.List;
import static org.assertj.core.api.Assertions.*;

@Tag("integration")
public class SmartContractTest {

    @Test
    @DisplayName("Invariant: Smart contract must persist state across multiple calls")
    void testContractStatePersistence() throws Exception {
        try (TestBlockchain tb = new TestBlockchain()) {
            Blockchain chain = tb.getBlockchain();
            TestKeyPair user = new TestKeyPair(1);
            chain.getAccountState().credit(user.getAddress(), 10000);
            
            // 1. Deploy contract (Simple counter contract)
            // OpCode: PUSH 0, SLOAD, PUSH 1, ADD, PUSH 0, SSTORE (Increment slot 0)
            byte[] bytecode = new byte[] {
                OpCode.PUSH.getByte(), 0, 0, 0, 0, 0, 0, 0, 0, // slot 0
                OpCode.SLOAD.getByte(),
                OpCode.PUSH.getByte(), 0, 0, 0, 0, 0, 0, 0, 1, // value 1
                OpCode.ADD.getByte(),
                OpCode.PUSH.getByte(), 0, 0, 0, 0, 0, 0, 0, 0, // slot 0
                OpCode.SSTORE.getByte()
            };
            
            Transaction deployTx = TestTransactionFactory.createContractCreation(user, bytecode, 100, 1);
            BlockApplier.createAndApplyBlock(tb, java.util.Collections.singletonList(deployTx));
            
            TransactionReceipt deployReceipt = chain.getStorage().loadReceipt(deployTx.getTxid());
            assertThat(deployReceipt).as("Deployment receipt must exist").isNotNull();
            assertThat(deployReceipt.getStatus()).as("Deployment must succeed").isEqualTo(TransactionReceipt.STATUS_SUCCESS);

            String creator = user.getAddress();
            long nonce = 1; 
            String contractAddr = Crypto.deriveAddress(Crypto.hash((creator + nonce).getBytes()));
            
            // 2. Initial call (SSTORE 1 to slot 0)
            Transaction call1 = TestTransactionFactory.createContractCall(user, contractAddr, new byte[0], 0, 100, 2);
            BlockApplier.createAndApplyBlock(tb, java.util.Collections.singletonList(call1));
            
            long val1 = chain.getAccountState().getAccount(contractAddr).getStorage().get(0);
            assertThat(val1).isEqualTo(1);
            
            // 3. Second call (SSTORE 2 to slot 0)
            Transaction call2 = TestTransactionFactory.createContractCall(user, contractAddr, new byte[0], 0, 100, 3);
            BlockApplier.createAndApplyBlock(tb, java.util.Collections.singletonList(call2));
            
            long val2 = chain.getAccountState().getAccount(contractAddr).getStorage().get(0);
            assertThat(val2).isEqualTo(2);
        }
    }

    @Test
    @DisplayName("Security: VM must revert state changes on execution failure (Gas exhaustion)")
    void testContractRevertOnFailure() throws Exception {
        try (TestBlockchain tb = new TestBlockchain()) {
            Blockchain chain = tb.getBlockchain();
            TestKeyPair user = new TestKeyPair(1);
            chain.getAccountState().credit(user.getAddress(), 1000);
            
            // OpCode: PUSH 1, PUSH 0, SSTORE, PUSH 0, PUSH 0, REVERT
            byte[] bytecode = new byte[] {
                OpCode.PUSH.getByte(), 0, 0, 0, 0, 0, 0, 0, 1, // value
                OpCode.PUSH.getByte(), 0, 0, 0, 0, 0, 0, 0, 0, // slot
                OpCode.SSTORE.getByte(),
                OpCode.PUSH.getByte(), 0, 0, 0, 0, 0, 0, 0, 0, // offset
                OpCode.PUSH.getByte(), 0, 0, 0, 0, 0, 0, 0, 0, // length
                OpCode.REVERT.getByte()
            };
            
            Transaction deployTx = TestTransactionFactory.createContractCreation(user, bytecode, 10, 1);
            BlockApplier.createAndApplyBlock(tb, java.util.Collections.singletonList(deployTx));
            
            String creator = user.getAddress();
            long nonce = 1; 
            String contractAddr = Crypto.deriveAddress(Crypto.hash((creator + nonce).getBytes()));
            
            // Call contract
            Transaction callTx = TestTransactionFactory.createContractCall(user, contractAddr, new byte[0], 0, 10, 2);
            BlockApplier.createAndApplyBlock(tb, java.util.Collections.singletonList(callTx));
            
            // Verify receipt shows failure and storage is EMPTY (reverted)
            TransactionReceipt receipt = chain.getStorage().loadReceipt(callTx.getTxid());
            assertThat(receipt.getStatus()).isEqualTo(TransactionReceipt.STATUS_REVERTED);
            
            long val = chain.getAccountState().getAccount(contractAddr).getStorage().get(0);
            assertThat(val).isEqualTo(0).as("Storage must be empty after REVERT");
        }
    }

    @Test
    @DisplayName("Security: VM must enforce gas limits strictly")
    void testGasLimitEnforcement() throws Exception {
        try (TestBlockchain tb = new TestBlockchain()) {
            Blockchain chain = tb.getBlockchain();
            TestKeyPair user = new TestKeyPair(1);
            chain.getAccountState().credit(user.getAddress(), 1000);
            
            // We use many SSTOREs which are expensive
            List<Byte> ops = new java.util.ArrayList<>();
            for (int i = 0; i < 100; i++) {
                ops.add(OpCode.PUSH.getByte());
                for(int j=0; j<8; j++) ops.add((byte)0); // value 0
                ops.add(OpCode.PUSH.getByte());
                for(int j=0; j<7; j++) ops.add((byte)0); ops.add((byte)i); // slot i
                ops.add(OpCode.SSTORE.getByte());
            }
            byte[] bytecode = new byte[ops.size()];
            for(int i=0; i<ops.size(); i++) bytecode[i] = ops.get(i);
            
            // Deploy expensive contract
            Transaction deployTx = TestTransactionFactory.createContractCreation(user, bytecode, 100, 1);
            BlockApplier.createAndApplyBlock(tb, java.util.Collections.singletonList(deployTx));
            
            String creator = user.getAddress();
            long nonce = 1; 
            String contractAddr = Crypto.deriveAddress(Crypto.hash((creator + nonce).getBytes()));

            // Call with insufficient fee (5 tokens = 5000 gas, but 100 SSTOREs = 500,000 gas)
            Transaction callTx = TestTransactionFactory.createContractCall(user, contractAddr, new byte[0], 0, 5, 2);
            BlockApplier.createAndApplyBlock(tb, java.util.Collections.singletonList(callTx));

            TransactionReceipt receipt = chain.getStorage().loadReceipt(callTx.getTxid());
            assertThat(receipt.getStatus()).isEqualTo(TransactionReceipt.STATUS_FAILED).as("Should fail due to out-of-gas");
        }
    }
}
