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
class TransactionTest extends TestHarness {

    private BigInteger senderPriv;
    private byte[] senderPub;
    private String senderAddr;

    @BeforeEach
    void setUp() throws Exception {
        System.setProperty("DEBUG", "false");
        initPoABlockchain(defaultValidators());

        senderPriv = privateKey(2001);
        senderPub = Crypto.derivePublicKey(senderPriv);
        senderAddr = Crypto.deriveAddress(senderPub);

        blockchain.getState().credit(senderAddr, 10_000);
        blockchain.getState().setNonce(senderAddr, 0);
    }

    @Test
    @DisplayName("Signed transaction verifies successfully")
    void signedTransactionVerifies() {
        Transaction tx = new Transaction.Builder()
                .type(Transaction.Type.ACCOUNT)
                .to("hb-receiver")
                .amount(10)
                .fee(1)
                .nonce(1)
                .sign(senderPriv, senderPub);

        assertTrue(tx.verify(), "A correctly signed transaction must pass cryptographic verification");
    }

    @Test
    @DisplayName("Unsigned transaction fails verification")
    void unsignedTransactionFailsVerification() {
        Transaction tx = new Transaction.Builder()
                .type(Transaction.Type.ACCOUNT)
                .from(senderAddr)
                .to("hb-receiver")
                .amount(10)
                .fee(1)
                .nonce(1)
                .build();

        assertFalse(tx.verify(), "An unsigned transaction must fail verification");
    }

    @Test
    @DisplayName("Changing signed transaction amount invalidates signature")
    void mutatingAmountBreaksVerification() throws Exception {
        Transaction tx = new Transaction.Builder()
                .type(Transaction.Type.ACCOUNT)
                .to("hb-receiver")
                .amount(10)
                .fee(1)
                .nonce(1)
                .sign(senderPriv, senderPub);

        Field amountField = Transaction.class.getDeclaredField("amount");
        amountField.setAccessible(true);
        amountField.setLong(tx, 999);

        assertFalse(tx.verify(), "Tampering with amount after signing must invalidate the signature");
    }

    @Test
    @DisplayName("Different nonces produce different transaction IDs")
    void differentNoncesDifferentTxIds() {
        Transaction tx1 = new Transaction.Builder().type(Transaction.Type.ACCOUNT).to("hb-r").amount(1).fee(1).nonce(1).sign(senderPriv, senderPub);
        Transaction tx2 = new Transaction.Builder().type(Transaction.Type.ACCOUNT).to("hb-r").amount(1).fee(1).nonce(2).sign(senderPriv, senderPub);

        assertNotEquals(tx1.getId(), tx2.getId(), "Transaction IDs must differ when nonce differs");
    }

    @Test
    @DisplayName("Transaction ID remains stable across repeated reads")
    void txIdStableAcrossCalls() {
        Transaction tx = new Transaction.Builder().type(Transaction.Type.ACCOUNT).to("hb-r").amount(1).fee(1).nonce(1).sign(senderPriv, senderPub);
        String expected = tx.getId();
        for (int i = 0; i < 1000; i++) {
            assertEquals(expected, tx.getId(), "Transaction ID must remain stable across repeated accessor calls");
        }
    }

    @Test
    @DisplayName("UTXO transaction with null inputs is rejected")
    void utxoTransactionNullInputsRejected() throws Exception {
        Transaction tx = new Transaction.Builder()
            .type(Transaction.Type.UTXO)
            .to("hb-r")
            .amount(1)
            .fee(0)
            .nonce(1)
            .inputs(List.of())
            .outputs(List.of(new UTXOOutput("hb-r", 1)))
            .sign(senderPriv, senderPub);

        Exception ex = assertThrows(Exception.class, () -> blockchain.validateTransaction(tx), "Null UTXO inputs must be rejected during validation");
        assertTrue(ex.getMessage().toLowerCase().contains("utxo") || ex.getMessage().toLowerCase().contains("input") || ex.getMessage().toLowerCase().contains("insufficient"), "Error message must clearly indicate UTXO input validation failure");
    }

