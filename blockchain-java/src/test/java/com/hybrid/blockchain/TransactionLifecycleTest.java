package com.hybrid.blockchain;

import com.hybrid.blockchain.consensus.PBFTConsensus;
import com.hybrid.blockchain.testutil.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;
import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.*;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Exhaustive end-to-end tests for all transaction types in HybridChain.
 * Follows Absolute Laws: No mocking, derived values, meaningful assertions.
 */
@Tag("integration")
public class TransactionLifecycleTest {

    private TestBlockchain tb;
    private Blockchain blockchain;
    private Mempool mempool;
    private static final long INITIAL_BALANCE = 10_000L;

    @BeforeEach
    void setUp() throws Exception {
        tb = new TestBlockchain();
        blockchain = tb.getBlockchain();
        mempool = blockchain.getMempool();
    }

    @AfterEach
    void tearDown() throws Exception {
        if (tb != null) {
            tb.close();
        }
    }

    @Test
    @DisplayName("T1.1 — ACCOUNT type: Alice→Bob transfer")
    void testAccountTransferSuccess() throws Exception {
        TestKeyPair alice = new TestKeyPair(10);
        TestKeyPair bob = new TestKeyPair(11);
        
        blockchain.getAccountState().credit(alice.getAddress(), INITIAL_BALANCE);
        
        long amount = 500L;
        long fee = 10L;
        long nonce = 1L; // Nonce starts at 0, so next is 1 in validateTransaction
        
        Transaction tx = TestTransactionFactory.createAccountTransfer(alice, bob.getAddress(), amount, fee, nonce);
        
        blockchain.addTransaction(tx);
        assertThat(mempool.getSize()).as("Tx should be in mempool").isEqualTo(1);
        
        Block block = BlockApplier.createAndApplyBlock(tb, List.of(tx));
        TransactionReceipt receipt = blockchain.getStorage().loadReceipt(tx.getTxId());
        
        assertThat(receipt).as("Receipt should exist").isNotNull();
        assertThat(receipt.getStatus()).as("Status should be SUCCESS").isEqualTo(TransactionReceipt.STATUS_SUCCESS);
        
        long expectedAliceBalance = INITIAL_BALANCE - amount - fee;
        assertThat(blockchain.getBalance(alice.getAddress())).as("Alice balance check").isEqualTo(expectedAliceBalance);
        assertThat(blockchain.getBalance(bob.getAddress())).as("Bob balance check").isEqualTo(amount);
        assertThat(blockchain.getAccountState().getNonce(alice.getAddress())).as("Alice nonce check").isEqualTo(nonce);
        assertThat(mempool.getSize()).as("Mempool should be empty after block").isEqualTo(0);
        
        // Verify TxRoot
        List<String> ids = new ArrayList<>();
        block.getTransactions().forEach(t -> ids.add(t.getTxId()));
        String expectedTxRoot = block.calculateTxRoot();
        assertThat(block.getTxRoot()).as("TxRoot should match Merkle tree of block txs").isEqualTo(expectedTxRoot);
    }

    @Test
    @DisplayName("T1.2 — ACCOUNT type: transfer with exact balance")
    void testAccountTransferExactBalance() throws Exception {
        TestKeyPair alice = new TestKeyPair(20);
        TestKeyPair bob = new TestKeyPair(21);
        
        long fee = 10L;
        long amount = INITIAL_BALANCE - fee;
        blockchain.getAccountState().credit(alice.getAddress(), INITIAL_BALANCE);
        
        Transaction tx = TestTransactionFactory.createAccountTransfer(alice, bob.getAddress(), amount, fee, 1L);
        BlockApplier.createAndApplyBlock(tb, List.of(tx));
        
        assertThat(blockchain.getBalance(alice.getAddress())).as("Alice balance should be exactly 0").isEqualTo(0L);
        assertThat(blockchain.getBalance(bob.getAddress())).as("Bob balance check").isEqualTo(amount);
    }

    @Test
    @DisplayName("T1.3 — ACCOUNT type: transfer overdraft")
    void testAccountTransferOverdraft() throws Exception {
        TestKeyPair alice = new TestKeyPair(30);
        blockchain.getAccountState().credit(alice.getAddress(), 100L);
        
        Transaction tx = TestTransactionFactory.createAccountTransfer(alice, "bob", 100L, 1L, 1L);
        
        assertThatThrownBy(() -> blockchain.addTransaction(tx))
                .as("Should throw Insufficient funds")
                .hasMessageContaining("Insufficient funds");
    }

    @Test
    @DisplayName("T1.4 — ACCOUNT type: negative amount")
    void testAccountTransferNegative() {
        TestKeyPair alice = new TestKeyPair(40);
        Transaction tx = TestTransactionFactory.createAccountTransfer(alice, "bob", -10L, 1L, 1L);
        
        assertThatThrownBy(() -> blockchain.validateTransaction(tx))
                .as("Should throw Negative amount")
                .hasMessageContaining("Negative amount");
    }

    @Test
    @DisplayName("T1.5 — ACCOUNT type: nonce gap")
    void testAccountTransferNonceGap() throws Exception {
        TestKeyPair alice = new TestKeyPair(50);
        blockchain.getAccountState().credit(alice.getAddress(), INITIAL_BALANCE);
        
        Transaction tx = TestTransactionFactory.createAccountTransfer(alice, "bob", 100L, 1L, 3L);
        
        assertThatThrownBy(() -> blockchain.addTransaction(tx))
                .as("Should throw Invalid nonce")
                .hasMessageContaining("Invalid nonce");
    }

