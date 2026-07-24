package com.prompthub.admin.order.presentation.controller;

import com.prompthub.admin.order.application.service.OrderService;
import com.prompthub.admin.order.domain.enums.OrderStatus;
import com.prompthub.admin.order.presentation.dto.response.DailyTransactionResponse;
import com.prompthub.admin.order.presentation.dto.response.MonthlyTradeAmountResponse;
import com.prompthub.admin.order.presentation.dto.response.OrderListResponse;
import com.prompthub.admin.order.presentation.dto.response.TransactionPeriodResponse;
import com.prompthub.admin.order.presentation.dto.response.WeeklyTransactionResponse;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(OrderController.class)
@ActiveProfiles("test")
class OrderControllerTest {

	@Autowired
	private MockMvc mockMvc;

	@MockitoBean
	private OrderService orderService;

	@Test
	void 전체_주문_목록을_조회한다() throws Exception {
		OrderListResponse order = new OrderListResponse(
			"ORD-20260624-0001",
			new OrderListResponse.UserSummary(
				UUID.fromString("00000000-0000-0000-0000-000000000101"), "구매자A", "https://cdn/buyer.png"
			),
			30_000,
			OrderStatus.COMPLETED,
			LocalDateTime.of(2026, 6, 24, 10, 0),
			List.of(new OrderListResponse.OrderProductSummary(
				new OrderListResponse.UserSummary(
					UUID.fromString("00000000-0000-0000-0000-000000000201"), "판매자A", "https://cdn/seller-a.png"
				),
				"프롬프트 상품 1", 30_000, "PAID"
			))
		);
		when(orderService.getOrders(eq(new com.prompthub.admin.order.presentation.dto.request.OrderSearchCondition("ALL", 1, 20).resolve())))
			.thenReturn(new PageImpl<>(List.of(order), PageRequest.of(0, 20), 1));

		mockMvc.perform(get("/api/v2/admin/orders")
				.param("orderStatus", "ALL")
				.param("page", "1")
				.param("size", "20"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.success").value(true))
			.andExpect(jsonPath("$.data[0].orderNumber").value("ORD-20260624-0001"))
			.andExpect(jsonPath("$.data[0].buyer.name").value("구매자A"))
			.andExpect(jsonPath("$.data[0].buyer.profileImageUrl").value("https://cdn/buyer.png"))
			.andExpect(jsonPath("$.data[0].orderProducts[0].seller.name").value("판매자A"))
			.andExpect(jsonPath("$.data[0].orderProducts[0].productTitle").value("프롬프트 상품 1"))
			.andExpect(jsonPath("$.data[0].orderProducts[0].productAmount").value(30_000))
			.andExpect(jsonPath("$.data[0].orderProducts[0].orderProductStatus").value("PAID"))
			.andExpect(jsonPath("$.meta.total").value(1));
	}

	@Test
	void 존재하지_않는_주문_상태는_400을_내려준다() throws Exception {
		mockMvc.perform(get("/api/v2/admin/orders").param("orderStatus", "UNKNOWN"))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.success").value(false))
			.andExpect(jsonPath("$.code").value("A-001"));
	}

	@Test
	void 이번_달_실제_거래액을_조회한다() throws Exception {
		when(orderService.getMonthlyTransactionAmount())
			.thenReturn(new MonthlyTradeAmountResponse(25_000L));

		mockMvc.perform(get("/api/v2/admin/orders/month"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.monthlyTransactionAmount").value(25_000L));
	}

	@Test
	void 최근_7일_거래량을_조회한다() throws Exception {
		when(orderService.getWeeklyTransactions())
			.thenReturn(new WeeklyTransactionResponse(
				2L,
				30_000L,
				new TransactionPeriodResponse(LocalDate.of(2026, 6, 18), LocalDate.of(2026, 6, 24)),
				List.of(new DailyTransactionResponse(LocalDate.of(2026, 6, 24), 2L, 30_000L))
			));

		mockMvc.perform(get("/api/v2/admin/orders/weekend"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.totalTransactionCount").value(2))
			.andExpect(jsonPath("$.data.period.startDate").value("2026-06-18"));
	}
}
