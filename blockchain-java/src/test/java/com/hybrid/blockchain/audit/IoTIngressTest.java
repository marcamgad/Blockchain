package com.hybrid.blockchain.audit;

import com.hybrid.blockchain.TestKeyPair;
import com.hybrid.blockchain.Transaction;
import com.hybrid.blockchain.api.AbstractIoTMessageAdapter;
import com.hybrid.blockchain.api.EventBus;
import com.hybrid.blockchain.api.MQTTAdapter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** [HC-063] event bus topics/filters, [HC-064] MQTT/CoAP never sign management on a publisher's behalf. */
class IoTIngressTest {

    private static Object call(Object target, String name, Class<?>[] types, Object... args) throws Throwable {
        Method m = AbstractIoTMessageAdapter.class.getDeclaredMethod(name, types);
        m.setAccessible(true);
        try {
            return m.invoke(target, args);
        } catch (InvocationTargetException e) {
            throw e.getCause();
        }
    }

    private static Throwable thrown(ThrowingCall c) {
        try { c.run(); return null; } catch (Throwable t) { return t; }
    }

    interface ThrowingCall { void run() throws Throwable; }

    @Test
    @DisplayName("HC-064: management is never node-signed; only an owner-signed tx is relayed")
    void managementRequiresOwnerSignature() throws Exception {
        try (LedgerPoCFixture f = new LedgerPoCFixture()) {
            MQTTAdapter adapter = new MQTTAdapter(f.chain);
            byte[] suspend = "{\"action\":\"SUSPEND\",\"deviceId\":\"d1\"}".getBytes(StandardCharsets.UTF_8);

            Throwable nodeSigned = thrown(() -> call(adapter, "submitDeviceTx",
                    new Class<?>[]{Transaction.Type.class, String.class, byte[].class},
                    Transaction.Type.IOT_MANAGEMENT, "d1", suspend));
            assertThat(nodeSigned).isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("signed by the device owner");

            TestKeyPair alice = new TestKeyPair(8701);
            f.fund(alice.getAddress());
            long fee = Math.max(1, f.chain.getCurrentBaseFee());
            byte[] transferJson = f.chain.serializeTransaction(LedgerPoCFixture.transfer(alice, "bob", 5, fee, 1));

            Throwable wrongType = thrown(() -> call(adapter, "submitSignedTx",
                    new Class<?>[]{byte[].class, Transaction.Type.class}, transferJson, Transaction.Type.IOT_MANAGEMENT));
            assertThat(wrongType).hasMessageContaining("Expected a IOT_MANAGEMENT");
            assertThat(f.chain.getMempool().size()).isZero();

            Object txid = assertDoesNotThrow(() -> call(adapter, "submitSignedTx",
                    new Class<?>[]{byte[].class, Transaction.Type.class}, transferJson, null));
            assertThat(txid).isNotNull();
            assertThat(f.chain.getMempool().size()).isEqualTo(1);
        }
    }

    private static Object assertDoesNotThrow(ThrowingSupplier s) {
        try { return s.get(); } catch (Throwable t) { throw new AssertionError("unexpected: " + t, t); }
    }

    interface ThrowingSupplier { Object get() throws Throwable; }

    @Test
    @DisplayName("HC-063: unknown topics are refused and filters are per topic")
    void eventBusTopicsAndFilters() throws Exception {
        EventBus bus = new EventBus();
        WebSocketSession s = mock(WebSocketSession.class);
        when(s.isOpen()).thenReturn(true);
        when(s.getId()).thenReturn("s");

        bus.subscribe("attacker-topic-1", s, Map.of());
        assertThat(bus.getSubscriberCount("attacker-topic-1")).isZero();

        bus.subscribe("blocks", s, Map.of("miner", "alice"));
        bus.subscribe("transactions", s, Map.of("from", "bob"));
        // before: the second subscribe replaced the session's only filter, so "blocks" now
        // filtered on from=bob and alice's block was dropped
        bus.publish("blocks", Map.of("miner", "alice"));
        verify(s, times(1)).sendMessage(any(TextMessage.class));
        bus.publish("transactions", Map.of("from", "carol"));
        verify(s, times(1)).sendMessage(any(TextMessage.class));
        bus.publish("transactions", Map.of("from", "bob"));
        verify(s, times(2)).sendMessage(any(TextMessage.class));
    }
}
