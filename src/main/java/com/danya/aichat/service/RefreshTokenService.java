package com.danya.aichat.service;

import com.danya.aichat.model.entity.RefreshToken;
import com.danya.aichat.model.entity.User;
import com.danya.aichat.repository.RefreshTokenRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class RefreshTokenService {

    private final RefreshTokenRepository refreshTokenRepository;

    @Value("${app.security.jwt.refresh-expiration:PT30D}")
    private String refreshTokenLifetimeValue;

    @Transactional
    public RefreshToken create(User user) {
        revokeAllActiveTokens(user);

        RefreshToken refreshToken = new RefreshToken();
        refreshToken.setToken(UUID.randomUUID().toString());
        refreshToken.setUser(user);
        refreshToken.setExpiresAt(Instant.now().plus(getRefreshTokenLifetime()));
        refreshToken.setRevoked(false);
        return refreshTokenRepository.save(refreshToken);
    }

    public RefreshToken validate(String token) {
        RefreshToken refreshToken = refreshTokenRepository.findByToken(token).orElseThrow(() -> new IllegalArgumentException("Refresh token not found"));

        if (refreshToken.isRevoked()) {
            throw new IllegalArgumentException("Refresh token has been revoked");
        }

        if (refreshToken.getExpiresAt().isBefore(Instant.now())) {
            throw new IllegalArgumentException("Refresh token has expired");
        }

        return refreshToken;
    }

    @Transactional
    public RefreshToken rotate(RefreshToken refreshToken) {
        revoke(refreshToken);
        return create(refreshToken.getUser());
    }

    @Transactional
    public void revoke(RefreshToken refreshToken) {
        if (refreshToken.isRevoked()) {
            return;
        }

        refreshToken.setRevoked(true);
        refreshTokenRepository.save(refreshToken);
    }

    @Transactional
    public void revokeByToken(String token) {
        refreshTokenRepository.findByToken(token).ifPresent(this::revoke);
    }

    public Duration getRefreshTokenLifetime() {
        return Duration.parse(refreshTokenLifetimeValue);
    }

    @Transactional
    protected void revokeAllActiveTokens(User user) {
        List<RefreshToken> activeTokens = refreshTokenRepository.findAllByUserAndRevokedFalse(user);
        for (RefreshToken activeToken : activeTokens) {
            activeToken.setRevoked(true);
        }

        refreshTokenRepository.saveAll(activeTokens);
    }
}