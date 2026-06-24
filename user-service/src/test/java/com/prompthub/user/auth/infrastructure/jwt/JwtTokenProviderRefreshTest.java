package com.prompthub.user.auth.infrastructure.jwt;

import com.prompthub.user.auth.domain.exception.InvalidRefreshTokenException;
import com.prompthub.user.auth.domain.exception.TokenExpiredException;
import com.prompthub.user.global.config.JwtProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtValidationException;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;

@ExtendWith(MockitoExtension.class)
class JwtTokenProviderRefreshTest {

    @Mock
    private JwtEncoder jwtEncoder;

    @Mock
    private JwtDecoder jwtDecoder;

    @Mock
    private JwtProperties jwtProperties;

    @InjectMocks
    private JwtTokenProvider jwtTokenProvider;

    private static final UUID USER_ID = UUID.randomUUID();

    private Jwt refreshJwt(UUID userId) {
        return Jwt.withTokenValue("token")
                .header("alg", "RS256")
                .subject(userId.toString())
                .claim("type", "refresh")
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(2592000))
                .build();
    }

    @Test
    void parseRefreshToken_유효한_RT_userId_반환() {
        given(jwtDecoder.decode(any())).willReturn(refreshJwt(USER_ID));

        UUID result = jwtTokenProvider.parseRefreshToken("valid-token");

        assertThat(result).isEqualTo(USER_ID);
    }

    @Test
    void parseRefreshToken_만료된_RT_TokenExpiredException() {
        OAuth2Error error = new OAuth2Error("invalid_token", "Jwt expired at 2026-01-01T00:00:00Z", null);
        JwtValidationException ex = new JwtValidationException("Jwt expired", List.of(error));
        given(jwtDecoder.decode(any())).willThrow(ex);

        assertThatThrownBy(() -> jwtTokenProvider.parseRefreshToken("expired-token"))
                .isInstanceOf(TokenExpiredException.class);
    }

    @Test
    void parseRefreshToken_유효하지_않은_클레임_InvalidRefreshTokenException() {
        OAuth2Error error = new OAuth2Error("invalid_token", "JWT claim validation failed", null);
        JwtValidationException ex = new JwtValidationException("invalid claim", List.of(error));
        given(jwtDecoder.decode(any())).willThrow(ex);

        assertThatThrownBy(() -> jwtTokenProvider.parseRefreshToken("invalid-claim-token"))
                .isInstanceOf(InvalidRefreshTokenException.class);
    }

    @Test
    void parseRefreshToken_서명_불일치_InvalidRefreshTokenException() {
        given(jwtDecoder.decode(any())).willThrow(new BadJwtException("bad signature"));

        assertThatThrownBy(() -> jwtTokenProvider.parseRefreshToken("tampered-token"))
                .isInstanceOf(InvalidRefreshTokenException.class);
    }

    @Test
    void parseRefreshToken_type이_access이면_InvalidRefreshTokenException() {
        Jwt accessJwt = Jwt.withTokenValue("token")
                .header("alg", "RS256")
                .subject(USER_ID.toString())
                .claim("type", "access")
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(3600))
                .build();
        given(jwtDecoder.decode(any())).willReturn(accessJwt);

        assertThatThrownBy(() -> jwtTokenProvider.parseRefreshToken("access-token"))
                .isInstanceOf(InvalidRefreshTokenException.class);
    }

    @Test
    void parseRefreshToken_type_클레임_없으면_InvalidRefreshTokenException() {
        Jwt noTypeJwt = Jwt.withTokenValue("token")
                .header("alg", "RS256")
                .subject(USER_ID.toString())
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(3600))
                .build();
        given(jwtDecoder.decode(any())).willReturn(noTypeJwt);

        assertThatThrownBy(() -> jwtTokenProvider.parseRefreshToken("no-type-token"))
                .isInstanceOf(InvalidRefreshTokenException.class);
    }
}
