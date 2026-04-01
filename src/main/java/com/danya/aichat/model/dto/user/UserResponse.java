package com.danya.aichat.model.dto.user;

import com.danya.aichat.model.entity.User;
import com.danya.aichat.model.enums.Role;
import java.time.Instant;

public record UserResponse(
        Long id,
        String username,
        String email,
        Role role,
        Instant createdAt
) {

    public static UserResponse from(User user) {
        return new UserResponse(
                user.getId(),
                user.getUsername(),
                user.getEmail(),
                user.getRole(),
                user.getCreatedAt()
        );
    }
}