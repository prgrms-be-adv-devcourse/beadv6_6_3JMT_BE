package com.prompthub.order.global.web;

import com.prompthub.order.application.dto.CreateOrderCommand;
import com.prompthub.order.application.usecase.AdminOrderUseCase;
import com.prompthub.order.application.usecase.CartUseCase;
import com.prompthub.order.application.usecase.ConfirmDownloadUseCase;
import com.prompthub.order.application.usecase.CreateOrderUseCase;
import com.prompthub.order.application.usecase.OrderQueryUseCase;
import com.prompthub.order.global.exception.ErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.data.domain.PageImpl;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static com.prompthub.order.fixture.OrderV2Fixture.BUYER_ID;
import static com.prompthub.order.fixture.OrderV2Fixture.requestJson;
import static com.prompthub.order.fixture.OrderV2Fixture.result;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
class OrderV2WebConfigTest {

	@Autowired
	private MockMvc mockMvc;

	@MockitoBean
	private AdminOrderUseCase adminOrderUseCase;

	@MockitoBean
	private CreateOrderUseCase createOrderUseCase;

	@MockitoBean
	private ConfirmDownloadUseCase confirmDownloadUseCase;

	@MockitoBean
	private OrderQueryUseCase orderQueryUseCase;

	@MockitoBean
	private CartUseCase cartUseCase;

	@Test
	@DisplayName("실제 WebConfig는 /api/v2/orders 요청에 구매자 인증을 적용한다")
	void v2OrderPathRequiresBuyerAuthentication() throws Exception {
		mockMvc.perform(post("/api/v2/orders")
				.contentType(MediaType.APPLICATION_JSON)
				.content(requestJson()))
			.andExpect(status().isUnauthorized())
			.andExpect(jsonPath("$.code").value(ErrorCode.INVALID_AUTHENTICATION.getCode()));

		then(createOrderUseCase).shouldHaveNoInteractions();
	}

	@Test
	@DisplayName("BUYER 인증을 통과한 v2 요청은 주문 생성 유스케이스에 도달한다")
	void authenticatedV2OrderPathReachesUseCase() throws Exception {
		given(createOrderUseCase.createOrder(eq(BUYER_ID), any(CreateOrderCommand.class)))
			.willReturn(result());

		mockMvc.perform(post("/api/v2/orders")
				.header(AuthHeaders.USER_ID, BUYER_ID.toString())
				.header(AuthHeaders.USER_ROLE, AuthHeaders.BUYER)
				.contentType(MediaType.APPLICATION_JSON)
				.content(requestJson()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.success").value(true));

		then(createOrderUseCase).should().createOrder(eq(BUYER_ID), any(CreateOrderCommand.class));
	}

	@Test
	@DisplayName("v1 주문 생성 POST는 노출하지 않는다")
	void v1CreateOrderIsNotExposed() throws Exception {
		mockMvc.perform(post("/api/v1/orders")
				.header(AuthHeaders.USER_ID, BUYER_ID.toString())
				.header(AuthHeaders.USER_ROLE, AuthHeaders.BUYER)
				.contentType(MediaType.APPLICATION_JSON)
				.content(requestJson()))
			.andExpect(status().isMethodNotAllowed());

		then(createOrderUseCase).shouldHaveNoInteractions();
	}

	@Test
	@DisplayName("기존 v1 주문 조회 API는 유지한다")
	void v1OrderQueryRemainsAvailable() throws Exception {
		given(orderQueryUseCase.getOrders(eq(BUYER_ID), any()))
			.willReturn(new PageImpl<>(List.of()));

		mockMvc.perform(get("/api/v1/orders")
				.header(AuthHeaders.USER_ID, BUYER_ID.toString())
				.header(AuthHeaders.USER_ROLE, AuthHeaders.BUYER))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.success").value(true));
	}
}
