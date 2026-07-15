package com.prompthub.order.presentation;

import com.prompthub.order.application.usecase.OrderRefundUseCase;
import com.prompthub.order.presentation.dto.request.RefundOrderRequest;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

import static com.prompthub.order.global.web.AuthHeaders.USER_ID;

@RestController
@RequestMapping("/api/v2/orders")
@RequiredArgsConstructor
@Tag(name = "Order Refund", description = "주문 상품 단건 부분 환불 API")
@SecurityRequirement(name = "gatewayHeaders")
public class OrderRefundController {

	private final OrderRefundUseCase orderRefundUseCase;

	@PostMapping("/{orderId}/refund")
	@ResponseStatus(HttpStatus.ACCEPTED)
	@Operation(
		summary = "주문 상품 단건 부분 환불 요청",
		description = "결제 완료된 미다운로드 주문 상품 한 건의 부분 환불을 비동기로 접수합니다."
	)
	@ApiResponses({
		@ApiResponse(responseCode = "202", description = "환불 요청 접수 성공, 빈 응답 본문"),
		@ApiResponse(responseCode = "400", description = "V001 입력값 검증 실패, O014 결제 승인 금액 불일치"),
		@ApiResponse(responseCode = "401", description = "A003 토큰 만료 또는 유효하지 않음"),
		@ApiResponse(responseCode = "403", description = "A004 권한 없음, O008 주문 접근 불가"),
		@ApiResponse(responseCode = "404", description = "O001 주문 없음, O012 주문 상품 없음, O016 주문 결제 없음"),
		@ApiResponse(responseCode = "409", description = "O017 환불 불가, O018 환불 진행 중, O019 동시 변경 충돌")
	})
	public void requestRefund(
		@Parameter(in = ParameterIn.HEADER, name = USER_ID, description = "Gateway가 주입하는 구매자 ID", required = true)
		@RequestHeader(USER_ID) UUID buyerId,
		@Parameter(description = "주문 ID", example = "9f1c2a7e-4b8d-4e2a-9c11-2d3e4f5a1111")
		@PathVariable UUID orderId,
		@Valid @RequestBody RefundOrderRequest request
	) {
		orderRefundUseCase.requestRefund(
			buyerId,
			orderId,
			request.paymentId(),
			request.orderProductId()
		);
	}
}
