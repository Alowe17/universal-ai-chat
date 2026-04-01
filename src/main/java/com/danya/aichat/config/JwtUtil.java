package com.danya.aichat.config;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import javax.crypto.SecretKey;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class JwtUtil {

    private final SecretKey signingKey;
    private final Duration accessTokenLifetime;

    public JwtUtil(
            @Value("${app.security.jwt.secret:change-this-secret-key-to-a-very-long-random-value-1234567890}") String secret,
            @Value("${app.security.jwt.access-expiration:PT15M}") Duration accessTokenLifetime
    ) {
        this.signingKey = buildSigningKey(secret);
        this.accessTokenLifetime = accessTokenLifetime;
    }

    public String generateAccessToken(String username) {
        Instant now = Instant.now();
        Instant expiresAt = now.plus(accessTokenLifetime);

        return Jwts.builder()
                .subject(username)
                .issuedAt(Date.from(now))
                .expiration(Date.from(expiresAt))
                .signWith(signingKey)
                .compact();
    }

    public String extractUsername(String token) {
        return parseClaims(token).getSubject();
    }

    public boolean isTokenExpired(String token) {
        return parseClaims(token).getExpiration().before(new Date());
    }

    public boolean isValid(String token, String username) {
        return username.equals(extractUsername(token)) && !isTokenExpired(token);
    }

    public Duration getAccessTokenLifetime() {
        return accessTokenLifetime;
    }

    private Claims parseClaims(String token) {
        return Jwts.parser()
                .verifyWith(signingKey)
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }

    private SecretKey buildSigningKey(String secret) {
        return Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
    }
}