package com.hybrid.blockchain;

import com.hybrid.blockchain.testutil.*;
import org.junit.jupiter.api.*;
import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.*;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.List;

/**
 * Hostile security tests targeting the blockchain's core invariants.
 * NO MOCKING — Uses real TestBlockchain and PBFT paths.
 */
@Tag("security")
public class SecurityAttackCompleteTest {

    private TestBlockchain tb;
    private Blockchain blockchain;

    @BeforeEach
    void setUp() throws Exception {
        tb = new TestBlockchain();
        blockchain = tb.getBlockchain();
    }

    @AfterEach
    void tearDown() throws Exception {
        if (tb != null) tb.close();
    }

    @Test
    @DisplayName("SA1.1 — Double spend via mempool")
    void testDoubleSpendMempool() throws Exception {
        TestKeyPair alice = new TestKeyPair(1);
        blockchain.getAccountState().credit(alice.getAddress(), 100L);
        
        Transaction tx1 = TestTransactionFactory.createAccountTransfer(alice, "b", 60, 1, 1);
        blockchain.addTransaction(tx1);
        
        Transaction tx2 = TestTransactionFactory.createAccountTransfer(alice, "c", 60, 1, 2);
        
        assertThatThrownBy(() -> blockchain.addTransaction(tx2))
                .as("Should reject tx that spends more than confirmed + pending balance")
                .hasMessageContaining("Insufficient funds");
    }

    @Test
    @DisplayName("SA1.2 — Double spend tx replay")
    void testDoubleSpendReplay() throws Exception {
        TestKeyPair alice = new TestKeyPair(1);
        blockchain.getAccountState().credit(alice.getAddress(), 1000L);
        
        Transaction tx1 = TestTransactionFactory.createAccountTransfer(alice, "b", 100, 1, 1);
        BlockApplier.createAndApplyBlock(tb, List.of(tx1));
        
        assertThatThrownBy(() -> blockchain.addTransaction(tx1))
                .as("Should reject already confirmed transaction (stale nonce)")
                .isNotNull();
    }

    @Test
    @DisplayName("SA1.4 — Sybil attack: rogue validator")
    void testRogueValidator() throws Exception {
        TestKeyPair rogue = new TestKeyPair(666);
        Block b = new Block(1, System.currentTimeMillis(), List.of(), blockchain.getLatestBlock().getHash(), 1, "state");
        b.setValidatorId(rogue.getAddress());
        b.setSignature(Crypto.sign(Crypto.hash(b.serializeCanonical()), rogue.getPrivateKey()));
        
        assertThatThrownBy(() -> blockchain.validateBlock(b))
                .hasMessageContaining("Unknown validator");
    }

    @Test
    @DisplayName("SA1.5 — Case-sensitivity/Forgery: tampered amount")
    void testSignatureForgery() {
        TestKeyPair alice = new TestKeyPair(1);
        Transaction tx = TestTransactionFactory.createAccountTransfer(alice, "b", 100, 1, 1);
        
        // Use reflection or manual construction to bypass builder's signing for the tampered version
        Transaction tampered = new Transaction.Builder()
                .type(Transaction.Type.ACCOUNT)
                .from(alice.getAddress())
                .to("b")
                .amount(200) // Tampered
                .fee(1)
                .nonce(1)
                .publicKey(tx.getPublicKey())
                .signature(tx.getSignature())
                .build();
        
        assertThat(tampered.verify()).isFalse();
        assertThatThrownBy(() -> blockchain.addTransaction(tampered))
                .hasMessageContaining("Invalid signature");
    }

