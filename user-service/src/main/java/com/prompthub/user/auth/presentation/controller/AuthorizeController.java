package com.prompthub.user.auth.presentation.controller;

import com.prompthub.user.auth.application.dto.AuthorizeResult;
import com.prompthub.user.auth.application.usecase.AuthorizeUseCase;
import com.prompthub.user.auth.presentation.dto.response.AuthorizeResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/internal/authorize")
@RequiredArgsConstructor
public class AuthorizeController {

    private final AuthorizeUseCase authorizeUseCase;

    @GetMapping("/{userId}")
    public AuthorizeResponse authorize(@PathVariable UUID userId) {
        AuthorizeResult result = authorizeUseCase.authorize(userId);
        return AuthorizeResponse.from(result);
    }
}
