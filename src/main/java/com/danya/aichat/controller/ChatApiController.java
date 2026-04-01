package com.danya.aichat.controller;

import com.danya.aichat.model.dto.chat.ChatDetailResponse;
import com.danya.aichat.model.dto.chat.ChatDocumentResponse;
import com.danya.aichat.model.dto.chat.ChatSummaryResponse;
import com.danya.aichat.model.dto.chat.CreateChatRequest;
import com.danya.aichat.model.entity.CustomUserDetails;
import com.danya.aichat.service.ChatService;
import jakarta.validation.Valid;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/chats")
@RequiredArgsConstructor
public class ChatApiController {

    private final ChatService chatService;

    @GetMapping
    public ResponseEntity<List<ChatSummaryResponse>> listChats(
            @AuthenticationPrincipal CustomUserDetails currentUser
    ) {
        return ResponseEntity.ok(chatService.getChatsForUser(currentUser.getUsername()));
    }

    @PostMapping
    public ResponseEntity<ChatDetailResponse> createChat(
            @AuthenticationPrincipal CustomUserDetails currentUser,
            @Valid @RequestBody(required = false) CreateChatRequest request
    ) {
        ChatDetailResponse createdChat = chatService.createChat(
                currentUser.getUsername(),
                request != null ? request.title() : null
        );
        return ResponseEntity.status(HttpStatus.CREATED).body(createdChat);
    }

    @GetMapping("/{chatId}")
    public ResponseEntity<ChatDetailResponse> getChat(
            @AuthenticationPrincipal CustomUserDetails currentUser,
            @PathVariable Long chatId
    ) {
        return ResponseEntity.ok(chatService.getChatForUser(currentUser.getUsername(), chatId));
    }

    @PostMapping(value = "/{chatId}/documents/pdf", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<ChatDocumentResponse> uploadPdf(
            @AuthenticationPrincipal CustomUserDetails currentUser,
            @PathVariable Long chatId,
            @RequestParam("file") MultipartFile file
    ) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(chatService.uploadPdfDocument(currentUser.getUsername(), chatId, file));
    }
}