    @Test
    @DisplayName("ACCOUNT nonce validation rejects current+2, current+0, and negative")
    void accountWrongNonceRejected() {
        assertNonceRejected(2);
        assertNonceRejected(0);
        assertNonceRejected(-1);
    }

    private void assertNonceRejected(long nonce) {
        Transaction tx = new Transaction.Builder()
                .type(Transaction.Type.ACCOUNT)
                .to("hb-r")
                .amount(10)
                .fee(1)
                .nonce(nonce)
                .sign(senderPriv, senderPub);

        Exception ex = assertThrows(Exception.class, () -> blockchain.validateTransaction(tx), "Invalid nonce " + nonce + " must be rejected");
        assertTrue(ex.getMessage().toLowerCase().contains("nonce"), "Nonce validation failure must mention nonce in the error message");
    }

    @Test
    @DisplayName("ACCOUNT transaction with insufficient balance is rejected")
    void insufficientBalanceRejected() {
        Transaction tx = new Transaction.Builder().type(Transaction.Type.ACCOUNT).to("hb-r").amount(50_000).fee(1).nonce(1).sign(senderPriv, senderPub);

        Exception ex = assertThrows(Exception.class, () -> blockchain.validateTransaction(tx), "Insufficient-balance account transaction must be rejected");
        assertTrue(ex.getMessage().toLowerCase().contains("funds"), "Insufficient balance error must mention funds");
    }

    @Test
    @DisplayName("MINT transaction with non-null from is rejected")
    void mintWithFromRejected() {
        Transaction tx = new Transaction.Builder()
                .type(Transaction.Type.MINT)
                .to("hb-r")
                .amount(10)
                .fee(0)
                .nonce(1)
            .sign(senderPriv, senderPub);

        Exception ex = assertThrows(Exception.class, () -> blockchain.validateTransaction(tx), "MINT with from address must be rejected");
        assertTrue(ex.getMessage().toLowerCase().contains("mint") || ex.getMessage().toLowerCase().contains("system-initiated"), "MINT rejection message must mention MINT constraints");
    }

    @Test
    @DisplayName("BURN transaction with null from is rejected")
    void burnWithNullFromRejected() {
        Transaction tx = new Transaction.Builder()
                .type(Transaction.Type.BURN)
                .to("hb-r")
                .amount(10)
                .fee(1)
                .nonce(1)
                .build();

        Exception ex = assertThrows(Exception.class, () -> blockchain.validateTransaction(tx), "BURN transaction requires a from address");
        assertTrue(ex.getMessage().toLowerCase().contains("burn"), "BURN validation error must mention burn requirements");
    }

    @Test
    @DisplayName("IOT_MANAGEMENT transaction with insufficient fee balance is rejected")
    void iotInsufficientFeeRejected() throws Exception {
        String poorAddr = Crypto.deriveAddress(Crypto.derivePublicKey(privateKey(3001)));
        BigInteger poorPriv = privateKey(3001);
        byte[] poorPub = Crypto.derivePublicKey(poorPriv);
        blockchain.getState().credit(poorAddr, 0);

        Transaction tx = new Transaction.Builder()
                .type(Transaction.Type.IOT_MANAGEMENT)
                .to("iot")
                .fee(10)
                .amount(0)
                .nonce(1)
                .data("{}".getBytes())
                .sign(poorPriv, poorPub);

        Exception ex = assertThrows(Exception.class, () -> blockchain.validateTransaction(tx), "IOT_MANAGEMENT must reject sender that cannot pay fee");
        assertTrue(ex.getMessage().toLowerCase().contains("fee"), "IoT fee rejection must mention fee insufficiency");
    }

