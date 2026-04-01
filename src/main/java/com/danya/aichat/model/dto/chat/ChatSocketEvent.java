package com.danya.aichat.model.dto.chat;

import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record ChatSocketEvent(
        String type,
        String message,
        Long chatId,
        Long messageId,
        String delta,
        ChatSummaryResponse chat,
        ChatMessageResponse chatMessage
) {

    public static ChatSocketEvent sessionReady() {
        return new ChatSocketEvent("session.ready", null, null, null, null, null, null);
    }

    public static ChatSocketEvent error(Long chatId, String message) {
        return new ChatSocketEvent("chat.error", message, chatId, null, null, null, null);
    }

    public static ChatSocketEvent chatUpdated(ChatSummaryResponse chat) {
        return new ChatSocketEvent("chat.updated", null, chat.id(), null, null, chat, null);
    }

    public static ChatSocketEvent userMessage(Long chatId, ChatMessageResponse chatMessage) {
        return new ChatSocketEvent("chat.message.user", null, chatId, chatMessage.id(), null, null, chatMessage);
    }

    public static ChatSocketEvent assistantStarted(Long chatId, ChatMessageResponse chatMessage) {
        return new ChatSocketEvent("chat.message.assistant.started", null, chatId, chatMessage.id(), null, null, chatMessage);
    }

    public static ChatSocketEvent assistantDelta(Long chatId, Long messageId, String delta) {
        return new ChatSocketEvent("chat.message.assistant.delta", null, chatId, messageId, delta, null, null);
    }

    public static ChatSocketEvent assistantCompleted(Long chatId, ChatMessageResponse chatMessage) {
        return new ChatSocketEvent("chat.message.assistant.completed", null, chatId, chatMessage.id(), null, null, chatMessage);
    }
}
