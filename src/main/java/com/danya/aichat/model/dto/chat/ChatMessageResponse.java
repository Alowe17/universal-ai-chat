package com.danya.aichat.model.dto.chat;

import com.danya.aichat.model.entity.ChatMessage;
import com.danya.aichat.model.enums.ChatMessageRole;
import java.time.Instant;

public record ChatMessageResponse(
        Long id,
        ChatMessageRole role,
        String content,
        Instant createdAt
) {

    public static ChatMessageResponse from(ChatMessage chatMessage) {
        return new ChatMessageResponse(
                chatMessage.getId(),
                chatMessage.getRole(),
                chatMessage.getContent(),
                chatMessage.getCreatedAt()
        );
    }
}
