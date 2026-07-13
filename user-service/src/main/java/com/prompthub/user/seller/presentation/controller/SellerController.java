package com.prompthub.user.seller.presentation.controller;

import com.prompthub.exception.response.ErrorResponse;
import com.prompthub.presentation.dto.ApiResult;
import com.prompthub.user.seller.application.usecase.SellerUseCase;
import com.prompthub.user.seller.presentation.dto.request.SellerRegisterRequest;
import com.prompthub.user.seller.presentation.dto.response.SellerRegisterResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@Tag(name = "Seller", description = "판매자 등록 신청")
@SecurityRequirement(name = "Bearer")
@RestController
@RequestMapping("/api/v2/seller")
@RequiredArgsConstructor
public class SellerController {

    private final SellerUseCase sellerUseCase;

    @Operation(summary = "판매자 등록 신청", description = "신청 시 상태는 PENDING. 역할: BUYER")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "신청 성공",
                    content = @Content(schema = @Schema(implementation = SellerRegisterResponse.class))),
            @ApiResponse(responseCode = "409", description = "이미 신청된 판매자 (A005)",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @ResponseStatus(HttpStatus.CREATED)
    @PostMapping("/register")
    public ApiResult<SellerRegisterResponse> register(
            @Parameter(hidden = true) @RequestHeader("X-User-Id") UUID userId,
            @Valid @RequestBody SellerRegisterRequest request
    ) {
        SellerRegisterResponse response = SellerRegisterResponse.from(
                sellerUseCase.register(request.toCommand(userId))
        );
        return ApiResult.success(response);
    }
}
