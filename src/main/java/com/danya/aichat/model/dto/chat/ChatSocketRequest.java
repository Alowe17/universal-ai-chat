package com.danya.aichat.model.dto.chat;

public record ChatSocketRequest(
        String type,
        Long chatId,
        String content
) {
}
