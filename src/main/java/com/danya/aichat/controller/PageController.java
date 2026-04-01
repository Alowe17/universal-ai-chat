package com.danya.aichat.controller;

import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
public class PageController {

    @GetMapping("/")
    public String home(Authentication authentication) {
        if (!isAuthenticated(authentication)) {
            return "redirect:/login";
        }

        return "index";
    }

    @GetMapping("/login")
    public String login(Authentication authentication) {
        if (isAuthenticated(authentication)) {
            return "redirect:/";
        }

        return "login";
    }

    @GetMapping("/register")
    public String register(Authentication authentication) {
        if (isAuthenticated(authentication)) {
            return "redirect:/";
        }

        return "register";
    }

    private boolean isAuthenticated(Authentication authentication) {
        return authentication != null
                && authentication.isAuthenticated()
                && !(authentication instanceof AnonymousAuthenticationToken);
    }
}
