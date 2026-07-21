package com.prompthub.user.seller.presentation.controller;

import java.util.List;
import java.util.UUID;

import com.prompthub.exception.response.ErrorResponse;
import com.prompthub.presentation.dto.ApiResult;
import com.prompthub.user.seller.application.dto.SellerInfoResult;
import com.prompthub.user.seller.application.usecase.SellerQueryUseCase;
import com.prompthub.user.seller.application.usecase.SellerUseCase;
import com.prompthub.user.seller.presentation.dto.request.SellerIdsRequest;
import com.prompthub.user.seller.presentation.dto.request.SellerRegisterRequest;
import com.prompthub.user.seller.presentation.dto.response.SellerNamesResponse;
import com.prompthub.user.seller.presentation.dto.response.SellerProfileResponse;
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
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Seller", description = "판매자 등록·조회")
@RestController
@RequiredArgsConstructor
public class SellerController {

    private final SellerUseCase sellerUseCase;
    private final SellerQueryUseCase sellerQueryUseCase;

    @Operation(summary = "판매자 등록 신청", description = "신청 시 상태는 PENDING. 역할: BUYER")
    @SecurityRequirement(name = "Bearer")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "신청 성공",
                    content = @Content(schema = @Schema(implementation = SellerRegisterResponse.class))),
            @ApiResponse(responseCode = "409", description = "이미 신청된 판매자 (A005)",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @ResponseStatus(HttpStatus.CREATED)
    @PostMapping("/api/v2/seller/register")
    public ApiResult<SellerRegisterResponse> register(
            @Parameter(hidden = true) @RequestHeader("X-User-Id") UUID userId,
            @Valid @RequestBody SellerRegisterRequest request
    ) {
        SellerRegisterResponse response = SellerRegisterResponse.from(
                sellerUseCase.register(request.toCommand(userId))
        );
        return ApiResult.success(response);
    }

    @Operation(summary = "판매자 단건 조회",
            description = "sellerId(UUID)로 판매자 이름과 프로필 이미지를 조회한다(인증 불필요). "
                    + "Client는 GET /products/{productId} 응답의 sellerId를 그대로 전달한다.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "조회 성공",
                    content = @Content(schema = @Schema(implementation = SellerProfileResponse.class))),
            @ApiResponse(responseCode = "400", description = "sellerId 누락 또는 잘못된 UUID 형식 (V001)",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "404", description = "존재하지 않는 sellerId (A001)",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @GetMapping("/api/v2/sellers/product")
    public ApiResult<SellerProfileResponse> getSeller(
            @Parameter(description = "조회할 판매자 ID(UUID)") @RequestParam UUID sellerId) {
        SellerInfoResult result = sellerQueryUseCase.findSeller(sellerId.toString());
        return ApiResult.success(SellerProfileResponse.from(result));
    }

    @Operation(summary = "판매자 이름 다건 조회",
            description = "sellerId(UUID) 목록으로 판매자 이름을 조회한다(인증 불필요). 중복은 서버가 제거하며,"
                    + " 존재하지 않는 sellerId는 실패 처리하지 않고 sellerName: null로 포함한다.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "조회 성공",
                    content = @Content(schema = @Schema(implementation = SellerNamesResponse.class))),
            @ApiResponse(responseCode = "400", description = "빈 배열, 30개 초과, 잘못된 UUID 형식 (V001)",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @PostMapping("/api/v2/sellers/products")
    public ApiResult<SellerNamesResponse> getSellers(@Valid @RequestBody SellerIdsRequest request) {
        List<SellerInfoResult> results = sellerQueryUseCase.findSellers(request.sellerIdStrings());
        return ApiResult.success(SellerNamesResponse.of(request.sellerIds(), results));
    }
}
