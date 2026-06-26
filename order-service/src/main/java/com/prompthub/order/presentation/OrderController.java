package com.prompthub.order.presentation;

import com.prompthub.order.application.usecase.OrderUseCase;
import com.prompthub.order.global.web.AuthHeaders;
import com.prompthub.presentation.dto.ApiResult;
import com.prompthub.order.presentation.dto.request.CreateOrderRequest;
import com.prompthub.order.presentation.dto.request.PageRequestParams;
import com.prompthub.order.presentation.dto.response.CreateOrderResponse;
import com.prompthub.order.presentation.dto.response.OrderContentResponse;
import com.prompthub.order.presentation.dto.response.OrderDetailResponse;
import com.prompthub.order.presentation.dto.response.OrderListResponse;
import com.prompthub.order.presentation.dto.response.OrderPaymentListResponse;
import com.prompthub.presentation.dto.PageResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

import static com.prompthub.order.global.web.AuthHeaders.USER_ID;

@RestController
@RequestMapping("/api/v1/orders")
@RequiredArgsConstructor
@Tag(name = "Order", description = "주문, 구매 콘텐츠, 리뷰, 결제 내역 API")
@SecurityRequirement(name = "gatewayHeaders")
public class OrderController {

	private final OrderUseCase orderUseCase;

	@PostMapping
	@Operation(summary = "주문 생성", description = "인증된 구매자가 선택한 상품 목록으로 주문을 생성합니다.")
	@ApiResponses({
		@ApiResponse(responseCode = "200", description = "주문 생성 성공"),
		@ApiResponse(responseCode = "400", description = "V001 입력값 검증 실패, O004 장바구니가 비어 있음"),
		@ApiResponse(responseCode = "401", description = "A003 토큰 만료 또는 유효하지 않음"),
		@ApiResponse(responseCode = "409", description = "O010 이미 처리된 주문, O011 상품 가격 변경")
	})
	public ApiResult<CreateOrderResponse> createOrder(
		@Parameter(in = ParameterIn.HEADER, name = USER_ID, description = "Gateway가 주입하는 구매자 ID", required = true)
		@RequestHeader(USER_ID) UUID buyerId,
		@Valid @RequestBody CreateOrderRequest request
	) {
		return ApiResult.success(orderUseCase.createOrder(buyerId, request));
	}

	@GetMapping("/{orderId}")
	@Operation(summary = "주문 상세 조회", description = "구매자 본인의 주문 상세와 주문 상품 목록을 조회합니다.")
	@ApiResponses({
		@ApiResponse(responseCode = "200", description = "주문 상세 조회 성공"),
		@ApiResponse(responseCode = "401", description = "A003 토큰 만료 또는 유효하지 않음"),
		@ApiResponse(responseCode = "403", description = "O008 해당 주문에 접근할 수 없음"),
		@ApiResponse(responseCode = "404", description = "O001 주문 없음")
	})
	public ApiResult<OrderDetailResponse> getOrderDetail(
		@Parameter(in = ParameterIn.HEADER, name = USER_ID, description = "Gateway가 주입하는 구매자 ID", required = true)
		@RequestHeader(USER_ID) UUID buyerId,
		@Parameter(description = "주문 ID", example = "9f1c2a7e-4b8d-4e2a-9c11-2d3e4f5a1111")
		@PathVariable UUID orderId
	) {
		return ApiResult.success(orderUseCase.getOrderDetail(buyerId, orderId));
	}

	@GetMapping("/{orderId}/content/{orderProductId}")
	@Operation(summary = "구매 콘텐츠 열람", description = "결제 완료된 주문 상품의 구매 콘텐츠를 조회합니다.")
	@ApiResponses({
		@ApiResponse(responseCode = "200", description = "구매 콘텐츠 열람 성공"),
		@ApiResponse(responseCode = "401", description = "A003 토큰 만료 또는 유효하지 않음"),
		@ApiResponse(responseCode = "403", description = "E001 구매 콘텐츠 열람 불가"),
		@ApiResponse(responseCode = "404", description = "O001 주문 없음, O012 주문 상품 없음")
	})
	public ApiResult<OrderContentResponse> getOrderContent(
		@Parameter(in = ParameterIn.HEADER, name = USER_ID, description = "Gateway가 주입하는 구매자 ID", required = true)
		@RequestHeader(USER_ID) UUID buyerId,
		@Parameter(description = "주문 ID", example = "9f1c2a7e-4b8d-4e2a-9c11-2d3e4f5a1111")
		@PathVariable UUID orderId,
		@Parameter(description = "주문 상품 ID", example = "72d95cb0-1835-49bf-8f08-2e0f1c4e4aaa")
		@PathVariable UUID orderProductId
	) {
		return ApiResult.success(orderUseCase.getOrderContent(buyerId, orderId, orderProductId));
	}

	@GetMapping
	@Operation(summary = "주문 목록 조회", description = "구매자 본인의 주문 목록을 페이지 조건으로 조회합니다.")
	@ApiResponses({
		@ApiResponse(responseCode = "200", description = "주문 목록 조회 성공"),
		@ApiResponse(responseCode = "400", description = "V001 입력값 검증 실패"),
		@ApiResponse(responseCode = "401", description = "A003 토큰 만료 또는 유효하지 않음")
	})
	public PageResponse<OrderListResponse> getOrders(
		@Parameter(in = ParameterIn.HEADER, name = USER_ID, description = "Gateway가 주입하는 구매자 ID", required = true)
		@RequestHeader(USER_ID) UUID buyerId,
		@ModelAttribute PageRequestParams request
	) {
		PageRequestParams resolvedRequest = request.resolve();
		Page<OrderListResponse> orders = orderUseCase.getOrders(buyerId, resolvedRequest);

		return PageResponse.success(
			orders.getContent(),
			resolvedRequest.page(),
			resolvedRequest.size(),
			orders.getTotalElements(),
			orders.hasNext()
		);
	}

	@GetMapping("/payments")
	@Operation(summary = "주문 결제 내역 조회", description = "구매자 본인의 주문 결제 내역을 페이지 조건으로 조회합니다.")
	@ApiResponses({
		@ApiResponse(responseCode = "200", description = "주문 결제 내역 조회 성공"),
		@ApiResponse(responseCode = "400", description = "V001 입력값 검증 실패"),
		@ApiResponse(responseCode = "401", description = "A003 토큰 만료 또는 유효하지 않음")
	})
	public PageResponse<OrderPaymentListResponse> getOrderPayments(
		@Parameter(in = ParameterIn.HEADER, name = USER_ID, description = "Gateway가 주입하는 구매자 ID", required = true)
		@RequestHeader(USER_ID) UUID buyerId,
		@ModelAttribute PageRequestParams request
	) {
		PageRequestParams resolvedRequest = request.resolve();
		Page<OrderPaymentListResponse> orderPayments = orderUseCase.getOrderPayments(buyerId, resolvedRequest);

		return PageResponse.success(
			orderPayments.getContent(),
			resolvedRequest.page(),
			resolvedRequest.size(),
			orderPayments.getTotalElements(),
			orderPayments.hasNext()
		);
	}
}
