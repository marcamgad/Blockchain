package com.hybrid.blockchain;

import com.hybrid.blockchain.testutil.TestTransactionFactory;
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

    @Test
    @DisplayName("Severe: Simple fork resolution (same height) must work correctly")
    void testSimpleForkResolution() throws Exception {
        java.nio.file.Path tempDir = java.nio.file.Files.createTempDirectory("fork-test");
        try {
            Storage storage = new Storage(tempDir.toString(), HexUtils.decode("00112233445566778899001122334455"));
            Mempool mempool = new Mempool();
            
            TestKeyPair v1 = new TestKeyPair(1);
            TestKeyPair v2 = new TestKeyPair(2);
            TestKeyPair v3 = new TestKeyPair(3);
            
            List<Validator> validators = new java.util.ArrayList<>();
            validators.add(new Validator(v1.getAddress(), v1.getPublicKey()));
            validators.add(new Validator(v2.getAddress(), v2.getPublicKey()));
            validators.add(new Validator(v3.getAddress(), v3.getPublicKey()));
            
            PoAConsensus poa = new PoAConsensus(validators);
            Blockchain chain = new Blockchain(storage, mempool, poa);
            chain.init();

            Config.NODE_ID = v1.getAddress();

            TestKeyPair alice = new TestKeyPair(100);
            chain.getAccountState().credit(alice.getAddress(), 1000);
            
            // 1. Build common prefix (height 1)
            Transaction tx1 = TestTransactionFactory.createAccountTransfer(alice, "bob", 10, 1, 1);
            chain.addTransaction(tx1);
            Block b1 = chain.createBlock(v1.getAddress(), 1);
            poa.signBlock(b1, validators.get(0), v1.getPrivateKey());
            chain.applyBlock(b1);
            
            // 2. Fork side A (height 2)
            AccountState sA = chain.getState().cloneState();
            sA.setBlockHeight(2);
            long rwdA = Tokenomics.getCurrentReward(2, chain.getTotalMinted());
            sA.credit(v2.getAddress(), rwdA);
            
            List<Transaction> txsA = new java.util.ArrayList<>();
            if (rwdA > 0) {
                txsA.add(new Transaction.Builder().type(Transaction.Type.MINT).to(v2.getAddress()).amount(rwdA).build());
            }
            
            Block blockA = new Block(2, System.currentTimeMillis() + 1000, txsA, 
                    b1.getHash(), chain.getDifficulty(), sA.calculateStateRoot());
            poa.signBlock(blockA, validators.get(1), v2.getPrivateKey());
            
            // 3. Fork side B (height 2)
            AccountState sB = chain.getState().cloneState();
            sB.setBlockHeight(2);
            long rwdB = Tokenomics.getCurrentReward(2, chain.getTotalMinted());
            sB.credit(v3.getAddress(), rwdB);
            
            List<Transaction> txsB = new java.util.ArrayList<>();
            if (rwdB > 0) {
                txsB.add(new Transaction.Builder().type(Transaction.Type.MINT).to(v3.getAddress()).amount(rwdB).build());
            }
            
            Block blockB = new Block(2, System.currentTimeMillis() + 2000, txsB, 
                    b1.getHash(), chain.getDifficulty(), sB.calculateStateRoot());
            poa.signBlock(blockB, validators.get(2), v3.getPrivateKey());
            
            // 4. Apply side A
            chain.applyBlock(blockA);
            assertThat(chain.getHeight()).isEqualTo(2);
            assertThat(chain.getLatestBlock().getHash()).isEqualTo(blockA.getHash());
            
            // 5. Apply side B - Trigger handleFork
            chain.applyBlock(blockB);
            
            // Depending on hash comparison, either A or B won
            Block winner = blockA.getHash().compareTo(blockB.getHash()) > 0 ? blockA : blockB;
            
            assertThat(chain.getHeight()).isEqualTo(2);
            assertThat(chain.getLatestBlock().getHash()).isEqualTo(winner.getHash());
        } finally {
            org.apache.commons.io.FileUtils.deleteDirectory(tempDir.toFile());
        }
    }

    @Test
    @DisplayName("Task 12: createBlock() state root must exactly match applyBlock() state root")
    void testCreateBlockStateRootMatchesApplyBlock() throws Exception {
        try (TestBlockchain tb = new TestBlockchain()) {
            Blockchain chain = tb.getBlockchain();
            TestKeyPair miner = tb.getValidatorKey(); // Use an existing validator
            Config.NODE_ID = miner.getAddress();
            
            // Add a test transaction
            TestKeyPair alice = new TestKeyPair(2);
            chain.getAccountState().credit(alice.getAddress(), 1000);
            Transaction tx = TestTransactionFactory.createAccountTransfer(alice, "bob", 100, 10, 1);
            chain.addTransaction(tx);
            
            // 1. Create block (simulates txs and calculates post-state root)
            Block newBlock = chain.createBlock(miner.getAddress(), 10);
            String stateRootFromCreate = newBlock.getStateRoot();
            
            // 2. Sign and commit block so it passes PBFT validation
            byte[] msg = newBlock.serializeCanonical();
            newBlock.setSignature(Crypto.sign(msg, miner.getPrivateKey()));
            tb.getConsensus().markCommitted(newBlock.getHash(), newBlock.getIndex(), miner.getAddress());
            
            // 3. Apply block
            chain.applyBlock(newBlock);
            
            // 4. Check if actual state root after apply matches the one simulated
            String stateRootFromApply = chain.getState().calculateStateRoot();
            assertThat(stateRootFromCreate).isEqualTo(stateRootFromApply);
        }
    }
}
