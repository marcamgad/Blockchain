package com.hybrid.blockchain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import java.util.ArrayList;
import java.util.List;
import static org.assertj.core.api.Assertions.*;

@Tag("integration")
public class BlockchainCoreTest {

    @Test
    @DisplayName("Invariant: Valid transactions must flow from mempool to ledger via consensus")
    void testEndToEndLedgerFlow() throws Exception {
        try (TestBlockchain tb = new TestBlockchain()) {
            Blockchain chain = tb.getBlockchain();
            TestKeyPair alice = new TestKeyPair(100);
            TestKeyPair bob = new TestKeyPair(200);
            
            // 1. Give Alice some native tokens (Genesis or direct credit for test)
            chain.getAccountState().credit(alice.getAddress(), 1000);
            
            // 2. Alice sends tokens to Bob
            Transaction tx = TestTransactionFactory.createAccountTransfer(
                    alice, bob.getAddress(), 400, 10, 1);
            
            chain.addTransaction(tx);
            assertThat(chain.getMempool().size()).isEqualTo(1);
            
            // 3. Create and apply block via PBFT quorum (using BlockApplier)
            List<Transaction> txs = new ArrayList<>();
            txs.add(tx);
            Block block = BlockApplier.createAndApplyBlock(tb, txs);
            
            // 4. Verify ledger state
            assertThat(chain.getLatestBlock().getHash()).isEqualTo(block.getHash());
            assertThat(chain.getMempool().size()).isEqualTo(0);
            assertThat(chain.getAccountState().getBalance(alice.getAddress())).isEqualTo(590); // 1000 - 400 - 10
            assertThat(chain.getAccountState().getBalance(bob.getAddress())).isEqualTo(400);
            assertThat(chain.getAccountState().getNonce(alice.getAddress())).isEqualTo(1);
        }
    }

    @Test
    @DisplayName("Security: Invalid transaction (wrong signature) must be rejected by Blockchain")
    void testInvalidTransactionRejection() throws Exception {
        try (TestBlockchain tb = new TestBlockchain()) {
            Blockchain chain = tb.getBlockchain();
            TestKeyPair attacker = new TestKeyPair(666);
            
            // Transaction signed by attacker but claiming to be from someone else
            Transaction tx = new Transaction.Builder()
                    .type(Transaction.Type.ACCOUNT)
                    .from("hbVictim")
                    .to("hbAttacker")
                    .amount(1000)
                    .nonce(1)
                    .sign(attacker.getPrivateKey(), attacker.getPublicKey());
            
            assertThatThrownBy(() -> chain.addTransaction(tx))
                    .as("Blockchain should reject invalid transaction signature")
                    .isInstanceOf(Exception.class);
            
            assertThat(chain.getMempool().size()).isEqualTo(0);
        }
    }

    @Test
    @DisplayName("Security: Replay attack (same nonce) must be rejected")
    void testReplayAttackPrevention() throws Exception {
        try (TestBlockchain tb = new TestBlockchain()) {
            Blockchain chain = tb.getBlockchain();
            TestKeyPair alice = new TestKeyPair(1);
            chain.getAccountState().credit(alice.getAddress(), 1000);
            
            Transaction tx = TestTransactionFactory.createAccountTransfer(alice, "bob", 10, 1, 1);
            chain.addTransaction(tx);
            
            // Apply in block
            List<Transaction> txs = new ArrayList<>();
            txs.add(tx);
            BlockApplier.createAndApplyBlock(tb, txs);
            
            // Try to add same tx again (same ID, same everything)
            assertThatThrownBy(() -> chain.addTransaction(tx))
                    .isInstanceOf(Exception.class);
            
            // Try to add different tx with SAME nonce
            Transaction replayTx = TestTransactionFactory.createAccountTransfer(alice, "charlie", 10, 1, 1);
            assertThatThrownBy(() -> chain.addTransaction(replayTx))
                    .as("Blockchain should reject duplicate nonce from same account")
                    .isInstanceOf(Exception.class);
        }
    }
}
