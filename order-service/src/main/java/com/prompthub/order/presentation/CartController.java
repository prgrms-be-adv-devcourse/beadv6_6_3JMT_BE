package com.prompthub.order.presentation;

import com.prompthub.order.application.usecase.CartUseCase;
import com.prompthub.order.global.web.AuthHeaders;
import com.prompthub.order.presentation.dto.request.AddCartProductRequest;
import com.prompthub.order.presentation.dto.response.AddCartProductResponse;
import com.prompthub.order.presentation.dto.response.CartResponse;
import com.prompthub.presentation.dto.ApiResult;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
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
@SecurityRequirement(name = "gatewayHeaders")
public class CartController {

	private final CartUseCase cartUseCase;

	@GetMapping
	@Operation(summary = "장바구니 조회", description = "인증된 구매자의 장바구니 상품 목록과 합계 금액을 조회합니다.")
	@ApiResponses({
		@ApiResponse(responseCode = "200", description = "장바구니 조회 성공"),
		@ApiResponse(responseCode = "401", description = "A003 토큰 만료 또는 유효하지 않음"),
		@ApiResponse(responseCode = "404", description = "O005 장바구니 없음")
	})
	public ApiResult<CartResponse> getCart(
		@Parameter(in = ParameterIn.HEADER, name = AuthHeaders.USER_ID, description = "Gateway가 주입하는 구매자 ID", required = true)
		@RequestHeader(AuthHeaders.USER_ID) UUID buyerId
	) {
		return ApiResult.success(cartUseCase.getCart(buyerId));
	}

	@PostMapping
	@Operation(summary = "장바구니 상품 추가", description = "인증된 구매자의 장바구니에 상품을 추가합니다.")
	@ApiResponses({
		@ApiResponse(responseCode = "200", description = "장바구니 상품 추가 성공"),
		@ApiResponse(responseCode = "400", description = "V001 입력값 검증 실패, O003 판매 중이 아닌 상품"),
		@ApiResponse(responseCode = "401", description = "A003 토큰 만료 또는 유효하지 않음"),
		@ApiResponse(responseCode = "409", description = "C001 이미 장바구니에 담긴 상품")
	})
	public ApiResult<AddCartProductResponse> addCartProduct(
		@Parameter(in = ParameterIn.HEADER, name = AuthHeaders.USER_ID, description = "Gateway가 주입하는 구매자 ID", required = true)
		@RequestHeader(AuthHeaders.USER_ID) UUID buyerId,
		@Valid @RequestBody AddCartProductRequest request
	) {
		return ApiResult.success(cartUseCase.addCartProduct(buyerId, request));
	}

	@DeleteMapping("/{cartProductId}")
	@Operation(summary = "장바구니 상품 삭제", description = "인증된 구매자의 장바구니에서 상품을 삭제합니다.")
	@ApiResponses({
		@ApiResponse(responseCode = "200", description = "장바구니 상품 삭제 성공"),
		@ApiResponse(responseCode = "400", description = "V001 입력값 검증 실패"),
		@ApiResponse(responseCode = "401", description = "A003 토큰 만료 또는 유효하지 않음"),
		@ApiResponse(responseCode = "403", description = "C003 본인의 장바구니 항목이 아님"),
		@ApiResponse(responseCode = "404", description = "O006 장바구니 상품 없음")
	})
	public ApiResult<Void> deleteCartProduct(
		@Parameter(in = ParameterIn.HEADER, name = AuthHeaders.USER_ID, description = "Gateway가 주입하는 구매자 ID", required = true)
		@RequestHeader(AuthHeaders.USER_ID) UUID buyerId,
		@Parameter(description = "장바구니 상품 ID", example = "00000000-0000-0000-0000-000000000701")
		@PathVariable UUID cartProductId
	) {
		cartUseCase.deleteCartProduct(buyerId, cartProductId);
		return ApiResult.success();
	}
}