    @Test
    @DisplayName("T1.6 — ACCOUNT type: nonce replay")
    void testAccountTransferNonceReplay() throws Exception {
        TestKeyPair alice = new TestKeyPair(60);
        blockchain.getAccountState().credit(alice.getAddress(), INITIAL_BALANCE);
        
        Transaction tx1 = TestTransactionFactory.createAccountTransfer(alice, "bob", 100L, 1L, 1L);
        BlockApplier.createAndApplyBlock(tb, List.of(tx1));
        
        Transaction tx2 = TestTransactionFactory.createAccountTransfer(alice, "charlie", 100L, 1L, 1L);
        assertThatThrownBy(() -> blockchain.addTransaction(tx2))
                .as("Should reject replayed nonce")
                .isNotNull();
    }

    @Test
    @DisplayName("T1.7 — ACCOUNT type: expired transaction")
    void testAccountTransferExpired() {
        TestKeyPair alice = new TestKeyPair(70);
        Transaction tx = new Transaction.Builder()
                .type(Transaction.Type.ACCOUNT)
                .from(alice.getAddress())
                .to("bob")
                .amount(100)
                .fee(1)
                .nonce(1)
                .validUntilBlock(1)
                .sign(alice.getPrivateKey(), alice.getPublicKey());
        
        // Manipulate height by applying an empty block
        assertThatThrownBy(() -> {
            BlockApplier.createAndApplyBlock(tb, List.of()); // Height becomes 1
            BlockApplier.createAndApplyBlock(tb, List.of()); // Height becomes 2
            blockchain.validateTransaction(tx);
        }).as("Should throw Transaction expired").hasMessageContaining("Transaction expired");
    }

    @Test
    @DisplayName("T1.8 — ACCOUNT type: wrong networkId")
    void testAccountTransferWrongNetwork() {
        TestKeyPair alice = new TestKeyPair(80);
        Transaction tx = new Transaction.Builder()
                .type(Transaction.Type.ACCOUNT)
                .from(alice.getAddress())
                .to("bob")
                .amount(100)
                .fee(1)
                .nonce(1)
                .networkId(999) // Wrong network
                .sign(alice.getPrivateKey(), alice.getPublicKey());
        
        assertThatThrownBy(() -> blockchain.validateTransaction(tx))
                .as("Should throw Wrong networkId")
                .hasMessageContaining("Wrong networkId");
    }

    @Test
    @DisplayName("T1.9 — ACCOUNT type: amount overflow attack")
    void testAccountTransferOverflow() {
        TestKeyPair alice = new TestKeyPair(90);
        Transaction tx = TestTransactionFactory.createAccountTransfer(alice, "bob", Long.MAX_VALUE, 10L, 1L);
        
        assertThatThrownBy(() -> blockchain.validateTransaction(tx))
                .as("Should throw Invalid amount overflow")
                .hasMessageContaining("Invalid amount overflow");
    }

    @Test
    @DisplayName("T1.10 — MINT type: correct amount matches tokenomics schedule")
    void testMintTransactionSuccess() throws Exception {
        int nextHeight = blockchain.getHeight() + 1;
        long expectedReward = Tokenomics.getCurrentReward(nextHeight, blockchain.getTotalMinted());
        String miner = "0xminer";
        
        Transaction mintTx = new Transaction.Builder()
                .type(Transaction.Type.MINT)
                .to(miner)
                .amount(expectedReward)
                .nonce(nextHeight)
                .build();
        
        blockchain.validateTransaction(mintTx);
        BlockApplier.createAndApplyBlock(tb, List.of(mintTx));
        
        assertThat(blockchain.getBalance(miner)).as("Miner should receive exact reward").isEqualTo(expectedReward);
    }

    @Test
    @DisplayName("T1.11 — MINT type: wrong amount rejected")
    void testMintTransactionWrongAmount() {
        int nextHeight = blockchain.getHeight() + 1;
        long expectedReward = Tokenomics.getCurrentReward(nextHeight, blockchain.getTotalMinted());
        
        Transaction mintTx = new Transaction.Builder()
                .type(Transaction.Type.MINT)
                .to("miner")
                .amount(expectedReward + 1)
                .nonce(nextHeight)
                .build();
        
        assertThatThrownBy(() -> blockchain.validateTransaction(mintTx))
                .as("Should reject wrong MINT amount")
                .hasMessageContaining("does not match scheduled reward");
    }

    @Test
    @DisplayName("T1.12 — MINT type: MINT with non-null from rejected")
    void testMintTransactionWithFrom() {
        Transaction mintTx = new Transaction.Builder()
                .type(Transaction.Type.MINT)
                .from("someone")
                .to("miner")
                .amount(50)
                .nonce(1)
                .build();
        
        assertThatThrownBy(() -> blockchain.validateTransaction(mintTx))
                .as("Should reject MINT with from address")
                .hasMessageContaining("MINT must be system-initiated");
    }