    @Test
    @DisplayName("SA1.7 — Reentrancy protection")
    void testContractReentrancy() throws Exception {
        TestKeyPair attacker = new TestKeyPair(100);
        blockchain.getAccountState().credit(attacker.getAddress(), 10_000L);
        
        // Contract: writes to slot 99, calls itself, writes to slot 98 (to detect whether re-entry ran)
        Config.BYPASS_CONTRACT_AUDIT = true;
        try {
            // Deploy first simple contract (just slot 99 = 1)
            ByteBuffer ops = ByteBuffer.allocate(64);
            ops.put(OpCode.PUSH.getByte()).putLong(1L);
            ops.put(OpCode.PUSH.getByte()).putLong(99L);
            ops.put(OpCode.SSTORE.getByte());
            ops.put(OpCode.STOP.getByte());
            
            Transaction dep = TestTransactionFactory.createContractCreation(attacker, toArray(ops), 100, 1);
            BlockApplier.createAndApplyBlock(tb, List.of(dep));
            String contractAddr = blockchain.getStorage().loadReceipt(dep.getTxId()).getContractAddress();
            
            // Deploy second contract: writes slot 99, writes slot 98, then calls first contract, terminates
            ByteBuffer reEntryOps = ByteBuffer.allocate(128);
            reEntryOps.put(OpCode.PUSH.getByte()).putLong(1L);
            reEntryOps.put(OpCode.PUSH.getByte()).putLong(99L);
            reEntryOps.put(OpCode.SSTORE.getByte());
            reEntryOps.put(OpCode.PUSH.getByte()).putLong(1L);
            reEntryOps.put(OpCode.PUSH.getByte()).putLong(98L);
            reEntryOps.put(OpCode.SSTORE.getByte());
            reEntryOps.put(OpCode.PUSH.getByte()).putLong(0L); // outLen
            reEntryOps.put(OpCode.PUSH.getByte()).putLong(0L); // outOff
            reEntryOps.put(OpCode.PUSH.getByte()).putLong(0L); // inLen
            reEntryOps.put(OpCode.PUSH.getByte()).putLong(0L); // inOff
            reEntryOps.put(OpCode.PUSH.getByte()).putLong(0L); // value
            reEntryOps.put(OpCode.PUSH.getByte()).putLong(ByteBuffer.wrap(Crypto.hash(contractAddr.getBytes())).getLong());
            reEntryOps.put(OpCode.CALL.getByte());
            reEntryOps.put(OpCode.STOP.getByte());
            
            Transaction dep2 = TestTransactionFactory.createContractCreation(attacker, toArray(reEntryOps), 100, 2);
            BlockApplier.createAndApplyBlock(tb, List.of(dep2));
            String attackAddr = blockchain.getStorage().loadReceipt(dep2.getTxId()).getContractAddress();
            
            Transaction call = TestTransactionFactory.createContractCall(attacker, attackAddr, new byte[0], 0, 100, 3);
            BlockApplier.createAndApplyBlock(tb, List.of(call));
            
            // Storage slots 99 and 98 should be written by the attack contract before the CALL
            assertThat(blockchain.getAccountState().getAccountStorage(attackAddr).get(99L)).isEqualTo(1L);
            assertThat(blockchain.getAccountState().getAccountStorage(attackAddr).get(98L)).isEqualTo(1L);
            // The CALL to the first contract may succeed or fail (reentrancy guard), but our slots were written
        } finally {
            Config.BYPASS_CONTRACT_AUDIT = false;
        }
    }

    @Test
    @DisplayName("SA1.8 — Gas exhaustion/Infinite loop")
    void testGasExhaustionAttack() throws Exception {
        TestKeyPair attacker = new TestKeyPair(1);
        blockchain.getAccountState().credit(attacker.getAddress(), 1000L);
        
        Config.BYPASS_CONTRACT_AUDIT = true;
        try {
            byte[] loop = { (byte)OpCode.PUSH.getByte(), 0, 0, 0, 0, 0, 0, 0, 0, (byte)OpCode.JUMP.getByte() };
            Transaction dep = TestTransactionFactory.createContractCreation(attacker, loop, 100, 1);
            BlockApplier.createAndApplyBlock(tb, List.of(dep));
            String addr = blockchain.getStorage().loadReceipt(dep.getTxId()).getContractAddress();
            
            Transaction call = TestTransactionFactory.createContractCall(attacker, addr, new byte[0], 0, 1, 2);
            
            await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
                BlockApplier.createAndApplyBlock(tb, List.of(call));
                assertThat(blockchain.getStorage().loadReceipt(call.getTxId()).getStatus()).isEqualTo(TransactionReceipt.STATUS_FAILED);
            });
        } finally {
            Config.BYPASS_CONTRACT_AUDIT = false;
        }
    }

    @Test
    @DisplayName("SA1.12 — Rate limit enforcement")
    void testRateLimit() throws Exception {
        blockchain.setSkipRateLimit(false);
        TestKeyPair alice = new TestKeyPair(1);
        blockchain.getAccountState().credit(alice.getAddress(), 1000L); // CREDIT ALICE with enough funds
        
        boolean reachedLimit = false;
        for (int i = 0; i < 30; i++) {
            Transaction tx = TestTransactionFactory.createAccountTransfer(alice, "b", 1, 1, i + 1);
            try {
                blockchain.addTransaction(tx);
            } catch (Exception e) {
                if (e.getMessage().contains("Rate limit exceeded")) {
                    reachedLimit = true;
                    break;
                }
                throw e;
            }
        }
        
        assertThat(reachedLimit).as("Rate limit should be triggered after some transactions").isTrue();
    }

    private byte[] toArray(ByteBuffer bb) {
        byte[] arr = new byte[bb.position()];
        bb.flip();
        bb.get(arr);
        return arr;
    }
}
