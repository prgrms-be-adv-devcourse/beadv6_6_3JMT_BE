package com.prompthub.user.auth.presentation.controller;

import com.prompthub.presentation.dto.ApiResult;
import com.prompthub.user.auth.application.dto.OAuthLoginResult;
import com.prompthub.user.auth.application.dto.TokenRefreshResult;
import com.prompthub.user.auth.application.usecase.OAuthUseCase;
import com.prompthub.user.auth.application.usecase.TokenRefreshUseCase;
import com.prompthub.user.auth.domain.model.OAuthProvider;
import com.prompthub.user.auth.presentation.dto.request.OAuthLoginRequest;
import com.prompthub.user.auth.presentation.dto.request.TokenRefreshRequest;
import com.prompthub.user.auth.presentation.dto.response.OAuthLoginResponse;
import com.prompthub.user.auth.presentation.dto.response.TokenRefreshResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/auth")
@RequiredArgsConstructor
public class AuthController {

    private final OAuthUseCase oAuthUseCase;
    private final TokenRefreshUseCase tokenRefreshUseCase;

    @PostMapping("/oauth/{provider}")
    public ApiResult<OAuthLoginResponse> oAuthLogin(
            @PathVariable String provider,
            @Valid @RequestBody OAuthLoginRequest request
    ) {
        OAuthProvider oAuthProvider = OAuthProvider.fromString(provider);
        OAuthLoginResult result = oAuthUseCase.login(request.toCommand(oAuthProvider));
        return ApiResult.success(OAuthLoginResponse.from(result));
    }

    @PostMapping("/token/refresh")
    public ApiResult<TokenRefreshResponse> refreshToken(
            @Valid @RequestBody TokenRefreshRequest request
    ) {
        TokenRefreshResult result = tokenRefreshUseCase.refresh(request.toCommand());
        return ApiResult.success(TokenRefreshResponse.from(result));
    }
}