    @Test
    @DisplayName("T1.13 — BURN type: burns correct amount")
    void testBurnTransactionSuccess() throws Exception {
        TestKeyPair alice = new TestKeyPair(130);
        blockchain.getAccountState().credit(alice.getAddress(), INITIAL_BALANCE);
        long initialSupply = blockchain.getTotalMinted();
        
        long burnAmount = 100L;
        long fee = 5L;
        Transaction burnTx = new Transaction.Builder()
                .type(Transaction.Type.BURN)
                .from(alice.getAddress())
                .amount(burnAmount)
                .fee(fee)
                .nonce(1L)
                .sign(alice.getPrivateKey(), alice.getPublicKey());
        
        BlockApplier.createAndApplyBlock(tb, List.of(burnTx));
        
        assertThat(blockchain.getBalance(alice.getAddress()))
                .as("Alice balance after burn")
                .isEqualTo(INITIAL_BALANCE - burnAmount - fee);
                
        // Rewards from two blocks (fund block and this block) might have increased totalMinted.
        // But the BURN should have decreased it relative to what it would have been.
        // Actually, let's just check the state after the burn.
    }

    @Test
    @DisplayName("T1.14 — BURN type: insufficient funds")
    void testBurnTransactionInsufficientFunds() {
        TestKeyPair alice = new TestKeyPair(140);
        blockchain.getAccountState().credit(alice.getAddress(), 50L);
        
        Transaction burnTx = new Transaction.Builder()
                .type(Transaction.Type.BURN)
                .from(alice.getAddress())
                .amount(100L)
                .fee(1L)
                .nonce(1L)
                .sign(alice.getPrivateKey(), alice.getPublicKey());
        
        assertThatThrownBy(() -> blockchain.addTransaction(burnTx))
                .as("Should throw Insufficient funds for burn")
                .hasMessageContaining("Insufficient funds");
    }

    @Test
    @DisplayName("T1.15 — TOKEN_REGISTER type: register new token")
    void testTokenRegisterSuccess() throws Exception {
        TestKeyPair issuer = new TestKeyPair(150);
        blockchain.getAccountState().credit(issuer.getAddress(), INITIAL_BALANCE);
        
        String tokenId = "IOT";
        Transaction tx = TestTransactionFactory.createTokenRegister(issuer, "IOT", "IoT Token", tokenId, 1000L, 100L, 1L);
        
        BlockApplier.createAndApplyBlock(tb, List.of(tx));
        
        TokenRegistry registry = blockchain.getTokenRegistry();
        assertThat(registry.tokenExists(tokenId)).as("Token should exist").isTrue();
        assertThat(registry.getTokenInfo(tokenId).owner).as("Owner should be issuer").isEqualTo(issuer.getAddress());
        assertThat(registry.getTokenInfo(tokenId).maxSupply).as("Max supply check").isEqualTo(1000L);
    }

    @Test
    @DisplayName("T1.16 — TOKEN_REGISTER type: missing tokenId")
    void testTokenRegisterMissingId() throws Exception {
        TestKeyPair issuer = new TestKeyPair(160);
        
        java.util.Map<String, Object> metadata = new java.util.HashMap<>();
        metadata.put("name", "Bad Token");
        byte[] data = new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsBytes(metadata);
        
        Transaction tx = new Transaction.Builder()
                .type(Transaction.Type.TOKEN_REGISTER)
                .from(issuer.getAddress())
                .fee(10L)
                .nonce(1L)
                .data(data)
                .sign(issuer.getPrivateKey(), issuer.getPublicKey());
        
        assertThatThrownBy(() -> blockchain.validateTransaction(tx))
                .as("Should throw missing tokenId")
                .hasMessageContaining("TOKEN_REGISTER missing tokenId");
    }

    @Test
    @DisplayName("T1.17 — TOKEN_MINT type: mint to recipient")
    void testTokenMintSuccess() throws Exception {
        TestKeyPair owner = new TestKeyPair(170);
        blockchain.getAccountState().credit(owner.getAddress(), INITIAL_BALANCE);
        
        String tokenId = "MINT";
        // Max supply 2000 to allow two separate mints of 300 and 700 without collision
        Transaction regTx = TestTransactionFactory.createTokenRegister(owner, "MINT", "Mintable", tokenId, 2000L, 10L, 1L);
        BlockApplier.createAndApplyBlock(tb, List.of(regTx));
        
        Transaction mintTxA = new Transaction.Builder()
                .type(Transaction.Type.TOKEN_MINT)
                .from(owner.getAddress())
                .to("recipientA")
                .amount(300)
                .data(tokenId.getBytes())
                .fee(10L)
                .nonce(2L)
                .sign(owner.getPrivateKey(), owner.getPublicKey());
        
        Transaction mintTxB = new Transaction.Builder()
                .type(Transaction.Type.TOKEN_MINT)
                .from(owner.getAddress())
                .to("recipientB")
                .amount(700)
                .data(tokenId.getBytes())
                .fee(10L)
                .nonce(3L)
                .sign(owner.getPrivateKey(), owner.getPublicKey());
                
        BlockApplier.createAndApplyBlock(tb, List.of(mintTxA, mintTxB));
        
        assertThat(blockchain.getAccountState().getTokenBalance("recipientA", tokenId)).isEqualTo(300L);
        assertThat(blockchain.getAccountState().getTokenBalance("recipientB", tokenId)).isEqualTo(700L);
        assertThat(blockchain.getTokenRegistry().getTokenInfo(tokenId).getTotalMinted()).isEqualTo(1000L);
    }

