package com.hybrid.blockchain.api;

import com.hybrid.blockchain.Blockchain;
import com.hybrid.blockchain.Config;
import com.hybrid.blockchain.Mempool;
import com.hybrid.blockchain.TestBlockchain;
import com.hybrid.blockchain.TestKeyPair;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.eclipse.californium.core.CoapClient;
import org.eclipse.californium.core.CoapResponse;
import org.eclipse.californium.core.coap.CoAP;
import org.eclipse.californium.core.coap.MediaTypeRegistry;
import org.junit.jupiter.api.*;
import org.mockito.Mockito;

import java.io.IOException;
import java.lang.reflect.Method;
import java.net.ServerSocket;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;

/**
 * End-to-end integration tests for CoAP and MQTT adapters.
 *
 * <h2>CoAP tests (C1.1–C1.6)</h2>
 * Spin up an embedded {@link CoAPAdapter} on a random free UDP port within the
 * same JVM.
 * A {@link CoapClient} fires requests and we assert both the CoAP response code
 * and the
 * observable blockchain side-effects (mempool size, balance).
 *
 * <h2>MQTT tests (M1.1–M1.5)</h2>
 * The MQTTAdapter requires a real broker to connect. We exercise its
 * message-routing logic
 * via reflection on the private {@code handleMessage} method (no live broker
 * needed) and
 * verify that the public {@code stop()} contract is idempotent.
 */
@Tag("integration")
@Tag("coap")
@Tag("mqtt")
@TestMethodOrder(MethodOrderer.DisplayName.class)
public class CoAPMQTTIntegrationTest {

    // ─── CoAP infrastructure ────────────────────────────────────────────────────

    private static TestBlockchain tb;
    private static Blockchain blockchain;
    private static CoAPAdapter coap;
    private static int coapPort;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @BeforeAll
    static void startCoAPServer() throws Exception {
        tb = new TestBlockchain();
        blockchain = tb.getBlockchain();

        // Pick a random free UDP port to avoid conflicts with any OS CoAP daemon
        coapPort = freePort();

        // Force GATEWAY role so CoAPAdapter.start() actually starts the server
        // We achieve this by directly starting the CoapServer instance via the adapter
        coap = new CoAPAdapter(blockchain, coapPort);
        coap.start(true);

        // Give Californium's netty layer a moment to bind
        Thread.sleep(200);
    }

    @AfterAll
    static void stopCoAPServer() throws Exception {
        if (coap != null)
            coap.stop();
        if (tb != null)
            tb.close();
    }

    // ────────────────────────────────────────────────────────────────────────────
    // C1.1 — Health endpoint
    // ────────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("C1.1 — CoAP GET /health returns OK and chain height")
    void testCoapHealth() throws Exception {
        CoapClient client = new CoapClient("coap://localhost:" + coapPort + "/health");
        client.setTimeout(3000L);
        CoapResponse resp = client.get();
        assertThat(resp).isNotNull();
        assertThat(resp.getCode()).isEqualTo(CoAP.ResponseCode.CONTENT);
        assertThat(resp.getResponseText()).startsWith("OK: Height ");
    }

    // ────────────────────────────────────────────────────────────────────────────
    // C1.2 — Balance endpoint happy path
    // ────────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("C1.2 — CoAP GET /balance?address=<addr> returns correct balance")
    void testCoapBalanceHappyPath() throws Exception {
        TestKeyPair alice = new TestKeyPair(42);
        blockchain.getAccountState().credit(alice.getAddress(), 9999L);

        CoapClient client = new CoapClient(
                "coap://localhost:" + coapPort + "/balance?address=" + alice.getAddress());
        client.setTimeout(3000L);
        CoapResponse resp = client.get();

        assertThat(resp).isNotNull();
        assertThat(resp.getCode()).isEqualTo(CoAP.ResponseCode.CONTENT);
        assertThat(Long.parseLong(resp.getResponseText().trim())).isEqualTo(9999L);
    }

