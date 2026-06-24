package com.prompthub.user.auth.infrastructure.jwt;

import com.prompthub.user.auth.domain.exception.InvalidRefreshTokenException;
import com.prompthub.user.auth.domain.exception.TokenExpiredException;
import com.prompthub.user.global.config.JwtProperties;
import com.prompthub.user.user.domain.model.UserRole;
import lombok.RequiredArgsConstructor;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.JwtValidationException;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.UUID;

@Component
@RequiredArgsConstructor
public class JwtTokenProvider {

    private final JwtEncoder jwtEncoder;
    private final JwtDecoder jwtDecoder;
    private final JwtProperties jwtProperties;

    public String generateAccessToken(UUID userId, UserRole role) {
        Instant now = Instant.now();

        JwtClaimsSet claims = JwtClaimsSet.builder()
                .subject(userId.toString())
                .issuedAt(now)
                .expiresAt(now.plusSeconds(jwtProperties.getAccessTokenExpireSeconds()))
                .claim("role", role.name())
                .claim("type", "access")
                .build();

        return jwtEncoder.encode(JwtEncoderParameters.from(claims)).getTokenValue();
    }

    public String generateRefreshToken(UUID userId) {
        Instant now = Instant.now();

        JwtClaimsSet claims = JwtClaimsSet.builder()
                .subject(userId.toString())
                .issuedAt(now)
                .expiresAt(now.plusSeconds(jwtProperties.getRefreshTokenExpireSeconds()))
                .claim("type", "refresh")
                .build();

        return jwtEncoder.encode(JwtEncoderParameters.from(claims)).getTokenValue();
    }

    public Instant getAccessTokenExpiresAt() {
        return Instant.now().plusSeconds(jwtProperties.getAccessTokenExpireSeconds());
    }

    public Instant getRefreshTokenExpiresAt() {
        return Instant.now().plusSeconds(jwtProperties.getRefreshTokenExpireSeconds());
    }

    public UUID parseRefreshToken(String token) {
        Jwt jwt;
        try {
            jwt = jwtDecoder.decode(token);
        } catch (JwtValidationException e) {
            boolean isExpired = e.getErrors().stream()
                    .anyMatch(err -> err.getDescription() != null && err.getDescription().contains("expired"));
            if (isExpired) {
                throw new TokenExpiredException();
            }
            throw new InvalidRefreshTokenException();
        } catch (JwtException e) {
            throw new InvalidRefreshTokenException();
        }

        if (!"refresh".equals(jwt.getClaimAsString("type"))) {
            throw new InvalidRefreshTokenException();
        }

        return UUID.fromString(jwt.getSubject());
    }
}
