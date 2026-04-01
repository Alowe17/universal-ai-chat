package com.danya.aichat.model.enums;

public enum ChatMessageRole {
    USER("user"),
    ASSISTANT("assistant"),
    SYSTEM("system");

    private final String ollamaRole;

    ChatMessageRole(String ollamaRole) {
        this.ollamaRole = ollamaRole;
    }

    public String getOllamaRole() {
        return ollamaRole;
    }
}
