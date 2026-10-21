package com.hybrid.blockchain.audit;

import com.hybrid.blockchain.TestKeyPair;
import com.hybrid.blockchain.TokenRegistry;
import com.hybrid.blockchain.Transaction;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * [Item 9] Rejection rules of Blockchain.validateTransaction that no test exercised. Each case
 * asserts the specific reason, so a rule that silently stops firing fails here.
 */
class ValidationRulesTest {

    private LedgerPoCFixture f;
    private final TestKeyPair alice = new TestKeyPair(9901), poor = new TestKeyPair(9902);
    private long fee;

    @BeforeEach
    void setUp() throws Exception {
        f = new LedgerPoCFixture();
        f.fund(alice.getAddress());
        fee = Math.max(1, f.chain.getCurrentBaseFee());
    }

    @AfterEach
    void tearDown() throws Exception {
        f.close();
    }

    private Transaction.Builder b(Transaction.Type t) {
        return new Transaction.Builder().type(t).fee(fee);
    }

    private Transaction signed(Transaction.Builder bld, TestKeyPair k) {
        return bld.from(k.getAddress()).sign(k.getPrivateKey(), k.getPublicKey());
    }

    private void rejects(Transaction tx, String reason) {
        assertThatThrownBy(() -> f.chain.validateTransaction(tx)).hasMessageContaining(reason);
    }

