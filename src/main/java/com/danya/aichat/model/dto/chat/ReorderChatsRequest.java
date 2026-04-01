package com.danya.aichat.model.dto.chat;

import jakarta.validation.constraints.NotEmpty;
import java.util.List;

public record ReorderChatsRequest(
        @NotEmpty(message = "Chat order must not be empty")
        List<Long> chatIds
) {
}
