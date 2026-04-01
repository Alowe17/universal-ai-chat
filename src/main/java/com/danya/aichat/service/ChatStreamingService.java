package com.danya.aichat.service;

import com.danya.aichat.model.dto.chat.ChatMessageResponse;
import com.danya.aichat.model.dto.chat.ChatSocketEvent;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class ChatStreamingService {

    private final ChatService chatService;
    private final OllamaClient ollamaClient;
    private final ChatSocketMessenger chatSocketMessenger;

    private final Set<Long> activeChatIds = ConcurrentHashMap.newKeySet();

    @Async
    public void handlePrompt(String username, Long chatId, String content) {
        if (!activeChatIds.add(chatId)) {
            chatSocketMessenger.sendToUser(
                    username,
                    ChatSocketEvent.error(chatId, "В этом чате уже генерируется ответ. Дождитесь завершения.")
            );
            return;
        }

        ChatService.PromptContext promptContext = null;
        StringBuilder assistantContent = new StringBuilder();

        try {
            promptContext = chatService.registerUserPrompt(username, chatId, content);

            chatSocketMessenger.sendToUser(
                    username,
                    ChatSocketEvent.userMessage(promptContext.chat().id(), promptContext.userMessage())
            );
            chatSocketMessenger.sendToUser(username, ChatSocketEvent.chatUpdated(promptContext.chat()));

            chatSocketMessenger.sendToUser(
                    username,
                    ChatSocketEvent.assistantStarted(promptContext.chat().id(), promptContext.assistantMessage())
            );

            ChatService.PromptContext finalPromptContext = promptContext;
            ollamaClient.streamChat(promptContext.history(), delta -> {
                if (delta == null || delta.isEmpty()) {
                    return;
                }

                assistantContent.append(delta);
                chatSocketMessenger.sendToUser(
                        username,
                        ChatSocketEvent.assistantDelta(
                                finalPromptContext.chat().id(),
                                finalPromptContext.assistantMessage().id(),
                                delta
                        )
                );
            });

            ChatMessageResponse completedAssistantMessage = chatService.finalizeAssistantMessage(
                    username,
                    promptContext.chat().id(),
                    promptContext.assistantMessage().id(),
                    assistantContent.toString()
            );

            chatSocketMessenger.sendToUser(
                    username,
                    ChatSocketEvent.assistantCompleted(promptContext.chat().id(), completedAssistantMessage)
            );
            chatSocketMessenger.sendToUser(
                    username,
                    ChatSocketEvent.chatUpdated(chatService.getChatSummary(username, promptContext.chat().id()))
            );
        } catch (Exception exception) {
            log.error("Failed to process chat {} for user {}", chatId, username, exception);

            if (promptContext != null) {
                try {
                    String fallbackMessage = buildFallbackMessage(assistantContent);
                    ChatMessageResponse failedAssistantMessage = chatService.finalizeAssistantMessage(
                            username,
                            promptContext.chat().id(),
                            promptContext.assistantMessage().id(),
                            fallbackMessage
                    );

                    chatSocketMessenger.sendToUser(
                            username,
                            ChatSocketEvent.assistantCompleted(promptContext.chat().id(), failedAssistantMessage)
                    );
                    chatSocketMessenger.sendToUser(
                            username,
                            ChatSocketEvent.chatUpdated(chatService.getChatSummary(username, promptContext.chat().id()))
                    );
                } catch (Exception persistenceException) {
                    log.error(
                            "Failed to persist assistant message for chat {} after Ollama response",
                            promptContext.chat().id(),
                            persistenceException
                    );
                }
            }

            chatSocketMessenger.sendToUser(
                    username,
                    ChatSocketEvent.error(
                            chatId,
                            "Не удалось получить ответ от Ollama. Проверьте, что Ollama запущена и модель deepseek-r1:7b доступна."
                    )
            );
        } finally {
            activeChatIds.remove(chatId);
        }
    }

    private String buildFallbackMessage(StringBuilder assistantContent) {
        String baseMessage = "Не удалось получить полный ответ от Ollama.";

        if (assistantContent.isEmpty()) {
            return baseMessage;
        }

        return assistantContent + "\n\n[" + baseMessage + "]";
    }
}
