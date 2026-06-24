package com.prompthub.user.seller.presentation.controller;

import com.prompthub.presentation.dto.ApiResult;
import com.prompthub.user.seller.application.usecase.SellerUseCase;
import com.prompthub.user.seller.presentation.dto.request.SellerRegisterRequest;
import com.prompthub.user.seller.presentation.dto.response.SellerRegisterResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/seller")
@RequiredArgsConstructor
public class SellerController {

    private final SellerUseCase sellerUseCase;

    @PostMapping("/register")
    public ResponseEntity<ApiResult<SellerRegisterResponse>> register(
            @RequestHeader("X-User-Id") UUID userId,
            @Valid @RequestBody SellerRegisterRequest request
    ) {
        SellerRegisterResponse response = SellerRegisterResponse.from(
                sellerUseCase.register(request.toCommand(userId))
        );
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResult.success(response));
    }
}
