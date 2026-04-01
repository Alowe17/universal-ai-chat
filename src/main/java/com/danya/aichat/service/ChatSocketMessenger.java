package com.danya.aichat.service;

import com.danya.aichat.model.dto.chat.ChatSocketEvent;
import com.danya.aichat.websocket.WebSocketSessionRegistry;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

@Slf4j
@Component
@RequiredArgsConstructor
public class ChatSocketMessenger {

    private final ObjectMapper objectMapper;
    private final WebSocketSessionRegistry webSocketSessionRegistry;

    public void sendToUser(String username, ChatSocketEvent event) {
        for (WebSocketSession session : webSocketSessionRegistry.getSessions(username)) {
            sendToSession(session, event);
        }
    }

    public void sendToSession(WebSocketSession session, ChatSocketEvent event) {
        if (session == null || !session.isOpen()) {
            return;
        }

        try {
            session.sendMessage(new TextMessage(objectMapper.writeValueAsString(event)));
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Failed to serialize websocket event", exception);
        } catch (IOException exception) {
            log.warn("Failed to send websocket message to session {}", session.getId(), exception);
            webSocketSessionRegistry.unregister(session.getId());
            try {
                session.close();
            } catch (IOException closeException) {
                log.debug("Failed to close websocket session {}", session.getId(), closeException);
            }
        }
    }
}
