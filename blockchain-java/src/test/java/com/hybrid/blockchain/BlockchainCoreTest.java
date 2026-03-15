package com.hybrid.blockchain;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.math.BigInteger;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@Tag("unit")
class BlockchainCoreTest extends TestHarness {

    private List<Validator> validators;
    private PoAConsensus poa;
    private Validator leader;
    private BigInteger leaderPriv;

    @BeforeEach
    void setUp() throws Exception {
        System.setProperty("DEBUG", "false");
        validators = defaultValidators();
        poa = new PoAConsensus(validators);
        tempDir = java.nio.file.Files.createTempDirectory("bc-core-");
        storage = new Storage(tempDir.toString(), TEST_AES_KEY);
        blockchain = new Blockchain(storage, new Mempool(10_000), poa);
        blockchain.init();

        leader = validators.get(0);
        leaderPriv = privateKey(101);
    }

    private Block signedBlockFromCreate(String minerAddress) throws Exception {
        Block block = blockchain.createBlock(minerAddress, 100);
        poa.signBlock(block, leader, leaderPriv);
        return block;
    }

    @Test
    @DisplayName("Genesis block invariant: index 0, zero prevHash, and hash consistency")
    void genesisBlockInvariant() {
        Block genesis = blockchain.getLatestBlock();
        assertEquals(0, genesis.getIndex(), "Genesis block index must be exactly 0");
        assertEquals("0000000000000000000000000000000000000000000000000000000000000000", genesis.getPrevHash(), "Genesis prevHash must be 64 hex zeros");
        assertEquals(genesis.calculateHash(), genesis.getHash(), "Genesis hash must match canonical hash calculation");
    }

    @Test
    @DisplayName("Freshly initialized chain is valid")
    void chainValidAfterInit() {
        assertTrue(blockchain.isChainValid(), "A newly initialized chain must pass structural and hash validation");
    }

    @Test
    @DisplayName("Corrupting block hash breaks chain validity")
    void chainInvalidAfterHashCorruption() throws Exception {
        Block block = signedBlockFromCreate(leader.getId());
        blockchain.applyBlock(block);

        Field hashField = Block.class.getDeclaredField("hash");
        hashField.setAccessible(true);
        hashField.set(block, "abcd");

        assertFalse(blockchain.isChainValid(), "Chain validity must fail when stored block hash is corrupted");
    }

    @Test
    @DisplayName("Corrupting block prevHash breaks chain validity")
    void chainInvalidAfterPrevHashCorruption() throws Exception {
        Block block = signedBlockFromCreate(leader.getId());
        blockchain.applyBlock(block);

        Field prevHash = Block.class.getDeclaredField("prevHash");
        prevHash.setAccessible(true);
        prevHash.set(block, "deadbeef");

        assertFalse(blockchain.isChainValid(), "Chain validity must fail when block prevHash linkage is corrupted");
    }

    @Test
    @DisplayName("Unknown address balance returns zero")
    void unknownAddressBalanceZero() {
        assertEquals(0, blockchain.getBalance("hb-unknown"), "Unknown addresses must have zero combined account and UTXO balance");
    }

    @Test
    @DisplayName("MINT transaction increases receiver balance exactly by minted amount")
    void mintIncreasesBalanceExactly() throws Exception {
        String receiver = "hb-mint-target";
        Transaction mint = new Transaction.Builder().type(Transaction.Type.MINT).to(receiver).amount(777).fee(0).build();
        blockchain.addTransaction(mint);

        Block valid = signedBlockFromCreate(leader.getId());
        long before = blockchain.getState().getBalance(receiver);
        blockchain.applyBlock(valid);

        assertEquals(before + 777, blockchain.getState().getBalance(receiver), "Minted amount must be reflected exactly in receiver balance");
    }

    @Test
    @DisplayName("ACCOUNT transfer debits sender amount+fee and credits receiver amount")
    void accountTransferAccountingInvariant() throws Exception {
        BigInteger senderPriv = privateKey(9001);
        String sender = Crypto.deriveAddress(Crypto.derivePublicKey(senderPriv));
        String receiver = "hb-receiver-1";
        blockchain.getState().credit(sender, 1000);

        Transaction tx = new Transaction.Builder().type(Transaction.Type.ACCOUNT).to(receiver).amount(100).fee(7).nonce(1).sign(senderPriv, Crypto.derivePublicKey(senderPriv));
        blockchain.addTransaction(tx);

        long senderBefore = blockchain.getState().getBalance(sender);
        long receiverBefore = blockchain.getState().getBalance(receiver);

        Block block = signedBlockFromCreate(leader.getId());
        blockchain.applyBlock(block);

        assertEquals(senderBefore - 107, blockchain.getState().getBalance(sender), "Sender balance must decrease by amount + fee after ACCOUNT transfer");
        assertEquals(receiverBefore + 100, blockchain.getState().getBalance(receiver), "Receiver balance must increase exactly by transfer amount");
    }

    @Test
    @DisplayName("Applying same block twice is rejected")
    void duplicateBlockApplicationRejected() throws Exception {
        Block block = signedBlockFromCreate(leader.getId());
        blockchain.applyBlock(block);

        Exception ex = assertThrows(Exception.class, () -> blockchain.applyBlock(block), "Re-applying a previously applied block must be rejected");
        assertTrue(
            ex.getMessage().toLowerCase().contains("tip")
                || ex.getMessage().toLowerCase().contains("chain")
                || ex.getMessage().toLowerCase().contains("height"),
            "Duplicate block rejection must indicate chain linkage/height failure"
        );
    }

