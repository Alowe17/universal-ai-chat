package com.danya.aichat.model.dto.chat;

import jakarta.validation.constraints.Size;

public record CreateChatRequest(
        @Size(max = 120, message = "Chat title must be shorter than 120 characters")
        String title
) {
}
