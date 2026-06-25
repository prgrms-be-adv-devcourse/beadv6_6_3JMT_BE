package com.prompthub.user.seller.presentation.controller;

import com.prompthub.presentation.dto.ApiResult;
import com.prompthub.user.seller.application.usecase.SellerUseCase;
import com.prompthub.user.seller.presentation.dto.request.SellerRegisterRequest;
import com.prompthub.user.seller.presentation.dto.response.SellerRegisterResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
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

@Tag(name = "판매자", description = "판매자 등록 신청")
@SecurityRequirement(name = "Bearer")
@RestController
@RequestMapping("/api/v1/seller")
@RequiredArgsConstructor
public class SellerController {

    private final SellerUseCase sellerUseCase;

    @Operation(summary = "판매자 등록 신청", description = "신청 시 상태는 PENDING. 역할: BUYER")
    @ApiResponse(responseCode = "201", description = "신청 성공")
    @ApiResponse(responseCode = "409", description = "이미 신청된 판매자 (A005)")
    @PostMapping("/register")
    public ResponseEntity<ApiResult<SellerRegisterResponse>> register(
            @Parameter(hidden = true) @RequestHeader("X-User-Id") UUID userId,
            @Valid @RequestBody SellerRegisterRequest request
    ) {
        SellerRegisterResponse response = SellerRegisterResponse.from(
                sellerUseCase.register(request.toCommand(userId))
        );
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResult.success(response));
    }
}