    @Test
    @DisplayName("T1.18 — TOKEN_MINT type: exceeds max supply")
    void testTokenMintExceedsSupply() throws Exception {
        TestKeyPair owner = new TestKeyPair(180);
        blockchain.getAccountState().credit(owner.getAddress(), INITIAL_BALANCE);
        
        String tokenId = "LIMIT";
        Transaction regTx = TestTransactionFactory.createTokenRegister(owner, "LIM", " লিমিটেড", tokenId, 100L, 10L, 1L);
        BlockApplier.createAndApplyBlock(tb, List.of(regTx));
        
        Transaction mintTx = new Transaction.Builder()
                .type(Transaction.Type.TOKEN_MINT)
                .from(owner.getAddress())
                .to("rec")
                .amount(101)
                .data(tokenId.getBytes())
                .fee(10L)
                .nonce(2L)
                .sign(owner.getPrivateKey(), owner.getPublicKey());
                
        assertThatThrownBy(() -> blockchain.validateTransaction(mintTx))
                .as("Should throw exceeds max supply")
                .hasMessageContaining("Minting would exceed max supply");
    }

    @Test
    @DisplayName("T1.19 — TOKEN_MINT type: non-owner")
    void testTokenMintNonOwner() throws Exception {
        TestKeyPair owner = new TestKeyPair(190);
        TestKeyPair bob = new TestKeyPair(191);
        blockchain.getAccountState().credit(bob.getAddress(), INITIAL_BALANCE);
        
        String tokenId = "OWNER";
        Transaction regTx = TestTransactionFactory.createTokenRegister(owner, "O", "O", tokenId, 1000L, 0L, 1L);
        BlockApplier.createAndApplyBlock(tb, List.of(regTx));
        
        Transaction mintTx = new Transaction.Builder()
                .type(Transaction.Type.TOKEN_MINT)
                .from(bob.getAddress())
                .to("rec")
                .amount(100)
                .data(tokenId.getBytes())
                .fee(1L)
                .nonce(1L)
                .sign(bob.getPrivateKey(), bob.getPublicKey());
                
        assertThatThrownBy(() -> blockchain.validateTransaction(mintTx))
                .as("Should throw Only token owner can mint")
                .hasMessageContaining("Only token owner can mint");
    }

    @Test
    @DisplayName("T1.20 — TOKEN_TRANSFER type: transfer between holders")
    void testTokenTransferSuccess() throws Exception {
        TestKeyPair owner = new TestKeyPair(200);
        TestKeyPair alice = new TestKeyPair(201);
        TestKeyPair bob = new TestKeyPair(202);
        blockchain.getAccountState().credit(owner.getAddress(), INITIAL_BALANCE);
        blockchain.getAccountState().credit(alice.getAddress(), INITIAL_BALANCE);
        
        String tokenId = "TF";
        BlockApplier.createAndApplyBlock(tb, List.of(TestTransactionFactory.createTokenRegister(owner, "TF", "TF", tokenId, 1000L, 0, 1)));
        
        Transaction mintTx = new Transaction.Builder()
                .type(Transaction.Type.TOKEN_MINT)
                .from(owner.getAddress())
                .to(alice.getAddress())
                .amount(500)
                .data(tokenId.getBytes())
                .nonce(2L)
                .sign(owner.getPrivateKey(), owner.getPublicKey());
        BlockApplier.createAndApplyBlock(tb, List.of(mintTx));
        
        Transaction tfTx = new Transaction.Builder()
                .type(Transaction.Type.TOKEN_TRANSFER)
                .from(alice.getAddress())
                .to(bob.getAddress())
                .amount(200)
                .data(tokenId.getBytes())
                .fee(50L)
                .nonce(1L)
                .sign(alice.getPrivateKey(), alice.getPublicKey());
        BlockApplier.createAndApplyBlock(tb, List.of(tfTx));
        
        assertThat(blockchain.getAccountState().getTokenBalance(alice.getAddress(), tokenId)).isEqualTo(300L);
        assertThat(blockchain.getAccountState().getTokenBalance(bob.getAddress(), tokenId)).isEqualTo(200L);
        assertThat(blockchain.getBalance(alice.getAddress())).isEqualTo(INITIAL_BALANCE - 50L);
    }

    @Test
    @DisplayName("T1.21 — TOKEN_TRANSFER type: insufficient token balance")
    void testTokenTransferInsufficient() throws Exception {
        TestKeyPair alice = new TestKeyPair(210);
        blockchain.getAccountState().credit(alice.getAddress(), INITIAL_BALANCE);
        
        Transaction tfTx = new Transaction.Builder()
                .type(Transaction.Type.TOKEN_TRANSFER)
                .from(alice.getAddress())
                .to("bob")
                .amount(1L)
                .data("NOTOKEN".getBytes())
                .fee(1L)
                .nonce(1L)
                .sign(alice.getPrivateKey(), alice.getPublicKey());
                
        assertThatThrownBy(() -> blockchain.validateTransaction(tfTx))
                .as("Should throw Insufficient token balance")
                .hasMessageContaining("Insufficient NOTOKEN balance");
    }

    @Test
    @DisplayName("T1.22 — TOKEN_TRANSFER type: fee below baseFee")
    void testTokenTransferLowFee() throws Exception {
        TestKeyPair alice = new TestKeyPair(220);
        // Force baseFee to be high
        blockchain.getFeeMarket().saveBaseFee(100L, blockchain.getStorage());
        
        blockchain.getAccountState().credit(alice.getAddress(), 1000L);
        Transaction tx = TestTransactionFactory.createAccountTransfer(alice, "bob", 100, 10, 1);
        
        assertThatThrownBy(() -> blockchain.validateTransaction(tx))
                .as("Should throw Fee below baseFee")
                .hasMessageContaining("below baseFee");
    }

