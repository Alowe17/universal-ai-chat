package com.danya.aichat.model.dto.chat;

import com.danya.aichat.model.entity.Chat;
import com.danya.aichat.model.entity.ChatMessage;
import java.time.Instant;
import java.util.List;

public record ChatDetailResponse(
        Long id,
        String title,
        Instant createdAt,
        Instant updatedAt,
        List<ChatMessageResponse> messages,
        List<ChatDocumentResponse> documents
) {

    public static ChatDetailResponse from(
            Chat chat,
            List<ChatMessage> messages,
            List<ChatDocumentResponse> documents
    ) {
        return new ChatDetailResponse(
                chat.getId(),
                chat.getTitle(),
                chat.getCreatedAt(),
                chat.getUpdatedAt(),
                messages.stream().map(ChatMessageResponse::from).toList(),
                documents
        );
    }
}
