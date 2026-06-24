package com.prompthub.order.presentation;

import com.prompthub.order.application.usecase.AdminOrderUseCase;
import com.prompthub.order.global.web.AuthHeaders;
import com.prompthub.order.presentation.dto.request.AdminOrderSearchCondition;
import com.prompthub.order.presentation.dto.response.AdminMonthlyTradeAmountResponse;
import com.prompthub.order.presentation.dto.response.AdminOrderListResponse;
import com.prompthub.order.presentation.dto.response.AdminWeeklyTransactionResponse;
import com.prompthub.presentation.dto.ApiResult;
import com.prompthub.presentation.dto.PageResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import static com.prompthub.order.global.web.AuthHeaders.USER_ROLE;

@RestController
@RequestMapping("/api/v1/admin/orders")
@RequiredArgsConstructor
@Tag(name = "Admin Order", description = "관리자 주문 관리 API")
@SecurityRequirement(name = "gatewayHeaders")
public class AdminOrderController {

	private final AdminOrderUseCase adminOrderUseCase;

	@GetMapping
	@Operation(summary = "관리자 전체 주문 목록 조회", description = "관리자가 전체 주문을 상태 조건과 페이지 조건으로 조회합니다.")
	@ApiResponses({
		@ApiResponse(responseCode = "200", description = "관리자 주문 목록 조회 성공"),
		@ApiResponse(responseCode = "400", description = "V001 입력값 검증 실패"),
		@ApiResponse(responseCode = "401", description = "A003 토큰 만료 또는 유효하지 않음"),
		@ApiResponse(responseCode = "403", description = "A004 권한 없음")
	})
	public PageResponse<AdminOrderListResponse> getAdminOrders(
		@Parameter(in = ParameterIn.HEADER, name = USER_ROLE, description = "Gateway가 주입하는 사용자 권한", required = true)
		@RequestHeader(USER_ROLE) String userRole,
		@ModelAttribute AdminOrderSearchCondition condition
	) {
		AdminOrderSearchCondition resolvedCondition = condition.resolve();
		Page<AdminOrderListResponse> orders = adminOrderUseCase.getAdminOrders(resolvedCondition);

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
		@ApiResponse(responseCode = "401", description = "A003 토큰 만료 또는 유효하지 않음"),
		@ApiResponse(responseCode = "403", description = "A004 권한 없음")
	})
	public ApiResult<AdminMonthlyTradeAmountResponse> getMonthlyTransactionAmount(
		@Parameter(in = ParameterIn.HEADER, name = USER_ROLE, description = "Gateway가 주입하는 사용자 권한", required = true)
		@RequestHeader(USER_ROLE) String userRole
	) {
		return ApiResult.success(adminOrderUseCase.getMonthlyTransactionAmount());
	}

	@GetMapping("/weekend")
	@Operation(summary = "최근 7일 거래량 조회", description = "최근 7일의 일자별 결제 승인 건수와 실제 거래액을 조회합니다.")
	@ApiResponses({
		@ApiResponse(responseCode = "200", description = "최근 7일 거래량 조회 성공"),
		@ApiResponse(responseCode = "401", description = "A003 토큰 만료 또는 유효하지 않음"),
		@ApiResponse(responseCode = "403", description = "A004 권한 없음")
	})
	public ApiResult<AdminWeeklyTransactionResponse> getWeeklyTransactions(
		@Parameter(in = ParameterIn.HEADER, name = USER_ROLE, description = "Gateway가 주입하는 사용자 권한", required = true)
		@RequestHeader(USER_ROLE) String userRole
	) {
		return ApiResult.success(adminOrderUseCase.getWeeklyTransactions());
	}
}