    @Test
    @DisplayName("Applying a block with unknown validator is rejected")
    void unknownValidatorRejected() throws Exception {
        Block block = blockchain.createBlock(leader.getId(), 100);
        block.setValidatorId("hb-not-validator");
        block.setSignature(new byte[64]);

        Exception ex = assertThrows(Exception.class, () -> blockchain.applyBlock(block), "Blocks from unknown validators must be rejected");
        assertTrue(ex.getMessage().toLowerCase().contains("unknown validator"), "Unknown validator error must be explicit");
    }

    @Test
    @DisplayName("Applying block with corrupted validator signature is rejected")
    void corruptedValidatorSignatureRejected() throws Exception {
        Block block = signedBlockFromCreate(leader.getId());
        byte[] sig = block.getSignature();
        sig[0] ^= 0x01;
        block.setSignature(sig);

        Exception ex = assertThrows(Exception.class, () -> blockchain.applyBlock(block), "Corrupted validator signature must fail block validation");
        assertTrue(ex.getMessage().toLowerCase().contains("signature"), "Signature failure must be reported clearly");
    }

    @Test
    @DisplayName("Applying block with invalid stateRoot is rejected")
    void invalidStateRootRejected() throws Exception {
        Block block = signedBlockFromCreate(leader.getId());
        block.setStateRoot("00");
        block.setHash(block.calculateHash());
        poa.signBlock(block, leader, leaderPriv);

        Exception ex = assertThrows(Exception.class, () -> blockchain.applyBlock(block), "State-root mismatch must reject block application");
        assertTrue(ex.getMessage().toLowerCase().contains("state root"), "State root mismatch message must be explicit");
    }

    @Test
    @DisplayName("Applying block with invalid txRoot is rejected")
    void invalidTxRootRejected() throws Exception {
        Block block = signedBlockFromCreate(leader.getId());
        block.setTxRoot("00");
        block.setHash(block.calculateHash());
        poa.signBlock(block, leader, leaderPriv);

        Exception ex = assertThrows(Exception.class, () -> blockchain.applyBlock(block), "Invalid txRoot must invalidate block signature/hash consistency");
        assertTrue(ex.getMessage().length() > 0, "Block application error must provide diagnostic message");
    }

    @Test
    @DisplayName("Height equals chain.size()-1 across 50 consecutive applied blocks")
    void heightMatchesChainSizeForFiftyBlocks() throws Exception {
        for (int i = 0; i < 50; i++) {
            Block block = signedBlockFromCreate(leader.getId());
            blockchain.applyBlock(block);
            assertEquals(blockchain.getChain().size() - 1, blockchain.getHeight(), "Blockchain height must always equal chain size minus one");
        }
    }

    @Test
    @DisplayName("createBlock excludes transactions that fail validation")
    void createBlockExcludesInvalidTransactions() throws Exception {
        BigInteger badPriv = privateKey(9123);
        byte[] badPub = Crypto.derivePublicKey(badPriv);
        String badSender = Crypto.deriveAddress(badPub);
        blockchain.getState().credit(badSender, 5_000);

        Transaction invalid = new Transaction.Builder().type(Transaction.Type.ACCOUNT).to("hb-r").amount(100).fee(1).nonce(2).sign(badPriv, badPub);
        blockchain.addTransaction(invalid);

        Block block = blockchain.createBlock(leader.getId(), 100);
        boolean present = block.getTransactions().stream().anyMatch(t -> t.getId().equals(invalid.getId()));

        assertFalse(present, "createBlock must exclude transactions that fail validateTransaction during inclusion simulation");
    }

    @Test
    @DisplayName("createBlock stateRoot matches computed post-application state")
    void createBlockStateRootMatchesPostApply() throws Exception {
        Block block = signedBlockFromCreate(leader.getId());
        String advertised = block.getStateRoot();
        blockchain.applyBlock(block);

        assertEquals(advertised, blockchain.getState().calculateStateRoot(), "Block stateRoot must match actual post-application state root");
    }

    @Test
    @DisplayName("Miner receives Config.MINER_REWARD after block application")
    void minerReceivesReward() throws Exception {
        String miner = leader.getId();
        long before = blockchain.getState().getBalance(miner);

        Block block = signedBlockFromCreate(miner);
        blockchain.applyBlock(block);

        assertTrue(blockchain.getState().getBalance(miner) >= before + Config.MINER_REWARD, "Miner balance must increase by at least MINER_REWARD after block apply");
    }

    @Test
    @DisplayName("Validator receives total transaction fees in applied block")
    void validatorReceivesTotalFees() throws Exception {
        BigInteger senderPriv = privateKey(7777);
        byte[] senderPub = Crypto.derivePublicKey(senderPriv);
        String sender = Crypto.deriveAddress(senderPub);
        blockchain.getState().credit(sender, 10_000);

        Transaction tx1 = new Transaction.Builder().type(Transaction.Type.ACCOUNT).to("hb-a").amount(5).fee(3).nonce(1).sign(senderPriv, senderPub);
        Transaction tx2 = new Transaction.Builder().type(Transaction.Type.ACCOUNT).to("hb-b").amount(7).fee(4).nonce(2).sign(senderPriv, senderPub);
        blockchain.addTransaction(tx1);
        blockchain.addTransaction(tx2);

        long before = blockchain.getState().getBalance(leader.getId());
        Block block = signedBlockFromCreate(leader.getId());
        blockchain.applyBlock(block);

        long after = blockchain.getState().getBalance(leader.getId());
        assertTrue(after >= before + tx1.getFee() + tx2.getFee(), "Validator balance must include the sum of all included transaction fees");
    }
}
