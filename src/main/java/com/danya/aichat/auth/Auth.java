package com.danya.aichat.auth;

import com.danya.aichat.config.JwtUtil;
import com.danya.aichat.model.dto.auth.AuthResponse;
import com.danya.aichat.model.dto.login.LoginRequest;
import com.danya.aichat.model.dto.register.RegisterRequest;
import com.danya.aichat.model.dto.user.UserResponse;
import com.danya.aichat.model.entity.CustomUserDetails;
import com.danya.aichat.model.entity.RefreshToken;
import com.danya.aichat.model.entity.User;
import com.danya.aichat.model.enums.Role;
import com.danya.aichat.repository.UserRepository;
import com.danya.aichat.service.RefreshTokenService;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Slf4j
@RestController
@RequestMapping("/api/auth")
public class Auth {

    private static final String ACCESS_COOKIE_NAME = "accessToken";
    private static final String REFRESH_COOKIE_NAME = "refreshToken";

    private final JwtUtil jwtUtil;
    private final RefreshTokenService refreshTokenService;
    private final AuthenticationManager authenticationManager;
    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    public Auth(
            JwtUtil jwtUtil,
            RefreshTokenService refreshTokenService,
            AuthenticationManager authenticationManager,
            UserRepository userRepository,
            PasswordEncoder passwordEncoder
    ) {
        this.jwtUtil = jwtUtil;
        this.refreshTokenService = refreshTokenService;
        this.authenticationManager = authenticationManager;
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
    }

    @PostMapping("/register")
    public ResponseEntity<?> register(@Valid @RequestBody RegisterRequest registerRequest, HttpServletResponse response) {
        String username = registerRequest.getUsername().trim();
        String email = registerRequest.getEmail().trim().toLowerCase();

        if (userRepository.existsByUsernameIgnoreCase(username)) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body("Username is already taken");
        }

        if (userRepository.existsByEmailIgnoreCase(email)) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body("Email is already registered");
        }

        User user = new User();
        user.setUsername(username);
        user.setEmail(email);
        user.setPassword(passwordEncoder.encode(registerRequest.getPassword()));
        user.setRole(Role.USER);

        User savedUser = userRepository.save(user);
        issueAuthCookies(response, savedUser);

        return ResponseEntity.status(HttpStatus.CREATED)
                .body(new AuthResponse("Registration successful", UserResponse.from(savedUser)));
    }

    @PostMapping("/login")
    public ResponseEntity<?> login(@Valid @RequestBody LoginRequest loginRequest, HttpServletResponse response) {
        try {
            Authentication authentication = authenticationManager.authenticate(
                    new UsernamePasswordAuthenticationToken(loginRequest.getLogin(), loginRequest.getPassword())
            );

            CustomUserDetails principal = (CustomUserDetails) authentication.getPrincipal();
            User user = principal.getUser();
            issueAuthCookies(response, user);

            return ResponseEntity.ok(new AuthResponse("Login successful", UserResponse.from(user)));
        } catch (BadCredentialsException exception) {
            log.warn("Login failed for {}", loginRequest.getLogin());
            clearAuthCookies(response);
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body("Invalid username/email or password");
        }
    }

    @PostMapping("/refresh")
    public ResponseEntity<?> refresh(HttpServletRequest request, HttpServletResponse response) {
        String refreshTokenValue = extractCookieValue(request, REFRESH_COOKIE_NAME);

        if (refreshTokenValue == null || refreshTokenValue.isBlank()) {
            clearAuthCookies(response);
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body("Refresh token is missing");
        }

        try {
            RefreshToken currentRefreshToken = refreshTokenService.validate(refreshTokenValue);
            RefreshToken rotatedRefreshToken = refreshTokenService.rotate(currentRefreshToken);
            User user = rotatedRefreshToken.getUser();

            writeAccessCookie(response, jwtUtil.generateAccessToken(user.getUsername()));
            writeRefreshCookie(response, rotatedRefreshToken.getToken());

            return ResponseEntity.ok(new AuthResponse("Token refreshed", UserResponse.from(user)));
        } catch (IllegalArgumentException exception) {
            log.warn("Refresh token rejected: {}", exception.getMessage());
            clearAuthCookies(response);
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body("Refresh token is invalid or expired");
        }
    }

    @GetMapping("/me")
    public ResponseEntity<UserResponse> me(@AuthenticationPrincipal CustomUserDetails currentUser) {
        return ResponseEntity.ok(UserResponse.from(currentUser.getUser()));
    }

    @PostMapping("/logout")
    public ResponseEntity<Void> logout(HttpServletRequest request, HttpServletResponse response) {
        String refreshTokenValue = extractCookieValue(request, REFRESH_COOKIE_NAME);

        if (refreshTokenValue != null && !refreshTokenValue.isBlank()) {
            refreshTokenService.revokeByToken(refreshTokenValue);
        }

        clearAuthCookies(response);
        return ResponseEntity.noContent().build();
    }

    private void writeAccessCookie(HttpServletResponse response, String token) {
        ResponseCookie accessCookie = ResponseCookie.from(ACCESS_COOKIE_NAME, token)
                .httpOnly(true)
                .secure(false)
                .path("/")
                .maxAge(jwtUtil.getAccessTokenLifetime())
                .sameSite("Lax")
                .build();

        response.addHeader(HttpHeaders.SET_COOKIE, accessCookie.toString());
    }

    private void writeRefreshCookie(HttpServletResponse response, String token) {
        ResponseCookie refreshCookie = ResponseCookie.from(REFRESH_COOKIE_NAME, token)
                .httpOnly(true)
                .secure(false)
                .path("/")
                .maxAge(refreshTokenService.getRefreshTokenLifetime())
                .sameSite("Lax")
                .build();

        response.addHeader(HttpHeaders.SET_COOKIE, refreshCookie.toString());
    }

    private void issueAuthCookies(HttpServletResponse response, User user) {
        RefreshToken refreshToken = refreshTokenService.create(user);
        writeAccessCookie(response, jwtUtil.generateAccessToken(user.getUsername()));
        writeRefreshCookie(response, refreshToken.getToken());
    }

    private void clearAuthCookies(HttpServletResponse response) {
        response.addHeader(HttpHeaders.SET_COOKIE, expiredCookie(ACCESS_COOKIE_NAME).toString());
        response.addHeader(HttpHeaders.SET_COOKIE, expiredCookie(REFRESH_COOKIE_NAME).toString());
    }

    private ResponseCookie expiredCookie(String name) {
        return ResponseCookie.from(name, "")
                .httpOnly(true)
                .secure(false)
                .path("/")
                .maxAge(0)
                .sameSite("Lax")
                .build();
    }

    private String extractCookieValue(HttpServletRequest request, String cookieName) {
        if (request.getCookies() == null) {
            return null;
        }

        for (Cookie cookie : request.getCookies()) {
            if (cookieName.equals(cookie.getName())) {
                return cookie.getValue();
            }
        }

        return null;
    }
}
