package com.danya.aichat.websocket;

import java.security.Principal;

public record ChatPrincipal(String username) implements Principal {

    @Override
    public String getName() {
        return username;
    }
}
