package com.danya.aichat.repository;

import com.danya.aichat.model.entity.ChatDocument;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ChatDocumentRepository extends JpaRepository<ChatDocument, Long> {

    List<ChatDocument> findAllByChatIdOrderByCreatedAtAsc(Long chatId);

    long deleteByChatId(Long chatId);
}
