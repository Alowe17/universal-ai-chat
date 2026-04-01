package com.danya.aichat.service;

import com.danya.aichat.model.dto.chat.ChatDetailResponse;
import com.danya.aichat.model.dto.chat.ChatMessageResponse;
import com.danya.aichat.model.dto.chat.ChatSummaryResponse;
import com.danya.aichat.model.entity.Chat;
import com.danya.aichat.model.entity.ChatMessage;
import com.danya.aichat.model.entity.User;
import com.danya.aichat.model.enums.ChatMessageRole;
import com.danya.aichat.repository.ChatMessageRepository;
import com.danya.aichat.repository.ChatRepository;
import com.danya.aichat.repository.UserRepository;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ChatService {

    private static final String DEFAULT_CHAT_TITLE = "Новый чат";
    private static final int MAX_TITLE_LENGTH = 120;
    private static final int MAX_MESSAGE_LENGTH = 12_000;

    private final ChatRepository chatRepository;
    private final ChatMessageRepository chatMessageRepository;
    private final UserRepository userRepository;

    public List<ChatSummaryResponse> getChatsForUser(String username) {
        User user = requireUser(username);
        return chatRepository.findAllByUserIdOrderByUpdatedAtDesc(user.getId())
                .stream()
                .map(this::toSummary)
                .toList();
    }

    @Transactional
    public ChatDetailResponse createChat(String username, String requestedTitle) {
        User user = requireUser(username);

        Chat chat = new Chat();
        chat.setUser(user);
        chat.setTitle(resolveInitialTitle(requestedTitle));

        Chat savedChat = chatRepository.save(chat);
        return ChatDetailResponse.from(savedChat, List.of());
    }

    public ChatDetailResponse getChatForUser(String username, Long chatId) {
        Chat chat = requireOwnedChat(username, chatId);
        List<ChatMessage> messages = chatMessageRepository.findAllByChatIdOrderByCreatedAtAscIdAsc(chatId);
        return ChatDetailResponse.from(chat, messages);
    }

    @Transactional
    public PromptContext registerUserPrompt(String username, Long chatId, String content) {
        Chat chat = requireOwnedChat(username, chatId);
        String normalizedContent = normalizeMessage(content);

        if (DEFAULT_CHAT_TITLE.equals(chat.getTitle())) {
            chat.setTitle(buildTitleFromMessage(normalizedContent));
        }

        ChatMessage userMessage = new ChatMessage();
        userMessage.setChat(chat);
        userMessage.setRole(ChatMessageRole.USER);
        userMessage.setContent(normalizedContent);

        chat.touch();
        ChatMessage savedUserMessage = chatMessageRepository.save(userMessage);
        chatRepository.save(chat);

        ChatMessage assistantMessage = new ChatMessage();
        assistantMessage.setChat(chat);
        assistantMessage.setRole(ChatMessageRole.ASSISTANT);
        assistantMessage.setContent("");

        chat.touch();
        ChatMessage savedAssistantMessage = chatMessageRepository.save(assistantMessage);
        chatRepository.save(chat);

        return new PromptContext(
                toSummary(chat),
                ChatMessageResponse.from(savedUserMessage),
                ChatMessageResponse.from(savedAssistantMessage),
                buildOllamaHistory(chat.getId())
        );
    }

    @Transactional
    public ChatMessageResponse finalizeAssistantMessage(String username, Long chatId, Long messageId, String content) {
        Chat chat = requireOwnedChat(username, chatId);
        ChatMessage assistantMessage = chatMessageRepository.findByIdAndChatId(messageId, chat.getId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Assistant message not found"));

        assistantMessage.setContent(content == null ? "" : content.strip());
        chat.touch();

        ChatMessage savedAssistantMessage = chatMessageRepository.save(assistantMessage);
        chatRepository.save(chat);
        return ChatMessageResponse.from(savedAssistantMessage);
    }

    public ChatSummaryResponse getChatSummary(String username, Long chatId) {
        return toSummary(requireOwnedChat(username, chatId));
    }

    private List<OllamaClient.OllamaMessage> buildOllamaHistory(Long chatId) {
        return chatMessageRepository.findAllByChatIdOrderByCreatedAtAscIdAsc(chatId)
                .stream()
                .filter(message -> message.getContent() != null && !message.getContent().isBlank())
                .map(message -> new OllamaClient.OllamaMessage(
                        message.getRole().getOllamaRole(),
                        message.getContent()
                ))
                .toList();
    }

    private ChatSummaryResponse toSummary(Chat chat) {
        List<ChatMessage> recentMessages = chatMessageRepository.findTop5ByChatIdOrderByCreatedAtDescIdDesc(chat.getId());
        String preview = recentMessages.stream()
                .map(ChatMessage::getContent)
                .filter(content -> content != null && !content.isBlank())
                .findFirst()
                .map(this::buildPreview)
                .orElse("Диалог еще пуст");

        return ChatSummaryResponse.from(chat, preview, chatMessageRepository.countByChatId(chat.getId()));
    }

    private User requireUser(String username) {
        return userRepository.findByUsernameIgnoreCase(username)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "User not found"));
    }

    private Chat requireOwnedChat(String username, Long chatId) {
        User user = requireUser(username);
        return chatRepository.findByIdAndUserId(chatId, user.getId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Chat not found"));
    }

    private String resolveInitialTitle(String requestedTitle) {
        if (requestedTitle == null || requestedTitle.isBlank()) {
            return DEFAULT_CHAT_TITLE;
        }

        return truncate(requestedTitle.strip(), MAX_TITLE_LENGTH);
    }

    private String normalizeMessage(String content) {
        if (content == null || content.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Message content must not be blank");
        }

        String normalized = content.strip();
        if (normalized.length() > MAX_MESSAGE_LENGTH) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Message content is too long. Maximum length is " + MAX_MESSAGE_LENGTH + " characters"
            );
        }

        return normalized;
    }

    private String buildTitleFromMessage(String content) {
        return truncate(content.replaceAll("\\s+", " "), 56);
    }

    private String buildPreview(String content) {
        return truncate(content.replaceAll("\\s+", " "), 96);
    }

    private String truncate(String value, int maxLength) {
        if (value.length() <= maxLength) {
            return value;
        }

        return value.substring(0, maxLength - 1).stripTrailing() + "…";
    }

    public record PromptContext(
            ChatSummaryResponse chat,
            ChatMessageResponse userMessage,
            ChatMessageResponse assistantMessage,
            List<OllamaClient.OllamaMessage> history
    ) {
    }
}
