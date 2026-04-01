package com.danya.aichat.model.dto.app;

import com.danya.aichat.model.dto.user.UserResponse;

public record SessionResponse(
        boolean authenticated,
        UserResponse user
) {
}