package com.hybrid.blockchain;

import com.hybrid.blockchain.api.EventBus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import static org.mockito.Mockito.*;
import static org.assertj.core.api.Assertions.*;

@Tag("integration")
public class WebSocketTest {

    @Test
    @DisplayName("Invariant: EventBus must broadcast events to all subscribed sessions")
    void testEventBusBroadcasting() throws Exception {
        EventBus eventBus = new EventBus();
        WebSocketSession session1 = mock(WebSocketSession.class);
        WebSocketSession session2 = mock(WebSocketSession.class);
        
        when(session1.isOpen()).thenReturn(true);
        when(session1.getId()).thenReturn("s1");
        when(session2.isOpen()).thenReturn(true);
        when(session2.getId()).thenReturn("s2");
        
        // 1. Subscribe
        eventBus.subscribe("blocks", session1);
        eventBus.subscribe("blocks", session2);
        eventBus.subscribe("mempool", session1);
        
        assertThat(eventBus.getSubscriberCount("blocks")).isEqualTo(2);
        assertThat(eventBus.getSubscriberCount("mempool")).isEqualTo(1);
        
        // 2. Publish to 'blocks'
        String blockData = "New Block #1";
        eventBus.publish("blocks", blockData);
        
        verify(session1, times(1)).sendMessage(any(TextMessage.class));
        verify(session2, times(1)).sendMessage(any(TextMessage.class));
        
        // 3. Publish to 'mempool'
        eventBus.publish("mempool", "New Tx");
        verify(session1, times(2)).sendMessage(any(TextMessage.class));
        verify(session2, times(1)).sendMessage(any(TextMessage.class));
    }

    @Test
    @DisplayName("Security: EventBus must stop sending to closed sessions and prune them")
    void testDeadSessionCleanup() throws Exception {
        EventBus eventBus = new EventBus();
        WebSocketSession deadSession = mock(WebSocketSession.class);
        
        when(deadSession.isOpen()).thenReturn(false);
        when(deadSession.getId()).thenReturn("dead");
        
        eventBus.subscribe("blocks", deadSession);
        assertThat(eventBus.getSubscriberCount("blocks")).isEqualTo(1);
        
        // Publish should trigger cleanup
        eventBus.publish("blocks", "data");
        
        verify(deadSession, never()).sendMessage(any());
        assertThat(eventBus.getSubscriberCount("blocks")).isEqualTo(0);
    }
}
