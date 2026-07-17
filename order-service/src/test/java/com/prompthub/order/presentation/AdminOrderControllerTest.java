package com.prompthub.order.presentation;

import com.prompthub.order.application.usecase.AdminOrderUseCase;
import com.prompthub.order.domain.enums.OrderStatus;
import com.prompthub.order.global.exception.ErrorCode;
import com.prompthub.order.global.exception.GlobalExceptionHandler;
import com.prompthub.order.global.web.AdminAuthInterceptor;
import com.prompthub.order.global.web.AuthHeaders;
import com.prompthub.order.presentation.dto.request.AdminOrderSearchCondition;
import com.prompthub.order.presentation.dto.response.AdminDailyTransactionResponse;
import com.prompthub.order.presentation.dto.response.AdminMonthlyTradeAmountResponse;
import com.prompthub.order.presentation.dto.response.AdminOrderListResponse;
import com.prompthub.order.presentation.dto.response.AdminTransactionPeriodResponse;
import com.prompthub.order.presentation.dto.response.AdminWeeklyTransactionResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;

import java.time.LocalDateTime;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static com.prompthub.order.fixture.OrderFixture.ORDER_ID;
import static com.prompthub.order.fixture.OrderFixture.PRODUCT_TITLE_1;
import static com.prompthub.order.fixture.OrderFixture.TOTAL_AMOUNT;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class AdminOrderControllerTest {

	private MockMvc mockMvc;

	@Mock
	private AdminOrderUseCase adminOrderUseCase;

	@BeforeEach
	void setUp() {
		LocalValidatorFactoryBean validator = new LocalValidatorFactoryBean();
		validator.afterPropertiesSet();

		mockMvc = MockMvcBuilders.standaloneSetup(new AdminOrderController(adminOrderUseCase))
			.setControllerAdvice(new GlobalExceptionHandler())
			.addInterceptors(new AdminAuthInterceptor())
			.setValidator(validator)
			.build();
	}

	@Test
	@DisplayName("ADMIN 권한으로 전체 주문 목록을 조회한다")
	void getAdminOrders_admin_success() throws Exception {
		AdminOrderSearchCondition condition = new AdminOrderSearchCondition("ALL", 1, 20).resolve();
		AdminOrderListResponse order = new AdminOrderListResponse(
			ORDER_ID,
			3,
			List.of(
				new AdminOrderListResponse.SellerSummary(
					UUID.fromString("00000000-0000-0000-0000-000000000201"), "판매자A", 2, 30_000
				),
				new AdminOrderListResponse.SellerSummary(
					UUID.fromString("00000000-0000-0000-0000-000000000202"), "판매자B", 1, 15_000
				),
				new AdminOrderListResponse.SellerSummary(
					UUID.fromString("00000000-0000-0000-0000-000000000203"), "판매자C", 1, 25_000
				)
			),
			PRODUCT_TITLE_1,
			4,
			70_000,
			OrderStatus.PAID,
			LocalDateTime.of(2026, 6, 24, 10, 0)
		);
		given(adminOrderUseCase.getAdminOrders(eq(condition)))
			.willReturn(new PageImpl<>(List.of(order), PageRequest.of(0, 20), 1));

		mockMvc.perform(get("/api/v1/admin/orders")
				.header(AuthHeaders.USER_ROLE, AuthHeaders.ADMIN)
				.param("orderStatus", "ALL")
				.param("page", "1")
				.param("size", "20"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.success").value(true))
			.andExpect(jsonPath("$.data[0].sellerCount").value(3))
			.andExpect(jsonPath("$.data[0].sellers.length()").value(3))
			.andExpect(jsonPath("$.data[0].sellers[0].sellerNickname").value("판매자A"))
			.andExpect(jsonPath("$.data[0].sellerNickname").doesNotExist())
			.andExpect(jsonPath("$.data[0].totalOrderCount").value(4))
			.andExpect(jsonPath("$.meta.page").value(1))
			.andExpect(jsonPath("$.meta.size").value(20))
			.andExpect(jsonPath("$.meta.total").value(1))
			.andExpect(jsonPath("$.meta.hasNext").value(false));
	}

	@Test
	@DisplayName("USER 권한으로 관리자 주문 목록 조회 시 403")
	void getAdminOrders_user_forbidden() throws Exception {
		mockMvc.perform(get("/api/v1/admin/orders")
				.header(AuthHeaders.USER_ROLE, AuthHeaders.BUYER))
			.andExpect(status().isForbidden())
			.andExpect(jsonPath("$.success").value(false))
			.andExpect(jsonPath("$.code").value(ErrorCode.FORBIDDEN.getCode()));

		verifyNoInteractions(adminOrderUseCase);
	}

	@Test
	@DisplayName("X-User-Role이 없으면 401")
	void getAdminOrders_missingRole_unauthorized() throws Exception {
		mockMvc.perform(get("/api/v1/admin/orders"))
			.andExpect(status().isUnauthorized())
			.andExpect(jsonPath("$.success").value(false))
			.andExpect(jsonPath("$.code").value(ErrorCode.INVALID_AUTHENTICATION.getCode()));

		verifyNoInteractions(adminOrderUseCase);
	}

	@Test
	@DisplayName("존재하지 않는 주문 상태는 400")
	void getAdminOrders_invalidStatus_badRequest() throws Exception {
		mockMvc.perform(get("/api/v1/admin/orders")
				.header(AuthHeaders.USER_ROLE, AuthHeaders.ADMIN)
				.param("orderStatus", "UNKNOWN"))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.success").value(false))
			.andExpect(jsonPath("$.code").value(ErrorCode.INVALID_INPUT_VALUE.getCode()));

		verifyNoInteractions(adminOrderUseCase);
	}

	@Test
	@DisplayName("ADMIN 권한으로 이번 달 실제 거래액을 조회한다")
	void getMonthlyTransactionAmount_admin_success() throws Exception {
		given(adminOrderUseCase.getMonthlyTransactionAmount())
			.willReturn(new AdminMonthlyTradeAmountResponse(25_000L));

		mockMvc.perform(get("/api/v1/admin/orders/month")
				.header(AuthHeaders.USER_ROLE, AuthHeaders.ADMIN))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.success").value(true))
			.andExpect(jsonPath("$.data.monthlyTransactionAmount").value(25_000L));
	}

	@Test
	@DisplayName("USER 권한으로 이번 달 실제 거래액 조회 시 403")
	void getMonthlyTransactionAmount_user_forbidden() throws Exception {
		mockMvc.perform(get("/api/v1/admin/orders/month")
				.header(AuthHeaders.USER_ROLE, AuthHeaders.BUYER))
			.andExpect(status().isForbidden())
			.andExpect(jsonPath("$.success").value(false))
			.andExpect(jsonPath("$.code").value(ErrorCode.FORBIDDEN.getCode()));

		verifyNoInteractions(adminOrderUseCase);
	}

	@Test
	@DisplayName("SELLER 권한으로 이번 달 실제 거래액 조회 시 403")
	void getMonthlyTransactionAmount_seller_forbidden() throws Exception {
		mockMvc.perform(get("/api/v1/admin/orders/month")
				.header(AuthHeaders.USER_ROLE, AuthHeaders.SELLER))
			.andExpect(status().isForbidden())
			.andExpect(jsonPath("$.success").value(false))
			.andExpect(jsonPath("$.code").value(ErrorCode.FORBIDDEN.getCode()));

		verifyNoInteractions(adminOrderUseCase);
	}

	@Test
	@DisplayName("ADMIN 권한으로 최근 7일 거래량을 조회한다")
	void getWeeklyTransactions_admin_success() throws Exception {
		given(adminOrderUseCase.getWeeklyTransactions())
			.willReturn(new AdminWeeklyTransactionResponse(
				2L,
				30_000L,
				new AdminTransactionPeriodResponse(LocalDate.of(2026, 6, 18), LocalDate.of(2026, 6, 24)),
				List.of(new AdminDailyTransactionResponse(LocalDate.of(2026, 6, 24), 2L, 30_000L))
			));

		mockMvc.perform(get("/api/v1/admin/orders/weekend")
				.header(AuthHeaders.USER_ROLE, AuthHeaders.ADMIN))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.success").value(true))
			.andExpect(jsonPath("$.data.totalTransactionCount").value(2))
			.andExpect(jsonPath("$.data.totalTransactionAmount").value(30_000))
			.andExpect(jsonPath("$.data.period.startDate").value("2026-06-18"))
			.andExpect(jsonPath("$.data.period.endDate").value("2026-06-24"))
			.andExpect(jsonPath("$.data.dailyTransactions[0].date").value("2026-06-24"))
			.andExpect(jsonPath("$.data.dailyTransactions[0].transactionCount").value(2))
			.andExpect(jsonPath("$.data.dailyTransactions[0].transactionAmount").value(30_000));
	}

	@Test
	@DisplayName("USER 권한으로 최근 7일 거래량 조회 시 403")
	void getWeeklyTransactions_user_forbidden() throws Exception {
		mockMvc.perform(get("/api/v1/admin/orders/weekend")
				.header(AuthHeaders.USER_ROLE, AuthHeaders.BUYER))
			.andExpect(status().isForbidden())
			.andExpect(jsonPath("$.success").value(false))
			.andExpect(jsonPath("$.code").value(ErrorCode.FORBIDDEN.getCode()));

		verifyNoInteractions(adminOrderUseCase);
	}

	@Test
	@DisplayName("SELLER 권한으로 최근 7일 거래량 조회 시 403")
	void getWeeklyTransactions_seller_forbidden() throws Exception {
		mockMvc.perform(get("/api/v1/admin/orders/weekend")
				.header(AuthHeaders.USER_ROLE, AuthHeaders.SELLER))
			.andExpect(status().isForbidden())
			.andExpect(jsonPath("$.success").value(false))
			.andExpect(jsonPath("$.code").value(ErrorCode.FORBIDDEN.getCode()));

		verifyNoInteractions(adminOrderUseCase);
	}

}