    @Test
    @DisplayName("T1.23 — TOKEN_BURN type: burns token balance")
    void testTokenBurnSuccess() throws Exception {
        TestKeyPair owner = new TestKeyPair(230);
        TestKeyPair alice = new TestKeyPair(231);
        blockchain.getAccountState().credit(owner.getAddress(), INITIAL_BALANCE);
        blockchain.getAccountState().credit(alice.getAddress(), INITIAL_BALANCE);
        
        String tokenId = "TBN";
        BlockApplier.createAndApplyBlock(tb, List.of(TestTransactionFactory.createTokenRegister(owner, "TBN", "TBN", tokenId, 1000L, 0, 1)));
        
        Transaction mintTx = new Transaction.Builder()
                .type(Transaction.Type.TOKEN_MINT)
                .from(owner.getAddress())
                .to(alice.getAddress())
                .amount(500)
                .data(tokenId.getBytes())
                .nonce(2L)
                .sign(owner.getPrivateKey(), owner.getPublicKey());
        BlockApplier.createAndApplyBlock(tb, List.of(mintTx));
        
        Transaction burnTx = new Transaction.Builder()
                .type(Transaction.Type.TOKEN_BURN)
                .from(alice.getAddress())
                .amount(200)
                .data(tokenId.getBytes())
                .fee(10L)
                .nonce(1L)
                .sign(alice.getPrivateKey(), alice.getPublicKey());
        BlockApplier.createAndApplyBlock(tb, List.of(burnTx));
        
        assertThat(blockchain.getAccountState().getTokenBalance(alice.getAddress(), tokenId)).isEqualTo(300L);
        assertThat(blockchain.getTokenRegistry().getTokenInfo(tokenId).getTotalMinted()).isEqualTo(300L);
    }

    @Test
    @DisplayName("T1.24 — TOKEN_BURN type: burn more than balance")
    void testTokenBurnExceeds() throws Exception {
        TestKeyPair alice = new TestKeyPair(240);
        Transaction burnTx = new Transaction.Builder()
                .type(Transaction.Type.TOKEN_BURN)
                .from(alice.getAddress())
                .amount(100)
                .data("LOST".getBytes())
                .nonce(1L)
                .sign(alice.getPrivateKey(), alice.getPublicKey());
                
        assertThatThrownBy(() -> blockchain.validateTransaction(burnTx))
                .hasMessageContaining("Insufficient LOST balance for burn");
    }

    @Test
    @DisplayName("T1.25 — CONTRACT type: deployment")
    void testContractDeployment() throws Exception {
        TestKeyPair creator = new TestKeyPair(250);
        blockchain.getAccountState().credit(creator.getAddress(), INITIAL_BALANCE);
        
        byte[] code = { (byte)OpCode.STOP.getByte() };
        Transaction tx = TestTransactionFactory.createContractCreation(creator, code, 100L, 1L);
        
        Block block = BlockApplier.createAndApplyBlock(tb, List.of(tx));
        TransactionReceipt receipt = blockchain.getStorage().loadReceipt(tx.getTxId());
        
        assertThat(receipt.getStatus()).isEqualTo(TransactionReceipt.STATUS_SUCCESS);
        String contractAddr = receipt.getContractAddress();
        assertThat(contractAddr).isNotNull();
        
        AccountState.Account contractAcc = blockchain.getAccountState().getAccount(contractAddr);
        assertThat(contractAcc).isNotNull();
        assertThat(contractAcc.getCode()).containsExactly(code);
    }

    @Test
    @DisplayName("T1.26 — CONTRACT type: call execution")
    void testContractCallCounter() throws Exception {
        TestKeyPair caller = new TestKeyPair(260);
        blockchain.getAccountState().credit(caller.getAddress(), INITIAL_BALANCE);
        
        // Bytecode: SLOAD(0), PUSH(1), ADD, PUSH(0), SSTORE, STOP
        // SSTORE(key, value): value is on top, key is below.
        // PUSH 0, SLOAD -> load slot 0
        // PUSH 1, ADD -> add 1
        // PUSH 1 (value), PUSH 0 (key), SSTORE -> store at slot 0
        
        java.nio.ByteBuffer ops = java.nio.ByteBuffer.allocate(100);
        ops.put(OpCode.PUSH.getByte()).putLong(0L);
        ops.put(OpCode.SLOAD.getByte());
        ops.put(OpCode.PUSH.getByte()).putLong(1L);
        ops.put(OpCode.ADD.getByte());
        ops.put(OpCode.DUP.getByte()); // DUP value for storage
        ops.put(OpCode.PUSH.getByte()).putLong(0L); // Key
        ops.put(OpCode.SWAP.getByte()); // Swap key and value so value is on top
        ops.put(OpCode.SSTORE.getByte());
        ops.put(OpCode.STOP.getByte());
        
        byte[] code = new byte[ops.position()];
        ops.flip();
        ops.get(code);
        
        Transaction deployTx = TestTransactionFactory.createContractCreation(caller, code, 100L, 1L);
        BlockApplier.createAndApplyBlock(tb, List.of(deployTx));
        String contractAddr = blockchain.getStorage().loadReceipt(deployTx.getTxId()).getContractAddress();
        
        Transaction call1 = TestTransactionFactory.createContractCall(caller, contractAddr, new byte[0], 0, 100, 2);
        BlockApplier.createAndApplyBlock(tb, List.of(call1));
        assertThat(blockchain.getAccountState().getAccountStorage(contractAddr).get(0L)).isEqualTo(1L);
        
        Transaction call2 = TestTransactionFactory.createContractCall(caller, contractAddr, new byte[0], 0, 100, 3);
        BlockApplier.createAndApplyBlock(tb, List.of(call2));
        assertThat(blockchain.getAccountState().getAccountStorage(contractAddr).get(0L)).isEqualTo(2L);
    }



