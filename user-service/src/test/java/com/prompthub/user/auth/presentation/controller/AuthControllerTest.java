package com.prompthub.user.auth.presentation.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.prompthub.user.auth.application.dto.OAuthLoginResult;
import com.prompthub.user.auth.application.dto.TokenRefreshResult;
import com.prompthub.user.auth.application.usecase.AuthUseCase;
import com.prompthub.user.auth.domain.exception.InvalidRefreshTokenException;
import com.prompthub.user.auth.domain.exception.OAuthVerificationFailedException;
import com.prompthub.user.auth.domain.exception.RefreshTokenReuseDetectedException;
import com.prompthub.user.auth.domain.exception.TokenExpiredException;
import com.prompthub.user.auth.domain.exception.UnsupportedOAuthProviderException;
import com.prompthub.user.auth.presentation.dto.request.OAuthLoginRequest;
import com.prompthub.user.auth.presentation.dto.request.TokenRefreshRequest;
import com.prompthub.user.global.config.SecurityConfig;
import com.prompthub.user.user.domain.model.UserRole;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willDoNothing;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
class AuthControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private AuthUseCase authUseCase;

    private static final UUID USER_ID = UUID.randomUUID();
    private static final Instant EXPIRES_AT = Instant.now().plusSeconds(3600);

    private OAuthLoginResult successResult(boolean isNewUser) {
        return new OAuthLoginResult(
                USER_ID,
                "테스트유저",
                "test@kakao.com",
                java.util.Set.of(UserRole.BUYER),
                "access-token",
                "refresh-token",
                "Bearer",
                EXPIRES_AT,
                isNewUser
        );
    }

    @Test
    void oAuthLogin_kakao_기존_사용자_200() throws Exception {
        given(authUseCase.oAuthLogin(any())).willReturn(successResult(false));

        OAuthLoginRequest request = new OAuthLoginRequest("kakao-access-token");

        mockMvc.perform(post("/api/v2/auth/oauth/kakao")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.accessToken").value("access-token"))
                .andExpect(jsonPath("$.data.tokenType").value("Bearer"))
                .andExpect(jsonPath("$.data.user.email").value("test@kakao.com"))
                .andExpect(jsonPath("$.data.user.roles[0]").value("BUYER"));
    }

    @Test
    void oAuthLogin_kakao_신규_사용자_isNewUser_true() throws Exception {
        given(authUseCase.oAuthLogin(any())).willReturn(successResult(true));

        OAuthLoginRequest request = new OAuthLoginRequest("kakao-access-token");

        mockMvc.perform(post("/api/v2/auth/oauth/kakao")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.isNewUser").value(true));
    }

    @Test
    void oAuthLogin_accessToken_누락_400() throws Exception {
        String body = "{}";

        mockMvc.perform(post("/api/v2/auth/oauth/kakao")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest());
    }

    @Test
    void oAuthLogin_accessToken_빈_문자열_400() throws Exception {
        OAuthLoginRequest request = new OAuthLoginRequest("");

        mockMvc.perform(post("/api/v2/auth/oauth/kakao")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void oAuthLogin_미지원_provider_400() throws Exception {
        given(authUseCase.oAuthLogin(any())).willThrow(new UnsupportedOAuthProviderException("twitter"));

        OAuthLoginRequest request = new OAuthLoginRequest("twitter-access-token");

        mockMvc.perform(post("/api/v2/auth/oauth/twitter")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void oAuthLogin_카카오_인증_실패_401() throws Exception {
        given(authUseCase.oAuthLogin(any())).willThrow(new OAuthVerificationFailedException("유효하지 않은 액세스 토큰"));

        OAuthLoginRequest request = new OAuthLoginRequest("invalid-access-token");

        mockMvc.perform(post("/api/v2/auth/oauth/kakao")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("A011"));
    }

    @Test
    void oAuthLogin_요청_본문_없음_400() throws Exception {
        mockMvc.perform(post("/api/v2/auth/oauth/kakao")
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest());
    }

    @Test
    void refreshToken_유효한_RT_새_AT_발급_200() throws Exception {
        TokenRefreshResult result = new TokenRefreshResult("new-access-token", EXPIRES_AT, "new-refresh-token");
        given(authUseCase.refresh(any())).willReturn(result);

        TokenRefreshRequest request = new TokenRefreshRequest("valid-refresh-token");

        mockMvc.perform(post("/api/v2/auth/token/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.accessToken").value("new-access-token"));
    }

    @Test
    void refreshToken_만료된_RT_401() throws Exception {
        given(authUseCase.refresh(any())).willThrow(new TokenExpiredException());

        TokenRefreshRequest request = new TokenRefreshRequest("expired-refresh-token");

        mockMvc.perform(post("/api/v2/auth/token/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("A003"));
    }

    @Test
    void refreshToken_유효하지_않은_RT_401() throws Exception {
        given(authUseCase.refresh(any())).willThrow(new InvalidRefreshTokenException());

        TokenRefreshRequest request = new TokenRefreshRequest("invalid-refresh-token");

        mockMvc.perform(post("/api/v2/auth/token/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("A006"));
    }

    @Test
    void refreshToken_RT_누락_400() throws Exception {
        String body = "{}";

        mockMvc.perform(post("/api/v2/auth/token/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest());
    }

    @Test
    void refreshToken_정상_응답에_새_RT_포함() throws Exception {
        TokenRefreshResult result = new TokenRefreshResult("new-access-token", EXPIRES_AT, "new-refresh-token");
        given(authUseCase.refresh(any())).willReturn(result);

        TokenRefreshRequest request = new TokenRefreshRequest("valid-refresh-token");

        mockMvc.perform(post("/api/v2/auth/token/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.refreshToken").value("new-refresh-token"));
    }

    @Test
    void refreshToken_재사용_감지시_401() throws Exception {
        given(authUseCase.refresh(any())).willThrow(new RefreshTokenReuseDetectedException());

        TokenRefreshRequest request = new TokenRefreshRequest("reused-refresh-token");

        mockMvc.perform(post("/api/v2/auth/token/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("A012"));
    }

    @Test
    void logout_정상_200() throws Exception {
        willDoNothing().given(authUseCase).logout(any(UUID.class));

        mockMvc.perform(post("/api/v2/auth/logout")
                        .header("X-User-Id", USER_ID.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data").isEmpty());
    }

    @Test
    void logout_XUserId_헤더_누락_403() throws Exception {
        mockMvc.perform(post("/api/v2/auth/logout"))
                .andExpect(status().isForbidden());
    }
}