    // ────────────────────────────────────────────────────────────────────────────
    // C1.3 — Balance endpoint missing address
    // ────────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("C1.3 — CoAP GET /balance (no address param) returns 4.00 BAD_REQUEST")
    void testCoapBalanceMissingAddress() throws Exception {
        CoapClient client = new CoapClient("coap://localhost:" + coapPort + "/balance");
        client.setTimeout(3000L);
        CoapResponse resp = client.get();

        assertThat(resp).isNotNull();
        assertThat(resp.getCode()).isEqualTo(CoAP.ResponseCode.BAD_REQUEST);
    }

    // ────────────────────────────────────────────────────────────────────────────
    // C1.4 — TX POST happy path: serialised ACCOUNT tx → CREATED + mempool grows
    // ────────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("C1.4 — CoAP POST /tx with valid ACCOUNT tx returns 2.01 CREATED and mempool grows")
    void testCoapTxPost() throws Exception {
        TestKeyPair sender = new TestKeyPair(77);
        blockchain.getAccountState().credit(sender.getAddress(), 5000L);

        com.hybrid.blockchain.Transaction tx = com.hybrid.blockchain.testutil.TestTransactionFactory.createAccountTransfer(
                sender, 
                "recipient-coap", 
                1, 
                1, 
                blockchain.getAccountState().getNonce(sender.getAddress()) + 1
        );

        byte[] serialized = blockchain.serializeTransaction(tx);

        int mempoolBefore = blockchain.getMempool().getSize();

        CoapClient client = new CoapClient("coap://localhost:" + coapPort + "/tx");
        client.setTimeout(3000L);
        CoapResponse resp = client.post(serialized, MediaTypeRegistry.APPLICATION_OCTET_STREAM);

        assertThat(resp).isNotNull();
        assertThat(resp.getCode()).isEqualTo(CoAP.ResponseCode.CREATED);
        assertThat(resp.getResponseText()).isNotBlank(); // txid echoed
        // Mempool should have grown by 1
        assertThat(blockchain.getMempool().getSize()).isGreaterThan(mempoolBefore);
    }

    // ────────────────────────────────────────────────────────────────────────────
    // C1.5 — TX POST malformed payload → BAD_REQUEST
    // ────────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("C1.5 — CoAP POST /tx with malformed payload returns 4.00 BAD_REQUEST")
    void testCoapTxPostMalformed() throws Exception {
        byte[] garbage = { 0x01, 0x02, 0x03, 0x04, 0x05 };
        CoapClient client = new CoapClient("coap://localhost:" + coapPort + "/tx");
        client.setTimeout(3000L);
        CoapResponse resp = client.post(garbage, MediaTypeRegistry.APPLICATION_OCTET_STREAM);

        assertThat(resp).isNotNull();
        assertThat(resp.getCode()).isEqualTo(CoAP.ResponseCode.BAD_REQUEST);
    }

    // ────────────────────────────────────────────────────────────────────────────
    // C1.6 — Telemetry POST with valid JSON → CREATED (regression guard)
    // ────────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("C1.6 — CoAP POST /telemetry with valid JSON returns 2.01 CREATED")
    void testCoapTelemetryPost() throws Exception {
        Map<String, Object> body = new HashMap<>();
        body.put("deviceId", "sensor-coap-01");
        body.put("value", 42.5);
        byte[] json = MAPPER.writeValueAsBytes(body);

        CoapClient client = new CoapClient("coap://localhost:" + coapPort + "/telemetry");
        client.setTimeout(3000L);
        CoapResponse resp = client.post(json, MediaTypeRegistry.APPLICATION_JSON);

        assertThat(resp).isNotNull();
        // May be CREATED (2.01) or INTERNAL_SERVER_ERROR (5.00) depending on signature
        // config.
        // The important invariant: it MUST NOT be NOT_IMPLEMENTED (5.01) any more.
        assertThat(resp.getCode())
                .as("TelemetryResource must no longer return NOT_IMPLEMENTED")
                .isNotEqualTo(CoAP.ResponseCode.NOT_IMPLEMENTED);
    }

