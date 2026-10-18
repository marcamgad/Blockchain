package com.hybrid.blockchain.audit;

import com.hybrid.blockchain.TestKeyPair;
import com.hybrid.blockchain.Transaction;
import com.hybrid.blockchain.api.IoTRestAPI;
import com.hybrid.blockchain.api.JwtManager;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/** [HC-054] REST submission of client-signed transactions, against a real chain. */
class SignedSubmissionTest {

    private static IoTRestAPI api(LedgerPoCFixture f, JwtManager jwt) throws Exception {
        IoTRestAPI api = new IoTRestAPI();
        for (String[] fv : new String[][]{{"blockchain", "chain"}, {"jwtManager", "jwt"}}) {
            Field field = IoTRestAPI.class.getDeclaredField(fv[0]);
            field.setAccessible(true);
            field.set(api, fv[1].equals("chain") ? f.chain : jwt);
        }
        return api;
    }

    @Test
    @DisplayName("signed tx from the token's owner -> 200 and in mempool; wrong owner / tampered / no token -> refused")
    void submitSigned() throws Exception {
        try (LedgerPoCFixture f = new LedgerPoCFixture()) {
            TestKeyPair alice = new TestKeyPair(8601), mallory = new TestKeyPair(8602);
            f.fund(alice.getAddress());
            JwtManager jwt = new JwtManager();
            IoTRestAPI api = api(f, jwt);
            long fee = Math.max(1, f.chain.getCurrentBaseFee());
            Transaction tx = LedgerPoCFixture.transfer(alice, "bob", 5, fee, 1);
            byte[] json = f.chain.serializeTransaction(tx);

            ResponseEntity<?> noToken = api.submitSignedTransaction(json, null);
            ResponseEntity<?> wrongOwner = api.submitSignedTransaction(json, "Bearer " + jwt.issueToken(mallory.getAddress()));
            String tamperedJson = new String(json, StandardCharsets.UTF_8).replace("\"amount\":5", "\"amount\":500");
            ResponseEntity<?> tampered = api.submitSignedTransaction(tamperedJson.getBytes(StandardCharsets.UTF_8),
                    "Bearer " + jwt.issueToken(alice.getAddress()));
            ResponseEntity<?> ok = api.submitSignedTransaction(json, "Bearer " + jwt.issueToken(alice.getAddress()));

            assertThat(noToken.getStatusCode().value()).isEqualTo(401);
            assertThat(wrongOwner.getStatusCode().value()).isEqualTo(401);
            assertThat(tampered.getStatusCode().value()).as(String.valueOf(tampered.getBody())).isEqualTo(400);
            assertThat(ok.getStatusCode().value()).as(String.valueOf(ok.getBody())).isEqualTo(200);
            assertThat(f.chain.getMempool().contains(tx.getId())).isTrue();
            assertThat(f.chain.getMempool().size()).isEqualTo(1);
        }
    }

    @Test
    @DisplayName("HC-055: a federated update must come from the node it names")
    void federatedUpdateBoundToCaller() throws Exception {
        try (LedgerPoCFixture f = new LedgerPoCFixture()) {
            JwtManager jwt = new JwtManager();
            IoTRestAPI api = api(f, jwt);
            com.hybrid.blockchain.ai.FederatedLearningManager.getInstance().reset();
            java.util.Map<String, Object> body = new java.util.HashMap<>();
            body.put("nodeId", "node-A");
            body.put("weights", java.util.List.of(0.1, 0.2));
            assertThat(api.submitFederatedUpdate(body, "Bearer " + jwt.issueToken("node-B")).getStatusCode().value())
                    .as("impersonation").isEqualTo(401);
            assertThat(api.submitFederatedUpdate(body, null).getStatusCode().value()).isEqualTo(401);
            assertThat(api.submitFederatedUpdate(body, "Bearer " + jwt.issueToken("node-A")).getStatusCode().value())
                    .isEqualTo(200);
            com.hybrid.blockchain.ai.FederatedLearningManager.getInstance().reset();
        }
    }

    @Test
    @DisplayName("HC-056: address history is returned and bounded; telemetry range is bounded")
    void readEndpointsBounded() throws Exception {
        try (LedgerPoCFixture f = new LedgerPoCFixture()) {
            String alice = new TestKeyPair(8603).getAddress();
            f.fund(alice);                                      // 1 transfer touching alice -> 1 index entry
            IoTRestAPI api = api(f, new JwtManager());
            Object page = api.getAddressTransactions(alice, 50, null).getBody();
            assertThat((java.util.List<?>) page).as("default call used to return nothing").hasSize(1);
            Object clamped = api.getAddressTransactions(alice, 1_000_000, null).getBody();
            assertThat((java.util.List<?>) clamped).hasSizeLessThanOrEqualTo(100);
            assertThat(api.getDeviceTelemetry("d", 0, 50_000).getStatusCode().value()).isEqualTo(400);
            assertThat(api.getDeviceTelemetry("d", 10, 5).getStatusCode().value()).isEqualTo(400);
            assertThat(api.getDeviceTelemetry("d", 0, 100).getStatusCode().value()).isEqualTo(200);
        }
    }

    @Test
    @DisplayName("the retired unsigned endpoint answers 410 Gone")
    void unsignedEndpointRetired() throws Exception {
        try (LedgerPoCFixture f = new LedgerPoCFixture()) {
            assertThat(api(f, new JwtManager()).submitTransaction(null, "Bearer x").getStatusCode().value()).isEqualTo(410);
        }
    }
}