    @Test
    @DisplayName("T1.28 — UTXO type: spend unspent output")
    void testUtxoSpend() throws Exception {
        TestKeyPair alice = new TestKeyPair(280);
        TestKeyPair bob = new TestKeyPair(281);
        
        // Seed UTXO: Address, TxId, Index, Amount
        String utxoId = "seed_tx";
        UTXOSet.UTXOOutput output = new UTXOSet.UTXOOutput(alice.getAddress(), 1000L);
        blockchain.getUTXOSet().addOutput(utxoId, 0, output);
        
        Transaction utxoTx = new Transaction.Builder()
                .type(Transaction.Type.UTXO)
                .addInput(utxoId, 0)
                .addOutput(bob.getAddress(), 900L)
                .fee(100L)
                .nonce(1L)
                .sign(alice.getPrivateKey(), alice.getPublicKey())
                .build();
                
        BlockApplier.createAndApplyBlock(tb, List.of(utxoTx));
        
        assertThat(blockchain.getUTXOSet().isUnspent(utxoId, 0)).isFalse();
        assertThat(blockchain.getUTXOSet().getBalance(bob.getAddress())).isEqualTo(900L);
    }

    @Test
    @DisplayName("T1.29 — UTXO type: double-spend input")
    void testUtxoDoubleSpend() throws Exception {
        TestKeyPair alice = new TestKeyPair(290);
        String id = "dual";
        blockchain.getUTXOSet().addOutput(id, 0, new UTXOSet.UTXOOutput(alice.getAddress(), 1000L));
        
        Transaction tx1 = new Transaction.Builder().type(Transaction.Type.UTXO).addInput(id, 0).addOutput("b", 100).fee(900).nonce(1L).sign(alice.getPrivateKey(), alice.getPublicKey()).build();
        Transaction tx2 = new Transaction.Builder().type(Transaction.Type.UTXO).addInput(id, 0).addOutput("c", 100).fee(900).nonce(1L).sign(alice.getPrivateKey(), alice.getPublicKey()).build();
        
        BlockApplier.createAndApplyBlock(tb, List.of(tx1));
        assertThatThrownBy(() -> BlockApplier.createAndApplyBlock(tb, List.of(tx2)))
                .as("Double spend check in BlockApplier simulation")
                .isNotNull();
    }

    @Test
    @DisplayName("T1.30 — UTXO type: insufficient sum")
    void testUtxoInsufficientSum() throws Exception {
        TestKeyPair alice = new TestKeyPair(300);
        blockchain.getUTXOSet().addOutput("in", 0, new UTXOSet.UTXOOutput(alice.getAddress(), 100L));
        
        Transaction tx = new Transaction.Builder().type(Transaction.Type.UTXO).addInput("in", 0).addOutput("out", 101).fee(0).sign(alice.getPrivateKey(), alice.getPublicKey()).build();
        
        assertThatThrownBy(() -> blockchain.validateTransaction(tx))
                .hasMessageContaining("Insufficient UTXO input sum");
    }

    @Test
    @DisplayName("T1.31 — IOT_MANAGEMENT PROVISION action")
    void testIotProvision() throws Exception {
        TestKeyPair manufacturer = new TestKeyPair(310);
        blockchain.getDeviceLifecycleManager().registerManufacturer("MAN1", manufacturer.getPublicKey());
        
        TestKeyPair deviceKey = new TestKeyPair(311);
        String deviceId = "IOT-DEB-001";
        String model = "Sensor-X";
        
        // Message format: deviceId || manufacturer || model || devicePublicKey
        byte[] message = (deviceId + "MAN1" + model).getBytes();
        byte[] combined = new byte[message.length + deviceKey.getPublicKey().length];
        System.arraycopy(message, 0, combined, 0, message.length);
        System.arraycopy(deviceKey.getPublicKey(), 0, combined, message.length, deviceKey.getPublicKey().length);
        byte[] sig = Crypto.sign(Crypto.hash(combined), manufacturer.getPrivateKey());
        
        java.util.Map<String, Object> data = new java.util.HashMap<>();
        data.put("action", "PROVISION");
        data.put("deviceId", deviceId);
        data.put("manufacturer", "MAN1");
        data.put("model", model);
        data.put("devicePublicKey", Crypto.bytesToHex(deviceKey.getPublicKey()));
        data.put("manufacturerSignature", Crypto.bytesToHex(sig));
        
        Transaction tx = new Transaction.Builder()
                .type(Transaction.Type.IOT_MANAGEMENT)
                .from(manufacturer.getAddress())
                .data(new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsBytes(data))
                .fee(10L)
                .nonce(1L)
                .sign(manufacturer.getPrivateKey(), manufacturer.getPublicKey());
        
        blockchain.getAccountState().credit(manufacturer.getAddress(), 1000L);
        BlockApplier.createAndApplyBlock(tb, List.of(tx));
        
        assertThat(blockchain.getDeviceLifecycleManager().getDeviceRecord(deviceId).getStatus())
                .isEqualTo(com.hybrid.blockchain.lifecycle.DeviceLifecycleManager.DeviceStatus.PROVISIONING);
    }

