package com.danya.aichat.model.dto.chat;

import com.danya.aichat.model.entity.ChatDocument;
import java.time.Instant;

public record ChatDocumentResponse(
        Long id,
        String fileName,
        Integer pageCount,
        Integer textLength,
        Instant createdAt
) {

    public static ChatDocumentResponse from(ChatDocument document) {
        return new ChatDocumentResponse(
                document.getId(),
                document.getFileName(),
                document.getPageCount(),
                document.getTextLength(),
                document.getCreatedAt()
        );
    }
}