    private static byte[] utf8(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    @Test
    @DisplayName("generic rules: negative values, expiry, nonce gap, MINT with a sender")
    void genericRules() {
        rejects(signed(b(Transaction.Type.ACCOUNT).to("hbx").amount(-1).nonce(1), alice), "Negative amount or fee");
        rejects(signed(b(Transaction.Type.ACCOUNT).to("hbx").amount(1).nonce(1).validUntilBlock(1), alice), "Transaction expired");
        rejects(signed(b(Transaction.Type.ACCOUNT).to("hbx").amount(1).nonce(500), alice), "Nonce too far in future");
        rejects(signed(b(Transaction.Type.MINT).to(alice.getAddress()).amount(1).nonce(1), alice), "MINT must be system-initiated");
    }

    @Test
    @DisplayName("every type that needs a sender refuses an unsigned transaction without one")
    void senderRequired() {
        rejects(b(Transaction.Type.TELEMETRY_BATCH).data(utf8("[]")).build(), "TELEMETRY_BATCH must have a gateway from address");
        rejects(b(Transaction.Type.TOKEN_TRANSFER).to("hbx").data(utf8("T")).build(), "TOKEN_TRANSFER must have a from address");
        rejects(b(Transaction.Type.TOKEN_REGISTER).data(utf8("{}")).build(), "TOKEN_REGISTER must have a from address");
        rejects(b(Transaction.Type.TOKEN_MINT).to("hbx").data(utf8("T")).build(), "TOKEN_MINT must have a from address");
        rejects(b(Transaction.Type.TOKEN_BURN).data(utf8("T")).build(), "TOKEN_BURN must have a from address");
        rejects(b(Transaction.Type.EQUIVOCATION_EVIDENCE).data(utf8("{}")).build(), "EQUIVOCATION_EVIDENCE must have a reporter");
    }

    @Test
    @DisplayName("missing recipient or missing payload is refused per type")
    void fieldsRequired() {
        // the generic destination rule fires first; the per-type "must have a to address" checks in
        // validateTransaction are therefore unreachable (dead branches, documented in the commit)
        rejects(signed(b(Transaction.Type.TOKEN_TRANSFER).data(utf8("T")).nonce(1), alice), "Missing destination");
        rejects(signed(b(Transaction.Type.TOKEN_MINT).data(utf8("T")).nonce(1), alice), "Missing destination");
        rejects(signed(b(Transaction.Type.TELEMETRY_BATCH).nonce(1), alice), "TELEMETRY_BATCH data is empty");
        rejects(signed(b(Transaction.Type.TOKEN_TRANSFER).to("hbx").nonce(1), alice), "TOKEN_TRANSFER missing tokenId");
        rejects(signed(b(Transaction.Type.TOKEN_REGISTER).nonce(1), alice), "TOKEN_REGISTER missing token metadata");
        rejects(signed(b(Transaction.Type.TOKEN_REGISTER).data(utf8("{\"name\":\"x\"}")).nonce(1), alice), "TOKEN_REGISTER missing tokenId");
        rejects(signed(b(Transaction.Type.TOKEN_MINT).to("hbx").nonce(1), alice), "TOKEN_MINT missing tokenId");
        rejects(signed(b(Transaction.Type.TOKEN_BURN).nonce(1), alice), "TOKEN_BURN missing tokenId");
    }

    @Test
    @DisplayName("a wrong nonce is refused for every account-based type")
    void nonceChecked() {
        String id = "\"tokenId\":\"T\"";
        rejects(signed(b(Transaction.Type.CONTRACT).to("hbcontract").nonce(7), alice), "Invalid nonce");
        rejects(signed(b(Transaction.Type.IOT_MANAGEMENT).data(utf8("{\"action\":\"SUSPEND\"}")).nonce(7), alice), "Invalid nonce");
        rejects(signed(b(Transaction.Type.BURN).amount(1).nonce(7), alice), "Invalid nonce");
        rejects(signed(b(Transaction.Type.TOKEN_REGISTER).data(utf8("{" + id + "}")).nonce(7), alice), "Invalid nonce");
        rejects(signed(b(Transaction.Type.TOKEN_BURN).data(utf8("T")).nonce(7), alice), "Invalid nonce");
    }

    @Test
    @DisplayName("balance rules: contract value, IoT fee, batch fee, token balances")
    void balancesChecked() {
        rejects(signed(b(Transaction.Type.CONTRACT).to("hbcontract").amount(Long.MAX_VALUE / 4).nonce(1), alice), "Insufficient funds for contract tx");
        rejects(signed(b(Transaction.Type.IOT_MANAGEMENT).data(utf8("{\"action\":\"SUSPEND\"}")).nonce(1), poor), "Insufficient funds for iot tx fee");
        rejects(signed(b(Transaction.Type.TELEMETRY_BATCH).data(utf8("[]")).nonce(1), poor), "Insufficient fee for TELEMETRY_BATCH");
        rejects(signed(b(Transaction.Type.TOKEN_BURN).amount(5).data(utf8("T")).nonce(1), alice), "Insufficient T balance for burn");
    }

    @Test
    @DisplayName("token minting: registry required, token must exist, only the owner, capped by max supply")
    void tokenMintRules() throws Exception {
        // a Blockchain always has a registry, so "TokenRegistry not configured" is unreachable here
        TokenRegistry reg = f.chain.getTokenRegistry();
        assertThat(reg).isNotNull();
        rejects(signed(b(Transaction.Type.TOKEN_MINT).to("hbx").amount(1).data(utf8("NOPE")).nonce(1), alice), "Unknown token: NOPE");
        reg.registerToken("CAP", "Capped", "CAP", 0, 10, alice.getAddress());
        rejects(signed(b(Transaction.Type.TOKEN_MINT).to("hbx").amount(1).data(utf8("CAP")).nonce(1), poor), "Only token owner can mint");
        rejects(signed(b(Transaction.Type.TOKEN_MINT).to("hbx").amount(11).data(utf8("CAP")).nonce(1), alice), "Minting would exceed max supply");
        rejects(signed(b(Transaction.Type.TOKEN_MINT).to("hbx").amount(1).data(utf8("CAP")).nonce(7), alice), "Invalid nonce");
        rejects(signed(b(Transaction.Type.TOKEN_TRANSFER).to("hbx").amount(1).data(utf8("CAP")).nonce(1), alice), "Insufficient CAP balance");
    }
}
