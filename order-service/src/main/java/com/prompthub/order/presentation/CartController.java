package com.prompthub.order.presentation;

import com.prompthub.order.application.usecase.CartUseCase;
import com.prompthub.order.global.web.AuthHeaders;
import com.prompthub.order.presentation.dto.request.AddCartProductRequest;
import com.prompthub.order.presentation.dto.response.AddCartProductResponse;
import com.prompthub.order.presentation.dto.response.CartResponse;
import com.prompthub.exception.response.ErrorResponse;
import com.prompthub.presentation.dto.ApiResult;
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
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/api/v2/cart")
@RequiredArgsConstructor
@Tag(name = "Cart", description = "장바구니 조회, 상품 추가, 상품 삭제 API")
@SecurityRequirement(name = "Bearer")
public class CartController {

	private final CartUseCase cartUseCase;

	@GetMapping
	@Operation(summary = "장바구니 조회", description = "인증된 구매자의 장바구니 상품 목록과 합계 금액을 조회합니다. 장바구니가 없으면 cartId가 null이고 상품이 비어 있는 200 응답을 반환합니다.")
	@ApiResponses({
		@ApiResponse(responseCode = "200", description = "장바구니 조회 성공 또는 빈 장바구니 응답"),
		@ApiResponse(responseCode = "400", description = "P002 상품 요청 오류",
			content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class))),
		@ApiResponse(responseCode = "401", description = "A003 토큰 만료 또는 유효하지 않음, P004 상품 서비스 인증 실패",
			content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class))),
		@ApiResponse(responseCode = "403", description = "P005 상품 서비스 접근 거부",
			content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class))),
		@ApiResponse(responseCode = "404", description = "P001 상품 없음",
			content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class))),
		@ApiResponse(responseCode = "503", description = "SYS002 상품 서비스 사용 불가",
			content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class)))
	})
	public ApiResult<CartResponse> getCart(
		@Parameter(hidden = true)
		@RequestHeader(AuthHeaders.USER_ID) UUID buyerId
	) {
		return ApiResult.success(cartUseCase.getCart(buyerId));
	}

	@PostMapping
	@Operation(summary = "장바구니 상품 추가", description = "인증된 구매자의 장바구니에 상품을 추가합니다.")
	@ApiResponses({
		@ApiResponse(responseCode = "200", description = "장바구니 상품 추가 성공"),
		@ApiResponse(responseCode = "400", description = "V001 입력값 검증 실패, O003 판매 중이 아닌 상품, P002 상품 요청 오류",
			content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class))),
		@ApiResponse(responseCode = "401", description = "A003 토큰 만료 또는 유효하지 않음, P004 상품 서비스 인증 실패",
			content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class))),
		@ApiResponse(responseCode = "404", description = "P001 상품 없음",
			content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class))),
		@ApiResponse(responseCode = "403", description = "P005 상품 서비스 접근 거부",
			content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class))),
		@ApiResponse(responseCode = "409", description = "C001 이미 장바구니에 담긴 상품, P003 상품 요청 충돌",
			content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class))),
		@ApiResponse(responseCode = "503", description = "SYS002 상품 서비스 사용 불가",
			content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class)))
	})
	public ApiResult<AddCartProductResponse> addCartProduct(
		@Parameter(hidden = true)
		@RequestHeader(AuthHeaders.USER_ID) UUID buyerId,
		@Valid @RequestBody AddCartProductRequest request
	) {
		return ApiResult.success(cartUseCase.addCartProduct(buyerId, request));
	}

	@DeleteMapping("/{cartProductId}")
	@Operation(summary = "장바구니 상품 삭제", description = "인증된 구매자의 장바구니에서 상품을 삭제합니다.")
	@ApiResponses({
		@ApiResponse(responseCode = "200", description = "장바구니 상품 삭제 성공"),
		@ApiResponse(responseCode = "400", description = "V001 경로 변수 UUID 형식 오류",
			content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class))),
		@ApiResponse(responseCode = "401", description = "A003 토큰 만료 또는 유효하지 않음",
			content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class))),
		@ApiResponse(responseCode = "403", description = "C003 본인의 장바구니 항목이 아님",
			content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class))),
		@ApiResponse(responseCode = "404", description = "O006 장바구니 상품 없음",
			content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class)))
	})
	public ApiResult<Void> deleteCartProduct(
		@Parameter(hidden = true)
		@RequestHeader(AuthHeaders.USER_ID) UUID buyerId,
		@Parameter(description = "장바구니 상품 ID", example = "00000000-0000-0000-0000-000000000701")
		@PathVariable UUID cartProductId
	) {
		cartUseCase.deleteCartProduct(buyerId, cartProductId);
		return ApiResult.success();
	}
}
