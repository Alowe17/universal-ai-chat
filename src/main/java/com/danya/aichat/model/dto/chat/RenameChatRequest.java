package com.danya.aichat.model.dto.chat;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record RenameChatRequest(
        @NotBlank(message = "Chat title must not be blank")
        @Size(max = 120, message = "Chat title must be shorter than 120 characters")
        String title
) {
}
