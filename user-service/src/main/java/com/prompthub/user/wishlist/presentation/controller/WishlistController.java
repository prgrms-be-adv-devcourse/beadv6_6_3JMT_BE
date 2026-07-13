package com.prompthub.user.wishlist.presentation.controller;

import com.prompthub.presentation.dto.ApiResult;
import com.prompthub.presentation.dto.PageResponse;
import com.prompthub.user.wishlist.application.dto.WishlistItemResult;
import com.prompthub.user.wishlist.application.usecase.WishlistUseCase;
import com.prompthub.user.wishlist.presentation.dto.request.AddWishlistRequest;
import com.prompthub.user.wishlist.presentation.dto.response.AddWishlistResponse;
import com.prompthub.user.wishlist.presentation.dto.response.WishlistItemResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@Tag(name = "찜", description = "찜 목록 관리")
@SecurityRequirement(name = "Bearer")
@RestController
@RequestMapping("/api/v2/wishlists")
@RequiredArgsConstructor
public class WishlistController {

    private final WishlistUseCase wishlistUseCase;

    @Operation(summary = "찜 등록", description = "상품을 찜 목록에 추가. 역할: BUYER / SELLER")
    @ApiResponse(responseCode = "201", description = "찜 등록 성공")
    @ApiResponse(responseCode = "409", description = "이미 찜한 상품 (W001)")
    @PostMapping
    public ResponseEntity<ApiResult<AddWishlistResponse>> add(
            @Parameter(hidden = true) @RequestHeader("X-User-Id") UUID userId,
            @Valid @RequestBody AddWishlistRequest request
    ) {
        AddWishlistResponse response = AddWishlistResponse.from(
                wishlistUseCase.add(request.toCommand(userId))
        );
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResult.success(response));
    }

    @Operation(summary = "찜 삭제", description = "찜 목록에서 상품 제거. 역할: BUYER / SELLER")
    @ApiResponse(responseCode = "204", description = "찜 삭제 성공")
    @ApiResponse(responseCode = "404", description = "찜 항목 없음 (W002)")
    @ApiResponse(responseCode = "403", description = "본인 찜 아님 (W003)")
    @DeleteMapping("/{wishlistId}")
    public ResponseEntity<Void> remove(
            @Parameter(hidden = true) @RequestHeader("X-User-Id") UUID userId,
            @PathVariable UUID wishlistId
    ) {
        wishlistUseCase.remove(wishlistId, userId);
        return ResponseEntity.noContent().build();
    }

    @Operation(summary = "찜한 상품 목록 조회", description = "역할: BUYER / SELLER")
    @ApiResponse(responseCode = "200", description = "조회 성공")
    @GetMapping
    public ResponseEntity<PageResponse<WishlistItemResponse>> getWishlists(
            @Parameter(hidden = true) @RequestHeader("X-User-Id") UUID userId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size
    ) {
        List<WishlistItemResult> items = wishlistUseCase.getWishlists(userId, page, size);
        long total = wishlistUseCase.countWishlists(userId);
        List<WishlistItemResponse> responses = items.stream()
                .map(WishlistItemResponse::from)
                .toList();
        boolean hasNext = (long) (page + 1) * size < total;
        return ResponseEntity.ok(PageResponse.success(responses, page, size, total, hasNext));
    }

    @Operation(summary = "찜 여부 확인", description = "상품 상세의 하트 버튼 활성화 여부 판단용. 역할: BUYER / SELLER")
    @ApiResponse(responseCode = "200", description = "확인 성공")
    @GetMapping("/exists")
    public ResponseEntity<ApiResult<Map<String, Boolean>>> exists(
            @Parameter(hidden = true) @RequestHeader("X-User-Id") UUID userId,
            @RequestParam UUID productId
    ) {
        boolean wished = wishlistUseCase.exists(userId, productId);
        return ResponseEntity.ok(ApiResult.success(Map.of("wished", wished)));
    }
}
