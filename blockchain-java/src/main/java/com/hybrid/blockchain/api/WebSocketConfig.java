package com.hybrid.blockchain.api;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

@Configuration
@EnableWebSocket
public class WebSocketConfig implements WebSocketConfigurer {

    private final EventBusWebSocketHandler eventBusHandler;

    public WebSocketConfig(EventBusWebSocketHandler eventBusHandler) {
        this.eventBusHandler = eventBusHandler;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(eventBusHandler, "/ws/events")
                .setAllowedOrigins("*");
    }
}
