package com.danya.aichat.model.dto.auth;

import com.danya.aichat.model.dto.user.UserResponse;

public record AuthResponse(
        String message,
        UserResponse user
) {
}