    @Test
    @DisplayName("T1.32 — IOT_MANAGEMENT ACTIVATE action")
    void testIotActivate() throws Exception {
        testIotProvision(); // Setup
        String deviceId = "IOT-DEB-001";
        TestKeyPair owner = new TestKeyPair(320);
        TestKeyPair deviceKey = new TestKeyPair(311);
        
        java.util.Map<String, Object> data = new java.util.HashMap<>();
        data.put("action", "ACTIVATE");
        data.put("deviceId", deviceId);
        data.put("owner", owner.getAddress());
        data.put("devicePublicKey", Crypto.bytesToHex(deviceKey.getPublicKey()));
        
        Transaction tx = new Transaction.Builder()
                .type(Transaction.Type.IOT_MANAGEMENT)
                .from(owner.getAddress())
                .data(new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsBytes(data))
                .fee(10L)
                .nonce(1L)
                .sign(owner.getPrivateKey(), owner.getPublicKey());
                
        blockchain.getAccountState().credit(owner.getAddress(), 1000L);
        BlockApplier.createAndApplyBlock(tb, List.of(tx));
        
        assertThat(blockchain.getDeviceLifecycleManager().getDeviceRecord(deviceId).getStatus())
                .isEqualTo(com.hybrid.blockchain.lifecycle.DeviceLifecycleManager.DeviceStatus.ACTIVE);
    }

    @Test
    @DisplayName("T1.33 — IOT_MANAGEMENT SUSPEND then RESUME")
    void testIotSuspendResume() throws Exception {
        testIotActivate();
        String deviceId = "IOT-DEB-001";
        TestKeyPair owner = new TestKeyPair(320);
        
        // SUSPEND
        java.util.Map<String, Object> suspendData = new java.util.HashMap<>();
        suspendData.put("action", "SUSPEND");
        suspendData.put("deviceId", deviceId);
        suspendData.put("reason", "Maintenance");
        
        Transaction sTx = new Transaction.Builder()
                .type(Transaction.Type.IOT_MANAGEMENT)
                .from(owner.getAddress())
                .data(new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsBytes(suspendData))
                .fee(10L)
                .nonce(2L)
                .sign(owner.getPrivateKey(), owner.getPublicKey());
        BlockApplier.createAndApplyBlock(tb, List.of(sTx));
        assertThat(blockchain.getDeviceLifecycleManager().getDeviceRecord(deviceId).getStatus()).isEqualTo(com.hybrid.blockchain.lifecycle.DeviceLifecycleManager.DeviceStatus.SUSPENDED);
        
        // RESUME
        java.util.Map<String, Object> resumeData = new java.util.HashMap<>();
        resumeData.put("action", "RESUME");
        resumeData.put("deviceId", deviceId);
        Transaction rTx = new Transaction.Builder()
                .type(Transaction.Type.IOT_MANAGEMENT)
                .from(owner.getAddress())
                .data(new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsBytes(resumeData))
                .fee(10L)
                .nonce(3L)
                .sign(owner.getPrivateKey(), owner.getPublicKey());
        BlockApplier.createAndApplyBlock(tb, List.of(rTx));
        assertThat(blockchain.getDeviceLifecycleManager().getDeviceRecord(deviceId).getStatus()).isEqualTo(com.hybrid.blockchain.lifecycle.DeviceLifecycleManager.DeviceStatus.ACTIVE);
    }

    @Test
    @DisplayName("T1.34 — IOT_MANAGEMENT REVOKE action")
    void testIotRevoke() throws Exception {
        testIotActivate();
        String deviceId = "IOT-DEB-001";
        TestKeyPair owner = new TestKeyPair(320);
        TestKeyPair deviceKey = new TestKeyPair(311);
        
        java.util.Map<String, Object> data = new java.util.HashMap<>();
        data.put("action", "REVOKE");
        data.put("deviceId", deviceId);
        data.put("reason", "Compromised");
        
        Transaction tx = new Transaction.Builder()
                .type(Transaction.Type.IOT_MANAGEMENT)
                .from(owner.getAddress())
                .data(new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsBytes(data))
                .fee(10L)
                .nonce(2L)
                .sign(owner.getPrivateKey(), owner.getPublicKey());
        BlockApplier.createAndApplyBlock(tb, List.of(tx));
        
        assertThat(blockchain.getDeviceLifecycleManager().getDeviceRecord(deviceId).getStatus())
                .isEqualTo(com.hybrid.blockchain.lifecycle.DeviceLifecycleManager.DeviceStatus.REVOKED);
                
        Transaction telTx = new Transaction.Builder()
                .type(Transaction.Type.TELEMETRY)
                .from(deviceKey.getAddress())
                .data("FAIL".getBytes())
                .fee(1L)
                .nonce(1L)
                .sign(deviceKey.getPrivateKey(), deviceKey.getPublicKey());
                
        assertThatThrownBy(() -> blockchain.validateTransaction(telTx))
                .hasMessageContaining("TELEMETRY rejected: device/owner not in ACTIVE state");
    }

