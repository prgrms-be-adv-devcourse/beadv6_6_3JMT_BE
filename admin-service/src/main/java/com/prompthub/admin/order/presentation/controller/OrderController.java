package com.prompthub.admin.order.presentation.controller;

import com.prompthub.admin.order.application.usecase.OrderUseCase;
import com.prompthub.admin.order.presentation.dto.request.OrderSearchCondition;
import com.prompthub.admin.order.presentation.dto.response.MonthlyTradeAmountResponse;
import com.prompthub.admin.order.presentation.dto.response.OrderListResponse;
import com.prompthub.admin.order.presentation.dto.response.WeeklyTransactionResponse;
import com.prompthub.presentation.dto.ApiResult;
import com.prompthub.presentation.dto.PageResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("${api.init}/admin/orders")
@RequiredArgsConstructor
@Tag(name = "Admin Order", description = "관리자 주문 관리 API (order-service 에서 이관)")
@SecurityRequirement(name = "gatewayHeaders")
public class OrderController {

	private final OrderUseCase orderUseCase;

	@GetMapping
	@Operation(summary = "관리자 전체 주문 목록 조회", description = "관리자가 전체 주문을 상태 조건과 페이지 조건으로 조회합니다.")
	@ApiResponses({
		@ApiResponse(responseCode = "200", description = "관리자 주문 목록 조회 성공"),
		@ApiResponse(responseCode = "400", description = "요청 값 검증 실패"),
		@ApiResponse(responseCode = "401", description = "인증 정보 없음"),
		@ApiResponse(responseCode = "403", description = "ADMIN 권한 없음")
	})
	public PageResponse<OrderListResponse> getOrders(@ModelAttribute OrderSearchCondition condition) {
		OrderSearchCondition resolvedCondition = condition.resolve();
		Page<OrderListResponse> orders = orderUseCase.getOrders(resolvedCondition);

		return PageResponse.success(
			orders.getContent(),
			resolvedCondition.page(),
			resolvedCondition.size(),
			orders.getTotalElements(),
			orders.hasNext()
		);
	}

	@GetMapping("/month")
	@Operation(summary = "이번 달 실제 거래액 조회", description = "이번 달 승인 금액에서 취소/환불 금액을 차감한 실제 거래액을 조회합니다.")
	@ApiResponses({
		@ApiResponse(responseCode = "200", description = "월간 실제 거래액 조회 성공"),
		@ApiResponse(responseCode = "401", description = "인증 정보 없음"),
		@ApiResponse(responseCode = "403", description = "ADMIN 권한 없음")
	})
	public ApiResult<MonthlyTradeAmountResponse> getMonthlyTransactionAmount() {
		return ApiResult.success(orderUseCase.getMonthlyTransactionAmount());
	}

	@GetMapping("/weekend")
	@Operation(summary = "최근 7일 거래량 조회", description = "최근 7일의 일자별 결제 승인 건수와 실제 거래액을 조회합니다.")
	@ApiResponses({
		@ApiResponse(responseCode = "200", description = "최근 7일 거래량 조회 성공"),
		@ApiResponse(responseCode = "401", description = "인증 정보 없음"),
		@ApiResponse(responseCode = "403", description = "ADMIN 권한 없음")
	})
	public ApiResult<WeeklyTransactionResponse> getWeeklyTransactions() {
		return ApiResult.success(orderUseCase.getWeeklyTransactions());
	}
}
