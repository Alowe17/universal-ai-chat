package com.danya.aichat.websocket;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.ConcurrentWebSocketSessionDecorator;

@Component
public class WebSocketSessionRegistry {

    private final Map<String, String> usernamesBySessionId = new ConcurrentHashMap<>();
    private final Map<String, Map<String, WebSocketSession>> sessionsByUsername = new ConcurrentHashMap<>();

    public void register(String username, WebSocketSession session) {
        WebSocketSession safeSession = new ConcurrentWebSocketSessionDecorator(session, 10_000, 512 * 1024);

        usernamesBySessionId.put(session.getId(), username);
        sessionsByUsername.computeIfAbsent(username, ignored -> new ConcurrentHashMap<>())
                .put(session.getId(), safeSession);
    }

    public void unregister(String sessionId) {
        String username = usernamesBySessionId.remove(sessionId);
        if (username == null) {
            return;
        }

        Map<String, WebSocketSession> sessions = sessionsByUsername.get(username);
        if (sessions == null) {
            return;
        }

        sessions.remove(sessionId);
        if (sessions.isEmpty()) {
            sessionsByUsername.remove(username);
        }
    }

    public Collection<WebSocketSession> getSessions(String username) {
        Map<String, WebSocketSession> sessions = sessionsByUsername.get(username);
        if (sessions == null || sessions.isEmpty()) {
            return java.util.List.of();
        }

        return new ArrayList<>(sessions.values());
    }
}
