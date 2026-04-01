package com.danya.aichat.websocket;

import com.danya.aichat.model.dto.chat.ChatSocketEvent;
import com.danya.aichat.model.dto.chat.ChatSocketRequest;
import com.danya.aichat.service.ChatSocketMessenger;
import com.danya.aichat.service.ChatStreamingService;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.security.Principal;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

@Slf4j
@Component
@RequiredArgsConstructor
public class ChatWebSocketHandler extends TextWebSocketHandler {

    private final ObjectMapper objectMapper;
    private final WebSocketSessionRegistry webSocketSessionRegistry;
    private final ChatSocketMessenger chatSocketMessenger;
    private final ChatStreamingService chatStreamingService;

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        String username = resolveUsername(session);
        webSocketSessionRegistry.register(username, session);
        chatSocketMessenger.sendToSession(session, ChatSocketEvent.sessionReady());
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) {
        try {
            ChatSocketRequest request = objectMapper.readValue(message.getPayload(), ChatSocketRequest.class);
            if (request == null || request.type() == null) {
                chatSocketMessenger.sendToSession(session, ChatSocketEvent.error(null, "Некорректный websocket payload"));
                return;
            }

            switch (request.type()) {
                case "chat.send" -> handleChatSend(session, request);
                case "ping" -> chatSocketMessenger.sendToSession(session, ChatSocketEvent.sessionReady());
                default -> chatSocketMessenger.sendToSession(
                        session,
                        ChatSocketEvent.error(request.chatId(), "Неизвестный тип websocket-сообщения")
                );
            }
        } catch (Exception exception) {
            log.warn("Invalid websocket payload for session {}", session.getId(), exception);
            chatSocketMessenger.sendToSession(session, ChatSocketEvent.error(null, "Не удалось обработать сообщение"));
        }
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) throws Exception {
        log.warn("Websocket transport error for session {}", session.getId(), exception);
        webSocketSessionRegistry.unregister(session.getId());
        if (session.isOpen()) {
            session.close(CloseStatus.SERVER_ERROR);
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        webSocketSessionRegistry.unregister(session.getId());
    }

    private void handleChatSend(WebSocketSession session, ChatSocketRequest request) {
        if (request.chatId() == null) {
            chatSocketMessenger.sendToSession(session, ChatSocketEvent.error(null, "Не выбран чат для отправки сообщения"));
            return;
        }

        if (request.content() == null || request.content().isBlank()) {
            chatSocketMessenger.sendToSession(
                    session,
                    ChatSocketEvent.error(request.chatId(), "Сообщение не должно быть пустым")
            );
            return;
        }

        chatStreamingService.handlePrompt(resolveUsername(session), request.chatId(), request.content());
    }

    private String resolveUsername(WebSocketSession session) {
        Principal principal = session.getPrincipal();
        if (principal == null || principal.getName() == null || principal.getName().isBlank()) {
            throw new IllegalStateException("Unauthorized websocket session");
        }
        return principal.getName();
    }
}