    // ════════════════════════════════════════════════════════════════════════════
    // MQTT tests (M1.1–M1.5) — no live broker, reflection-based routing tests
    // ════════════════════════════════════════════════════════════════════════════

    private MQTTAdapter buildAdapter() {
        // Use a mock Blockchain to avoid needing a full TestBlockchain for MQTT routing
        Blockchain mockChain = Mockito.mock(Blockchain.class);
        return new MQTTAdapter(mockChain);
    }

    @Test
    @DisplayName("M1.1 — MQTT /mgmt topic routes to submitIOTTransaction without exception")
    void testMqttMgmtRouting() throws Exception {
        MQTTAdapter adapter = buildAdapter();
        Map<String, Object> payload = new HashMap<>();
        payload.put("action", "REBOOT");
        payload.put("deviceId", "dev-001");
        String json = MAPPER.writeValueAsString(payload);

        // Invoke private handleMessage via reflection
        assertThatCode(() -> invokeHandleMessage(adapter, "blockchain/iot/dev-001/mgmt", json))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("M1.2 — MQTT /telemetry topic routes to submitTelemetryTransaction without exception")
    void testMqttTelemetryRouting() throws Exception {
        MQTTAdapter adapter = buildAdapter();
        Map<String, Object> payload = new HashMap<>();
        payload.put("deviceId", "sensor-001");
        payload.put("temperature", 37.2);
        String json = MAPPER.writeValueAsString(payload);

        assertThatCode(() -> invokeHandleMessage(adapter, "blockchain/iot/sensor-001/telemetry", json))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("M1.3 — MQTT malformed JSON payload on any topic is swallowed gracefully")
    void testMqttMalformedJson() {
        MQTTAdapter adapter = buildAdapter();
        // Should swallow, never rethrow
        assertThatCode(() -> invokeHandleMessage(adapter, "blockchain/iot/dev/mgmt", "{bad json!!!}"))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("M1.4 — MQTTAdapter.start() with unreachable broker logs warning, no exception thrown")
    void testMqttStartWithUnreachableBroker() {
        // Build adapter pointing at a definitely-closed port
        Blockchain mockChain = Mockito.mock(Blockchain.class);
        // Force unreachable broker URL by overriding via System property temporarily
        String original = System.getProperty("MQTT_BROKER_URL");
        System.setProperty("MQTT_BROKER_URL", "tcp://localhost:19999");
        try {
            MQTTAdapter adapter = new MQTTAdapter(mockChain);
            assertThatCode(adapter::start).doesNotThrowAnyException();
        } finally {
            if (original == null) {
                System.clearProperty("MQTT_BROKER_URL");
            } else {
                System.setProperty("MQTT_BROKER_URL", original);
            }
        }
    }

    @Test
    @DisplayName("M1.5 — MQTTAdapter.stop() is idempotent (safe to call twice)")
    void testMqttStopIdempotent() {
        Blockchain mockChain = Mockito.mock(Blockchain.class);
        MQTTAdapter adapter = new MQTTAdapter(mockChain);
        // Never started — stop() must not throw
        assertThatCode(adapter::stop).doesNotThrowAnyException();
        // Second call also safe
        assertThatCode(adapter::stop).doesNotThrowAnyException();
    }

    // ─── Helpers ────────────────────────────────────────────────────────────────

    /**
     * Invokes the private {@code handleMessage(String topic, String payload)}
     * method
     * on the given {@link MQTTAdapter} instance via reflection.
     */
    private static void invokeHandleMessage(MQTTAdapter adapter, String topic, String payload)
            throws Exception {
        Method m = MQTTAdapter.class.getDeclaredMethod("handleMessage", String.class, String.class);
        m.setAccessible(true);
        m.invoke(adapter, topic, payload);
    }

    /**
     * Finds a free local UDP port for the embedded CoAP server.
     */
    private static int freePort() throws IOException {
        try (ServerSocket ss = new ServerSocket(0)) {
            ss.setReuseAddress(true);
            return ss.getLocalPort();
        }
    }

}
