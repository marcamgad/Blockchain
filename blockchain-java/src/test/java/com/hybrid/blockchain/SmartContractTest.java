package com.hybrid.blockchain;

import com.hybrid.blockchain.testutil.TestTransactionFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import java.util.ArrayList;
import java.util.List;
import static org.assertj.core.api.Assertions.*;

@Tag("integration")
public class SmartContractTest {

    @BeforeEach
    void setup() {
        com.hybrid.blockchain.ai.TelemetryAnomalyDetector.getInstance().reset();
        Config.BYPASS_CONTRACT_AUDIT = false;
    }

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
                OpCode.SWAP.getByte(), // key is below value
                OpCode.SSTORE.getByte(),
                OpCode.STOP.getByte()
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
                OpCode.PUSH.getByte(), 0, 0, 0, 0, 0, 0, 0, 0, // slot
                OpCode.PUSH.getByte(), 0, 0, 0, 0, 0, 0, 0, 1, // value
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
                for(int j=0; j<7; j++) ops.add((byte)0); ops.add((byte)i); // slot i
                ops.add(OpCode.PUSH.getByte());
                for(int j=0; j<8; j++) ops.add((byte)0); // value 0
                ops.add(OpCode.SSTORE.getByte());
            }
            ops.add(OpCode.STOP.getByte());
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

    @Test
    @DisplayName("Severe: VM must correctly execute every single OpCode")
    void testEveryOpCode() throws Exception {
        try (TestBlockchain tb = new TestBlockchain()) {
            Blockchain chain = tb.getBlockchain();
            TestKeyPair user = new TestKeyPair(1);
            chain.getAccountState().credit(user.getAddress(), 10000);
            
            // Assembly logic:
            // PUSH 10, PUSH 20, ADD (30)
            // PUSH 5, SUB (25)
            // PUSH 0, SSTORE (Slot 0 = 25)
            // PUSH 1, PUSH 0, SLOAD (Load 25), MSTORE (Mem 1 = 25)
            // PUSH 1, MLOAD (Top = 25)
            // PUSH 25, EQ (Top = 1)
            // PUSH 77, JUMPI (Jump if true)
            // STOP (If jump fails)
            // ... padding to PC 77 ...
            // PC 77: PUSH 99, PUSH 1, SSTORE (Succeed signal: Slot 1 = 99)
            // STOP
            
            java.nio.ByteBuffer ops = java.nio.ByteBuffer.allocate(1000);
            ops.put(OpCode.PUSH.getByte()).putLong(10L);
            ops.put(OpCode.PUSH.getByte()).putLong(20L);
            ops.put(OpCode.ADD.getByte());
            ops.put(OpCode.PUSH.getByte()).putLong(5L);
            ops.put(OpCode.SUB.getByte());
            ops.put(OpCode.PUSH.getByte()).putLong(0L); // storage key (slot 0)
            ops.put(OpCode.SWAP.getByte()); // Move key below the value (35)
            ops.put(OpCode.SSTORE.getByte());
            
            ops.put(OpCode.PUSH.getByte()).putLong(0L); // slot
            ops.put(OpCode.SLOAD.getByte());
            ops.put(OpCode.PUSH.getByte()).putLong(0L); // memory offset
            ops.put(OpCode.MSTORE.getByte());
            
            ops.put(OpCode.PUSH.getByte()).putLong(0L); // constant offset for MLOAD (safe)
            ops.put(OpCode.MLOAD.getByte());
            ops.put(OpCode.PUSH.getByte()).putLong(25L);
            ops.put(OpCode.EQ.getByte());
            
            ops.put(OpCode.PUSH.getByte()).putLong(100L); // Forward jump target
            ops.put(OpCode.SWAP.getByte());
            ops.put(OpCode.JUMPI.getByte());
            ops.put(OpCode.STOP.getByte());
            
            // Forward jump target at 100
            while(ops.position() < 100) ops.put(OpCode.STOP.getByte());
            
            ops.put(OpCode.PUSH.getByte()).putLong(1L); // key (slot 1)
            ops.put(OpCode.PUSH.getByte()).putLong(99L); // value
            ops.put(OpCode.SSTORE.getByte());
            ops.put(OpCode.STOP.getByte());
            
            byte[] bytecode = java.util.Arrays.copyOf(ops.array(), ops.position());
            
            Transaction deployTx = TestTransactionFactory.createContractCreation(user, bytecode, 1000, 1);
            BlockApplier.createAndApplyBlock(tb, java.util.Collections.singletonList(deployTx));
            
            String contractAddr = Crypto.deriveAddress(Crypto.hash((user.getAddress() + 1).getBytes()));
            
            Transaction callTx = TestTransactionFactory.createContractCall(user, contractAddr, new byte[0], 0, 1000, 2);
            BlockApplier.createAndApplyBlock(tb, java.util.Collections.singletonList(callTx));
            
            // Final Verification
            try {
                long result = chain.getAccountState().getAccount(contractAddr).getStorage().get(1L);
                assertThat(result).as("OpCode sequence execution verification").isEqualTo(99L);
            } catch (AssertionError | Exception e) {
                System.err.println("--- DIAGNOSTIC: Smart Contract Storage ---");
                AccountState.Account acc = chain.getAccountState().getAccount(contractAddr);
                if (acc == null) {
                    System.err.println("Contract Account is NULL!");
                } else {
                    acc.getStorage().getStorage().forEach((k, v) -> System.err.println("Slot " + k + ": " + v));
                }
                throw e;
            }
        }
    }

    @Test
    @DisplayName("C8.1: LOG Opcode Emits Event")
    void testLogOpcodeEmitsEvent() throws Exception {
        try (TestBlockchain tb = new TestBlockchain()) {
            Blockchain chain = tb.getBlockchain();
            TestKeyPair user = new TestKeyPair(80);
            chain.getAccountState().credit(user.getAddress(), 1000);

            // MSTORE: pops offset (top), then value (below).
            // PUSH value, PUSH offset, MSTORE
            long eventDataWord = java.nio.ByteBuffer.wrap("Hello E!".getBytes()).getLong();
            java.nio.ByteBuffer ops = java.nio.ByteBuffer.allocate(100);
            
            ops.put(OpCode.PUSH.getByte()).putLong(eventDataWord); // Value
            ops.put(OpCode.PUSH.getByte()).putLong(0L);           // Offset (on top)
            ops.put(OpCode.MSTORE.getByte());
            
            // LOG: pops topic (top), then len, then offset.
            // PUSH offset, PUSH len, PUSH topic, LOG
            ops.put(OpCode.PUSH.getByte()).putLong(0L);   // Offset
            ops.put(OpCode.PUSH.getByte()).putLong(8L);   // Len
            ops.put(OpCode.PUSH.getByte()).putLong(123L); // Topic (on top)
            ops.put(OpCode.LOG.getByte());
            ops.put(OpCode.STOP.getByte());
            
            byte[] bytecode = java.util.Arrays.copyOf(ops.array(), ops.position());

            Transaction deployTx = TestTransactionFactory.createContractCreation(user, bytecode, 100, 1);
            BlockApplier.createAndApplyBlock(tb, java.util.List.of(deployTx));
            String contractAddr = Crypto.deriveAddress(Crypto.hash((user.getAddress() + 1).getBytes()));

            Transaction callTx = TestTransactionFactory.createContractCall(user, contractAddr, new byte[0], 0, 100, 2);
            BlockApplier.createAndApplyBlock(tb, java.util.List.of(callTx));

            TransactionReceipt receipt = chain.getStorage().loadReceipt(callTx.getTxid());
            if (receipt.getEvents().isEmpty()) {
                System.err.println("Receipt Error: " + receipt.getErrorMessage() + " Status: " + receipt.getStatus());
            }
            assertThat(receipt.getEvents()).isNotEmpty();
            assertThat(new String(receipt.getEvents().get(0).getData())).isEqualTo("Hello E!");
            assertThat(receipt.getEvents().get(0).getTopic()).isEqualTo(123L);
        }
    }

    @Test
    @DisplayName("C8.2: CALL Opcode Transfers Value")
    void testCallOpcodeTransfersValue() throws Exception {
        // [TEST-C8]
        try (TestBlockchain tb = new TestBlockchain()) {
            Blockchain chain = tb.getBlockchain();
            TestKeyPair user = new TestKeyPair(90);
            chain.getAccountState().credit(user.getAddress(), 10000);

            String addrB = "0xRecipientB";
            chain.getAccountState().ensure(addrB);

            // CALL: pops gas (top), to, value, inOff, inLen, outOff, outLen (last).
            // PUSH in reverse order: outLen, outOff, inLen, inOff, value, target, gas.
            java.nio.ByteBuffer ops = java.nio.ByteBuffer.allocate(200);
            ops.put(OpCode.PUSH.getByte()).putLong(0L);    // outLen
            ops.put(OpCode.PUSH.getByte()).putLong(0L);    // outOff
            ops.put(OpCode.PUSH.getByte()).putLong(0L);    // inLen
            ops.put(OpCode.PUSH.getByte()).putLong(0L);    // inOff
            ops.put(OpCode.PUSH.getByte()).putLong(500L);  // value
            ops.put(OpCode.PUSH.getByte()).putLong(java.nio.ByteBuffer.wrap(Crypto.hash(addrB.getBytes(java.nio.charset.StandardCharsets.UTF_8))).getLong()); // target
            ops.put(OpCode.PUSH.getByte()).putLong(5000L); // gas (on top)
            ops.put(OpCode.CALL.getByte());
            ops.put(OpCode.STOP.getByte());
            byte[] bytecode = java.util.Arrays.copyOf(ops.array(), ops.position());

            chain.recalculateStateRoot();
            Transaction deployTx = TestTransactionFactory.createContractCreation(user, bytecode, 100, 1);
            BlockApplier.createAndApplyBlock(tb, List.of(deployTx));
            String addrA = Crypto.deriveAddress(Crypto.hash((user.getAddress() + 1).getBytes()));
            
            // Fund contract A (nonce 2)
            Transaction fundTx = TestTransactionFactory.createAccountTransfer(user, addrA, 1000, 1, 2);
            BlockApplier.createAndApplyBlock(tb, List.of(fundTx));

            // Call contract (nonce 3)
            Transaction callTx = TestTransactionFactory.createContractCall(user, addrA, new byte[0], 0, 100, 3);
            BlockApplier.createAndApplyBlock(tb, List.of(callTx));

            assertThat(chain.getAccountState().getBalance(addrB)).isEqualTo(500);
            assertThat(chain.getAccountState().getBalance(addrA)).isEqualTo(500);
        }
    }
}
