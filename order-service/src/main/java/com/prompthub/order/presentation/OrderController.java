package com.prompthub.order.presentation;

import com.prompthub.order.application.dto.CreateOrderResult;
import com.prompthub.order.application.usecase.ConfirmDownloadUseCase;
import com.prompthub.order.application.usecase.CreateOrderUseCase;
import com.prompthub.order.application.usecase.OrderQueryUseCase;
import com.prompthub.order.presentation.dto.request.CreateOrderRequest;
import com.prompthub.order.presentation.dto.request.OrderPaymentValidationRequest;
import com.prompthub.order.presentation.dto.request.PageRequestParams;
import com.prompthub.order.presentation.dto.response.CreateOrderResponse;
import com.prompthub.order.presentation.dto.response.OrderContentResponse;
import com.prompthub.order.presentation.dto.response.OrderDetailResponse;
import com.prompthub.order.presentation.dto.response.OrderListResponse;
import com.prompthub.order.presentation.dto.response.OrderPaymentListResponse;
import com.prompthub.order.presentation.dto.response.OrderPaymentValidationResponse;
import com.prompthub.order.presentation.dto.response.OrderProductDownloadResponse;
import com.prompthub.presentation.dto.ApiResult;
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
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.UUID;

import static com.prompthub.order.global.web.AuthHeaders.USER_ID;

@RestController
@RequiredArgsConstructor
@Tag(name = "Order", description = "주문 생성, 조회, 구매 콘텐츠, 리뷰, 결제 내역 API")
@SecurityRequirement(name = "gatewayHeaders")
public class OrderController {

	private final ConfirmDownloadUseCase confirmDownloadUseCase;
	private final OrderQueryUseCase orderQueryUseCase;
	private final CreateOrderUseCase createOrderUseCase;

	@PostMapping("/api/v2/orders")
	@Operation(summary = "판매자별 주문 생성", description = "상품을 판매자별 주문으로 분리해 생성합니다.")
	@ApiResponses({
		@ApiResponse(responseCode = "200", description = "주문 생성 성공"),
		@ApiResponse(responseCode = "400", description = "V001 입력값 검증 실패"),
		@ApiResponse(responseCode = "401", description = "A003 인증 정보 누락"),
		@ApiResponse(responseCode = "403", description = "A004 구매자 권한 없음"),
		@ApiResponse(responseCode = "503", description = "SYS002 상품 서비스 사용 불가")
	})
	public ApiResult<CreateOrderResponse> createOrder(
		@Parameter(in = ParameterIn.HEADER, name = USER_ID, description = "Gateway가 주입하는 구매자 ID", required = true)
		@RequestHeader(USER_ID) UUID buyerId,
		@Valid @RequestBody CreateOrderRequest request
	) {
		CreateOrderResult result = createOrderUseCase.createOrder(buyerId, request.toCommand());
		return ApiResult.success(CreateOrderResponse.from(result));
	}

	@GetMapping("/api/v1/orders/{orderId}")
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
		return ApiResult.success(orderQueryUseCase.getOrderDetail(buyerId, orderId));
	}

	@Operation(summary = "결제 승인 전 유효성 검사", description = "PG 승인 요청 전에 주문 소유자, 상태, 만료 시간, 결제 금액을 검증합니다.")
	@PostMapping("/api/v1/orders/{orderId}/payment-ready")
	@ApiResponses({
		@ApiResponse(responseCode = "200", description = "결제 가능 주문"),
		@ApiResponse(responseCode = "400", description = "V001 입력값 검증 실패, O014 주문 금액 불일치"),
		@ApiResponse(responseCode = "401", description = "A003 토큰 만료 또는 유효하지 않음"),
		@ApiResponse(responseCode = "403", description = "A004 권한 없음"),
		@ApiResponse(responseCode = "404", description = "O001 주문 없음"),
		@ApiResponse(responseCode = "409", description = "O010 이미 처리된 주문, O015 만료된 주문")
	})
	public ApiResult<OrderPaymentValidationResponse> validatePaymentReady(
		@Parameter(in = ParameterIn.HEADER, name = USER_ID, description = "Gateway가 주입하는 구매자 ID", required = true)
		@RequestHeader(USER_ID) UUID buyerId,
		@Parameter(description = "주문 ID", example = "9f1c2a7e-4b8d-4e2a-9c11-2d3e4f5a1111")
		@PathVariable UUID orderId,
		@RequestBody @Valid OrderPaymentValidationRequest request
	) {
		return ApiResult.success(orderQueryUseCase.validatePaymentReady(
			buyerId,
			orderId,
			request.amount(),
			LocalDateTime.now()
		));
	}

	@GetMapping("/api/v1/orders/{orderId}/content/{orderProductId}")
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
		return ApiResult.success(orderQueryUseCase.getOrderContent(buyerId, orderId, orderProductId));
	}

	@GetMapping("/api/v1/orders")
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
		Page<OrderListResponse> orders = orderQueryUseCase.getOrders(buyerId, resolvedRequest);

		return PageResponse.success(
			orders.getContent(),
			resolvedRequest.page(),
			resolvedRequest.size(),
			orders.getTotalElements(),
			orders.hasNext()
		);
	}

	@GetMapping("/api/v1/orders/payments")
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
		Page<OrderPaymentListResponse> orderPayments = orderQueryUseCase.getOrderPayments(buyerId, resolvedRequest);

		return PageResponse.success(
			orderPayments.getContent(),
			resolvedRequest.page(),
			resolvedRequest.size(),
			orderPayments.getTotalElements(),
			orderPayments.hasNext()
		);
	}

	@PatchMapping("/api/v1/orders/{orderId}/products/{orderProductId}/download")
	@Operation(summary = "주문상품 다운로드 확정", description = "구매자가 다운로드 버튼을 클릭했음을 기록하고 환불 가능 여부를 반환합니다.")
	@ApiResponses({
		@ApiResponse(responseCode = "200", description = "주문상품 다운로드 확정 성공"),
		@ApiResponse(responseCode = "401", description = "A003 토큰 만료 또는 유효하지 않음"),
		@ApiResponse(responseCode = "403", description = "A004 권한 없음, E001 구매 콘텐츠 열람 불가"),
		@ApiResponse(responseCode = "404", description = "O001 주문 없음, O012 주문 상품 없음")
	})
	public ApiResult<OrderProductDownloadResponse> confirmDownload(
		@Parameter(in = ParameterIn.HEADER, name = USER_ID, description = "Gateway가 주입하는 구매자 ID", required = true)
		@RequestHeader(USER_ID) UUID buyerId,
		@Parameter(description = "주문 ID", example = "9f1c2a7e-4b8d-4e2a-9c11-2d3e4f5a1111")
		@PathVariable UUID orderId,
		@Parameter(description = "주문 상품 ID", example = "72d95cb0-1835-49bf-8f08-2e0f1c4e4aaa")
		@PathVariable UUID orderProductId
	) {
		return ApiResult.success(confirmDownloadUseCase.confirmDownload(buyerId, orderId, orderProductId));
	}
}