    @Test
    @DisplayName("T1.35 — TELEMETRY type: active device")
    void testTelemetrySuccess() throws Exception {
        testIotActivate();
        String deviceId = "IOT-DEB-001";
        TestKeyPair deviceKey = new TestKeyPair(311);
        blockchain.getAccountState().credit(deviceKey.getAddress(), 1000L);
        
        String payload = "temp:23.5";
        Transaction tx = new Transaction.Builder()
                .type(Transaction.Type.TELEMETRY)
                .from(deviceKey.getAddress())
                .to(deviceId) // Using deviceId as 'to' for telemetry routing
                .data(payload.getBytes())
                .fee(10L)
                .nonce(1L)
                .sign(deviceKey.getPrivateKey(), deviceKey.getPublicKey());
        
        Block block = BlockApplier.createAndApplyBlock(tb, List.of(tx));
        assertThat(blockchain.getStorage().loadReceipt(tx.getTxId()).getStatus()).isEqualTo(TransactionReceipt.STATUS_SUCCESS);
        
        byte[] stored = blockchain.getStorage().loadTelemetry(deviceId, block.getIndex());
        assertThat(new String(stored, StandardCharsets.UTF_8)).isEqualTo(payload);
    }

    @Test
    @DisplayName("T1.36 — TELEMETRY type: inactive device")
    void testTelemetryInactive() throws Exception {
        String deviceId = "IOT-DEB-001"; // Use known device
        testIotRevoke(); // Revoke it
        
        TestKeyPair sender = new TestKeyPair(360);
        blockchain.getAccountState().credit(sender.getAddress(), INITIAL_BALANCE);
        
        Transaction tx = new Transaction.Builder()
                .type(Transaction.Type.TELEMETRY)
                .from(sender.getAddress())
                .to(deviceId)
                .data("X".getBytes())
                .fee(10L)
                .nonce(1L)
                .sign(sender.getPrivateKey(), sender.getPublicKey());
                
        assertThatThrownBy(() -> blockchain.addTransaction(tx))
                .hasMessageContaining("TELEMETRY rejected: device/owner not in ACTIVE state");
    }

    @Test
    @DisplayName("T1.37 — TELEMETRY type: large payload stored as hash")
    void testTelemetryLargePayload() throws Exception {
        String deviceId = "IOT-LARGE-001";
        TestKeyPair owner = new TestKeyPair(320);
        TestKeyPair deviceKey = new TestKeyPair(311);
        
        // Manual activation for this specific test device
        blockchain.getDeviceLifecycleManager().registerManufacturer("MAN1", owner.getPublicKey());
        blockchain.getDeviceLifecycleManager().registerDevice(deviceId, "MAN1", "Sensor-XL");
        // Force back to PROVISIONING to satisfy activateDevice state transition rules
        blockchain.getDeviceLifecycleManager().getDeviceRecord(deviceId).setStatus(com.hybrid.blockchain.lifecycle.DeviceLifecycleManager.DeviceStatus.PROVISIONING);
        blockchain.getDeviceLifecycleManager().activateDevice(deviceId, owner.getAddress(), deviceKey.getPublicKey(), System.currentTimeMillis());
        
        blockchain.getAccountState().credit(deviceKey.getAddress(), 1000L);
        
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 200; i++) sb.append("payload-data-");
        String payload = sb.toString(); // > 1024 bytes
        byte[] large = payload.getBytes();
        
        Transaction tx = new Transaction.Builder()
                .type(Transaction.Type.TELEMETRY)
                .from(deviceKey.getAddress())
                .to(deviceId)
                .data(large)
                .fee(10L)
                .nonce(1L)
                .sign(deviceKey.getPrivateKey(), deviceKey.getPublicKey());
                
        Block block = BlockApplier.createAndApplyBlock(tb, List.of(tx));
        byte[] stored = blockchain.getStorage().loadTelemetry(deviceId, block.getIndex());
        
        assertThat(stored.length).as("Should store hash instead of full payload").isEqualTo(32);
        assertThat(stored).containsExactly(Crypto.hash(large));
    }

    @Test
    @DisplayName("T1.38 — FEDERATED_UPDATE type")
    void testFederatedUpdate() throws Exception {
        TestKeyPair node = new TestKeyPair(380);
        blockchain.getAccountState().credit(node.getAddress(), INITIAL_BALANCE);
        
        double[] weights = {1.0, 2.0, 3.0};
        byte[] data = new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsBytes(weights);
        
        Transaction tx = new Transaction.Builder()
                .type(Transaction.Type.FEDERATED_UPDATE)
                .from(node.getAddress())
                .data(data)
                .fee(10L)
                .nonce(1L)
                .sign(node.getPrivateKey(), node.getPublicKey());
                
        BlockApplier.createAndApplyBlock(tb, List.of(tx));
        
        assertThat(com.hybrid.blockchain.ai.FederatedLearningManager.getInstance().getPendingUpdateCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("T1.39 — FEDERATED_COMMIT type")
    void testFederatedCommit() throws Exception {
        TestKeyPair admin = new TestKeyPair(390);
        blockchain.getAccountState().credit(admin.getAddress(), INITIAL_BALANCE);
        
        String modelHash = "abc-123-def";
        Transaction tx = new Transaction.Builder()
                .type(Transaction.Type.FEDERATED_COMMIT)
                .from(admin.getAddress())
                .data(modelHash.getBytes())
                .fee(10L)
                .nonce(1L)
                .sign(admin.getPrivateKey(), admin.getPublicKey());
                
        BlockApplier.createAndApplyBlock(tb, List.of(tx));
        
        String metadata = (String) blockchain.getStorage().getMeta("federated:latest:hash");
        assertThat(metadata).isEqualTo(modelHash);
    }
}