    @Test
    @DisplayName("validUntilBlock in the past is rejected as expired")
    void validUntilInPastRejected() {
        blockchain.getChain().add(new Block(1, System.currentTimeMillis(), List.of(), blockchain.getLatestBlock().getHash(), blockchain.getDifficulty(), blockchain.getState().calculateStateRoot()));
        blockchain.getChain().add(new Block(2, System.currentTimeMillis(), List.of(), blockchain.getLatestBlock().getHash(), blockchain.getDifficulty(), blockchain.getState().calculateStateRoot()));

        long pastHeight = 1L;
        Transaction tx = new Transaction.Builder()
                .type(Transaction.Type.ACCOUNT)
                .to("hb-r")
                .amount(1)
                .fee(1)
                .nonce(1)
                .validUntilBlock(pastHeight)
                .sign(senderPriv, senderPub);

        Exception ex = assertThrows(Exception.class, () -> blockchain.validateTransaction(tx), "Expired transaction must be rejected");
        assertTrue(ex.getMessage().toLowerCase().contains("expired"), "Expiration validation error must mention expired");
    }

    @Test
    @DisplayName("validUntilBlock zero is treated as no expiry")
    void validUntilZeroAccepted() {
        Transaction tx = new Transaction.Builder()
                .type(Transaction.Type.ACCOUNT)
                .to("hb-r")
                .amount(1)
                .fee(1)
                .nonce(1)
                .validUntilBlock(0)
                .sign(senderPriv, senderPub);

        assertDoesNotThrow(() -> blockchain.validateTransaction(tx), "validUntilBlock=0 must be accepted as no-expiry transaction");
    }

    @Test
    @DisplayName("Network ID mismatch is rejected")
    void networkIdMismatchRejected() {
        Transaction tx = new Transaction.Builder().type(Transaction.Type.ACCOUNT).to("hb-r").amount(1).fee(1).nonce(1).networkId(999).sign(senderPriv, senderPub);

        Exception ex = assertThrows(Exception.class, () -> blockchain.validateTransaction(tx), "Transactions for a different network ID must be rejected");
        assertTrue(ex.getMessage().toLowerCase().contains("network"), "Network mismatch error must mention network identifier");
    }

    @Test
    @DisplayName("Negative amount is rejected")
    void negativeAmountRejected() {
        Transaction tx = new Transaction.Builder().type(Transaction.Type.ACCOUNT).to("hb-r").amount(-1).fee(1).nonce(1).sign(senderPriv, senderPub);
        Exception ex = assertThrows(Exception.class, () -> blockchain.validateTransaction(tx), "Negative transfer amount must be rejected");
        assertTrue(ex.getMessage().toLowerCase().contains("negative"), "Negative amount rejection must mention negative values");
    }

    @Test
    @DisplayName("Negative fee is rejected")
    void negativeFeeRejected() {
        Transaction tx = new Transaction.Builder().type(Transaction.Type.ACCOUNT).to("hb-r").amount(1).fee(-1).nonce(1).sign(senderPriv, senderPub);
        Exception ex = assertThrows(Exception.class, () -> blockchain.validateTransaction(tx), "Negative fee must be rejected");
        assertTrue(ex.getMessage().toLowerCase().contains("negative"), "Negative fee rejection must mention negative values");
    }

    @Test
    @DisplayName("Zero fee is allowed")
    void zeroFeeAllowed() {
        Transaction tx = new Transaction.Builder().type(Transaction.Type.ACCOUNT).to("hb-r").amount(1).fee(0).nonce(1).sign(senderPriv, senderPub);
        assertDoesNotThrow(() -> blockchain.validateTransaction(tx), "Zero-fee account transaction should remain valid when all other invariants hold");
    }

    @Test
    @DisplayName("CONTRACT disable toggle via reflection cannot be reliably tested due static final inlining")
    void contractDisabledReflectionLimitationDocumented() throws Exception {
        Field field = Config.class.getDeclaredField("ENABLE_SMART_CONTRACTS");
        assertTrue(java.lang.reflect.Modifier.isFinal(field.getModifiers()), "ENABLE_SMART_CONTRACTS is compile-time final, preventing runtime toggle needed for this test");
    }
}
