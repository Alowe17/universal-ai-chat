package com.danya.aichat.model.dto.chat;

import com.danya.aichat.model.entity.Chat;
import java.time.Instant;

public record ChatSummaryResponse(
        Long id,
        String title,
        String preview,
        Long sortOrder,
        Instant createdAt,
        Instant updatedAt,
        long messageCount
) {

    public static ChatSummaryResponse from(Chat chat, String preview, long messageCount) {
        return new ChatSummaryResponse(
                chat.getId(),
                chat.getTitle(),
                preview,
                chat.getSortOrder(),
                chat.getCreatedAt(),
                chat.getUpdatedAt(),
                messageCount
        );
    }
}
