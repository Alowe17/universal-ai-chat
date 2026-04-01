package com.danya.aichat.repository;

import com.danya.aichat.model.entity.ChatMessage;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ChatMessageRepository extends JpaRepository<ChatMessage, Long> {

    List<ChatMessage> findAllByChatIdOrderByCreatedAtAscIdAsc(Long chatId);

    List<ChatMessage> findTop5ByChatIdOrderByCreatedAtDescIdDesc(Long chatId);

    Optional<ChatMessage> findByIdAndChatId(Long id, Long chatId);

    long countByChatId(Long chatId);
}
