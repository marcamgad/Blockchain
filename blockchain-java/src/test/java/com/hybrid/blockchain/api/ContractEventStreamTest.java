package com.hybrid.blockchain.api;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import static org.mockito.Mockito.*;

@Tag("unit")
public class ContractEventStreamTest {

    @Test
    @DisplayName("EventBus: Test WebSocket event subscription filtering")
    public void testEventBusWebSocketSub() throws Exception {
        EventBus bus = new EventBus();
        EventBusWebSocketHandler handler = new EventBusWebSocketHandler(bus);

        WebSocketSession session = mock(WebSocketSession.class);
        when(session.getId()).thenReturn("ws-1");
        when(session.isOpen()).thenReturn(true);

        String subscribePayload = "{\"action\":\"subscribe\",\"topic\":\"contracts\",\"filter\":{\"contractAddress\":\"0x123\"}}";
        handler.handleTextMessage(session, new TextMessage(subscribePayload));

        com.hybrid.blockchain.ContractEvent ev1 = new com.hybrid.blockchain.ContractEvent("0x123", 1L, new byte[]{1}, System.currentTimeMillis());
        com.hybrid.blockchain.ContractEvent ev2 = new com.hybrid.blockchain.ContractEvent("0x444", 2L, new byte[]{2}, System.currentTimeMillis());

        bus.publish("contracts", ev1);
        
        try {
            // Need a tiny delay for asynchronous bus processing
            Thread.sleep(100);
        } catch (Exception e) {}

        verify(session, atLeastOnce()).sendMessage(any(TextMessage.class));

        // clear invocations to check ev2 filtering
        reset(session);
        when(session.isOpen()).thenReturn(true);
        when(session.getId()).thenReturn("ws-1");

        bus.publish("contracts", ev2);

        try {
            Thread.sleep(100);
        } catch (Exception e) {}

        verify(session, never()).sendMessage(any(TextMessage.class));
    }
}
