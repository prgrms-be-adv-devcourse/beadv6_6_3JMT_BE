package com.prompthub.user.seller.presentation.controller;

import java.util.List;

import com.prompthub.exception.response.ErrorResponse;
import com.prompthub.presentation.dto.ApiResult;
import com.prompthub.user.seller.application.dto.SellerInfoResult;
import com.prompthub.user.seller.application.usecase.SellerQueryUseCase;
import com.prompthub.user.seller.presentation.dto.request.SellerBatchRequest;
import com.prompthub.user.seller.presentation.dto.response.SellerBatchResponse;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Seller Batch", description = "판매자 이름 배치 조회 (인증 불필요)")
@RestController
@RequestMapping("/api/v2/sellers/products")
@RequiredArgsConstructor
public class SellerBatchController {

    private final SellerQueryUseCase sellerQueryUseCase;

    @Operation(summary = "판매자 이름 배치 조회",
            description = "sellerId(UUID) 목록으로 판매자 이름을 조회한다. 중복은 서버가 제거하며,"
                    + " 존재하지 않는 sellerId는 실패 처리하지 않고 sellerName: null로 포함한다.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "조회 성공",
                    content = @Content(schema = @Schema(implementation = SellerBatchResponse.class))),
            @ApiResponse(responseCode = "400", description = "빈 배열, 30개 초과, 잘못된 UUID 형식 (V001)",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @PostMapping
    public ApiResult<SellerBatchResponse> batch(@Valid @RequestBody SellerBatchRequest request) {
        List<SellerInfoResult> results = sellerQueryUseCase.findSellers(request.sellerIdStrings());
        return ApiResult.success(SellerBatchResponse.of(request.sellerIds(), results));
    }
}
