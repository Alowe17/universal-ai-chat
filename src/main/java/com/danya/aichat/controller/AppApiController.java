package com.danya.aichat.controller;

import com.danya.aichat.model.dto.app.SessionResponse;
import com.danya.aichat.model.dto.user.UserResponse;
import com.danya.aichat.model.entity.CustomUserDetails;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/app")
public class AppApiController {

    @GetMapping("/session")
    public ResponseEntity<SessionResponse> session(@AuthenticationPrincipal CustomUserDetails currentUser) {
        if (currentUser == null) {
            return ResponseEntity.ok(new SessionResponse(false, null));
        }

        return ResponseEntity.ok(new SessionResponse(true, UserResponse.from(currentUser.getUser())));
    }
}