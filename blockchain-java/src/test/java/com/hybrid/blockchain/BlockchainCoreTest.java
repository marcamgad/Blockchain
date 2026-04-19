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
    @DisplayName("Severe: Deep fork resolution (5+ blocks) must work correctly")
    void testDeepForkResolution() throws Exception {
        // Use a manual setup with PoAConsensus for simple fork tests
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

            // Align node identity with first validator to ensure consistent miner credit
            Config.NODE_ID = v1.getAddress();

            TestKeyPair alice = new TestKeyPair(100);
            chain.getAccountState().credit(alice.getAddress(), 1000);
            
            // 1. Build common prefix (height 1)
            Transaction tx1 = TestTransactionFactory.createAccountTransfer(alice, "bob", 10, 1, 1);
            chain.addTransaction(tx1);
            Block b1 = chain.createBlock(v1.getAddress(), 1);
            poa.signBlock(b1, validators.get(0), v1.getPrivateKey());
            chain.applyBlock(b1);
            
            // 2. Pre-calculate Fork side A (height 2-4)
            List<Block> forkA = new java.util.ArrayList<>();
            forkA.add(b1);
            String lastHashA = b1.getHash();
            long simMintedA = 1050; // genesis + b1
            for (int i = 0; i < 3; i++) {
                long h = i + 2;
                AccountState s = chain.getState().cloneState();
                s.setBlockHeight(h);
                long rwd = Tokenomics.getCurrentReward(h, simMintedA);
                s.credit(v2.getAddress(), rwd); // reward only (totalFees=0)
                
                Block b = new Block((int)h, System.currentTimeMillis() + i*1000, new java.util.ArrayList<>(), 
                        lastHashA, chain.getDifficulty(), s.calculateStateRoot());
                poa.signBlock(b, validators.get(1), v2.getPrivateKey());
                forkA.add(b);
                lastHashA = b.getHash();
                simMintedA += rwd;
            }
            
            // 3. Pre-calculate Fork side B (height 2-6) - Longer chain
            List<Block> forkB = new java.util.ArrayList<>();
            forkB.add(b1);
            String lastHashB = b1.getHash();
            long simMintedB = 1050;
            for (int i = 0; i < 5; i++) {
                long h = i + 2;
                AccountState s = chain.getState().cloneState();
                s.setBlockHeight(h);
                long rwd = Tokenomics.getCurrentReward(h, simMintedB);
                s.credit(v3.getAddress(), rwd);
                
                Block b = new Block((int)h, System.currentTimeMillis() + i*1000 + 500, new java.util.ArrayList<>(), 
                        lastHashB, chain.getDifficulty(), s.calculateStateRoot());
                poa.signBlock(b, validators.get(2), v3.getPrivateKey());
                forkB.add(b);
                lastHashB = b.getHash();
                simMintedB += rwd;
            }
            
            // 4. Apply side A
            for (int i = 1; i < forkA.size(); i++) chain.applyBlock(forkA.get(i));
            assertThat(chain.getHeight()).isEqualTo(4);
            assertThat(chain.getLatestBlock().getHash()).isEqualTo(forkA.get(3).getHash());
            
            // 5. Apply side B (longer) - Trigger Reorg
            for (int i = 1; i < forkB.size(); i++) {
                chain.applyBlock(forkB.get(i));
            }
            
            assertThat(chain.getHeight()).isEqualTo(6);
            assertThat(chain.getLatestBlock().getHash()).isEqualTo(forkB.get(5).getHash());
        } finally {
            org.apache.commons.io.FileUtils.deleteDirectory(tempDir.toFile());
        }
    }
}
