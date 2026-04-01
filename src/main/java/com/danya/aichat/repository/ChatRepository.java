package com.danya.aichat.repository;

import com.danya.aichat.model.entity.Chat;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ChatRepository extends JpaRepository<Chat, Long> {

    List<Chat> findAllByUserIdOrderBySortOrderDescUpdatedAtDesc(Long userId);

    Optional<Chat> findByIdAndUserId(Long id, Long userId);

    Optional<Chat> findTopByUserIdOrderBySortOrderDesc(Long userId);
}
