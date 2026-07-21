package com.prompthub.user.seller.presentation.controller;

import java.util.UUID;

import com.prompthub.exception.response.ErrorResponse;
import com.prompthub.presentation.dto.ApiResult;
import com.prompthub.user.seller.application.dto.SellerInfoResult;
import com.prompthub.user.seller.application.usecase.SellerQueryUseCase;
import com.prompthub.user.seller.presentation.dto.response.SellerProfileResponse;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Seller Lookup", description = "상품 상세용 판매자 단건 조회 (인증 불필요)")
@RestController
@RequestMapping("/api/v2/sellers/product")
@RequiredArgsConstructor
public class SellerLookupController {

    private final SellerQueryUseCase sellerQueryUseCase;

    @Operation(summary = "판매자 단건 조회",
            description = "sellerId(UUID)로 판매자 이름과 프로필 이미지를 조회한다. "
                    + "Client는 GET /products/{productId} 응답의 sellerId를 그대로 전달한다.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "조회 성공",
                    content = @Content(schema = @Schema(implementation = SellerProfileResponse.class))),
            @ApiResponse(responseCode = "400", description = "sellerId 누락 또는 잘못된 UUID 형식 (V001)",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "404", description = "존재하지 않는 sellerId (A001)",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @GetMapping
    public ApiResult<SellerProfileResponse> getSeller(
            @Parameter(description = "조회할 판매자 ID(UUID)") @RequestParam UUID sellerId) {
        SellerInfoResult result = sellerQueryUseCase.findSeller(sellerId.toString());
        return ApiResult.success(SellerProfileResponse.from(result));
    }
}
