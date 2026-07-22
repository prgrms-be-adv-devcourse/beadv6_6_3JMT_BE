package com.prompthub.order.presentation;

import com.prompthub.order.application.dto.CreateOrderResult;
import com.prompthub.order.application.dto.RefundResult;
import com.prompthub.order.application.service.refund.OrderRefundService;
import com.prompthub.order.application.usecase.ConfirmDownloadUseCase;
import com.prompthub.order.application.usecase.CreateOrderUseCase;
import com.prompthub.order.application.usecase.OrderQueryUseCase;
import com.prompthub.order.presentation.dto.RefundOrderRequest;
import com.prompthub.order.presentation.dto.request.CreateOrderRequest;
import com.prompthub.order.presentation.dto.request.PageRequestParams;
import com.prompthub.order.presentation.dto.response.CreateOrderResponse;
import com.prompthub.order.presentation.dto.response.OrderContentResponse;
import com.prompthub.order.presentation.dto.response.OrderDetailResponse;
import com.prompthub.order.presentation.dto.response.OrderListResponse;
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
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

import static com.prompthub.order.global.web.AuthHeaders.USER_ID;

@RequestMapping("/api/v2/orders")
@RestController
@RequiredArgsConstructor
@Tag(name = "Order", description = "주문 생성, 조회, 구매 콘텐츠, 리뷰 API")
@SecurityRequirement(name = "gatewayHeaders")
public class OrderController {

	private final ConfirmDownloadUseCase confirmDownloadUseCase;
	private final OrderQueryUseCase orderQueryUseCase;
	private final CreateOrderUseCase createOrderUseCase;
	private final OrderRefundService orderRefundService;

	@PostMapping
	@Operation(summary = "단일 주문 생성", description = "요청 상품을 하나의 주문으로 생성하고 주문 상품별 판매자 정보를 제공합니다.")
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

	@GetMapping("/product/{productId}/paid")
	@Operation(summary = "상품 구매 여부 조회", description = "구매자가 현재 상품 콘텐츠를 열람할 수 있는 결제 상태인지 반환합니다.")
	@ApiResponses({
		@ApiResponse(responseCode = "200", description = "구매 여부 조회 성공"),
		@ApiResponse(responseCode = "400", description = "V001 입력값 검증 실패"),
		@ApiResponse(responseCode = "401", description = "A003 인증 정보 누락")
	})
	public ApiResult<Boolean> hasAccessiblePaidProduct(
		@Parameter(in = ParameterIn.HEADER, name = USER_ID, description = "Gateway가 주입하는 구매자 ID", required = true)
		@RequestHeader(USER_ID) UUID buyerId,
		@Parameter(description = "상품 ID") @PathVariable UUID productId
	) {
		return ApiResult.success(orderQueryUseCase.hasAccessiblePaidProduct(buyerId, productId));
	}

	@GetMapping("/users")
	@Operation(summary = "구매 상품 ID 목록 조회", description = "구매자가 현재 열람할 수 있는 상품 ID 목록을 중복 없이 반환합니다.")
	@ApiResponses({
		@ApiResponse(responseCode = "200", description = "구매 상품 ID 목록 조회 성공"),
		@ApiResponse(responseCode = "401", description = "A003 인증 정보 누락")
	})
	public ApiResult<List<UUID>> getAccessiblePaidProductIds(
		@Parameter(in = ParameterIn.HEADER, name = USER_ID, description = "Gateway가 주입하는 구매자 ID", required = true)
		@RequestHeader(USER_ID) UUID buyerId
	) {
		return ApiResult.success(orderQueryUseCase.getAccessiblePaidProductIds(buyerId));
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
		return ApiResult.success(orderQueryUseCase.getOrderDetail(buyerId, orderId));
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
		return ApiResult.success(orderQueryUseCase.getOrderContent(buyerId, orderId, orderProductId));
	}

	@GetMapping
	@Operation(summary = "주문 목록 조회", description = "구매자 본인의 주문을 주문 단위로 페이지 조회하고 각 주문의 주문상품 목록을 함께 반환합니다.")
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

	@PatchMapping("/{orderId}/products/{orderProductId}/download")
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

	@PostMapping("/{orderId}/refund")
	@ResponseStatus(HttpStatus.ACCEPTED)
	@Operation(summary = "주문 상품 다건 부분 환불 요청", description = "결제 완료된 미다운로드 주문 상품을 비동기로 환불 접수합니다.")
	@ApiResponses({
		@ApiResponse(responseCode = "202", description = "환불 요청 접수 성공"),
		@ApiResponse(responseCode = "400", description = "V001 입력값 검증 실패"),
		@ApiResponse(responseCode = "403", description = "O008 주문 접근 불가"),
		@ApiResponse(responseCode = "404", description = "O001 주문 없음, O012 주문 상품 없음"),
		@ApiResponse(responseCode = "409", description = "O017 환불 불가")
	})
	public ApiResult<RefundResult> requestRefund(
		@Parameter(in = ParameterIn.HEADER, name = USER_ID, description = "Gateway가 주입하는 구매자 ID", required = true)
		@RequestHeader(USER_ID) UUID buyerId,
		@PathVariable UUID orderId,
		@Valid @RequestBody RefundOrderRequest request
	) {
		return ApiResult.success(orderRefundService.requestRefund(buyerId, orderId, request.orderProductIds()));
	}
}
