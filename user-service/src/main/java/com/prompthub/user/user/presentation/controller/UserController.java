package com.prompthub.user.user.presentation.controller;

import java.util.UUID;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.prompthub.presentation.dto.ApiResult;
import com.prompthub.user.user.application.usecase.UserUseCase;
import com.prompthub.user.user.presentation.dto.response.UserProfileResponse;

import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/api/v1/users")
@RequiredArgsConstructor
public class UserController {

    private final UserUseCase userUseCase;

    @GetMapping("/me")
    public ApiResult<UserProfileResponse> getMe(
            @RequestHeader("X-User-Id") UUID userId
    ) {
        return ApiResult.success(UserProfileResponse.from(userUseCase.getMyProfile(userId)));
    }
}
