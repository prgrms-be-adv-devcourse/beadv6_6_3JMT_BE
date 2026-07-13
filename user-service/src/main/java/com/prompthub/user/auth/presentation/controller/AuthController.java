package com.prompthub.user.auth.presentation.controller;

import com.prompthub.presentation.dto.ApiResult;
import com.prompthub.user.auth.application.dto.OAuthLoginResult;
import com.prompthub.user.auth.application.dto.TokenRefreshResult;
import com.prompthub.user.auth.application.usecase.AuthUseCase;
import com.prompthub.user.auth.domain.model.OAuthProvider;
import com.prompthub.user.auth.presentation.dto.request.OAuthLoginRequest;
import com.prompthub.user.auth.presentation.dto.request.TokenRefreshRequest;
import com.prompthub.user.auth.presentation.dto.response.OAuthLoginResponse;
import com.prompthub.user.auth.presentation.dto.response.TokenRefreshResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@Tag(name = "인증", description = "OAuth 소셜 로그인, 토큰 재발급, 로그아웃")
@RestController
@RequestMapping("/api/v2/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthUseCase authUseCase;

    @Operation(summary = "OAuth 소셜 로그인", description = "최초 로그인 시 자동 회원가입 처리. 현재 지원 provider: kakao")
    @ApiResponse(responseCode = "200", description = "로그인 성공")
    @ApiResponse(responseCode = "400", description = "지원하지 않는 OAuth 공급자 (A009)")
    @ApiResponse(responseCode = "401", description = "카카오 인증에 실패함 (A011)")
    @PostMapping("/oauth/{provider}")
    public ApiResult<OAuthLoginResponse> oAuthLogin(
            @Parameter(description = "OAuth 제공자", example = "kakao") @PathVariable String provider,
            @Valid @RequestBody OAuthLoginRequest request
    ) {
        OAuthProvider oAuthProvider = OAuthProvider.fromString(provider);
        OAuthLoginResult result = authUseCase.oAuthLogin(request.toCommand(oAuthProvider));
        return ApiResult.success(OAuthLoginResponse.from(result));
    }

    @Operation(summary = "토큰 재발급", description = "Refresh Token으로 새 Access Token을 발급")
    @ApiResponse(responseCode = "200", description = "재발급 성공")
    @ApiResponse(responseCode = "401", description = "리프레시 토큰이 유효하지 않거나 만료됨 (A003, A006)")
    @PostMapping("/token/refresh")
    public ApiResult<TokenRefreshResponse> refreshToken(
            @Valid @RequestBody TokenRefreshRequest request
    ) {
        TokenRefreshResult result = authUseCase.refresh(request.toCommand());
        return ApiResult.success(TokenRefreshResponse.from(result));
    }

    @Operation(summary = "로그아웃", description = "Refresh Token 삭제. Access Token은 만료 전까지 유효")
    @ApiResponse(responseCode = "200", description = "로그아웃 성공")
    @SecurityRequirement(name = "Bearer")
    @PostMapping("/logout")
    public ApiResult<Void> logout(
            @Parameter(hidden = true) @RequestHeader("X-User-Id") UUID userId
    ) {
        authUseCase.logout(userId);
        return ApiResult.success(null);
    }
}
