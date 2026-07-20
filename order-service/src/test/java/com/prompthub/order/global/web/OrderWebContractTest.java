package com.prompthub.order.global.web;

import com.prompthub.order.application.dto.CreateOrderCommand;
import com.prompthub.order.application.usecase.CartUseCase;
import com.prompthub.order.application.usecase.ConfirmDownloadUseCase;
import com.prompthub.order.application.usecase.CreateOrderUseCase;
import com.prompthub.order.application.usecase.OrderQueryUseCase;
import com.prompthub.order.global.exception.ErrorCode;
import com.prompthub.order.presentation.dto.response.CartResponse;
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
import java.util.UUID;

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
class OrderWebContractTest {

	@Autowired
	private MockMvc mockMvc;

	@MockitoBean
	private CreateOrderUseCase createOrderUseCase;

	@MockitoBean
	private ConfirmDownloadUseCase confirmDownloadUseCase;

	@MockitoBean
	private OrderQueryUseCase orderQueryUseCase;

	@MockitoBean
	private CartUseCase cartUseCase;

	@Test
	@DisplayName("장바구니 API는 사용자 ID만으로 유스케이스까지 도달한다")
	void cartApiWithUserIdOnlyReachesUseCase() throws Exception {
		given(cartUseCase.getCart(eq(BUYER_ID)))
			.willReturn(new CartResponse(
				UUID.fromString("00000000-0000-0000-0000-000000000201"),
				BUYER_ID,
				List.of(),
				0,
				0
			));

		mockMvc.perform(get("/api/v2/cart/products")
				.header(AuthHeaders.USER_ID, BUYER_ID.toString()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.success").value(true));

		then(cartUseCase).should().getCart(BUYER_ID);
	}

	@Test
	@DisplayName("v2 주문 생성은 사용자 ID만으로 유스케이스까지 도달한다")
	void v2OrderWithUserIdOnlyReachesUseCase() throws Exception {
		given(createOrderUseCase.createOrder(eq(BUYER_ID), any(CreateOrderCommand.class)))
			.willReturn(result());

		mockMvc.perform(post("/api/v2/orders")
				.header(AuthHeaders.USER_ID, BUYER_ID.toString())
				.contentType(MediaType.APPLICATION_JSON)
				.content(requestJson()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.success").value(true));

		then(createOrderUseCase).should().createOrder(eq(BUYER_ID), any(CreateOrderCommand.class));
	}

	@Test
	@DisplayName("주문 API는 사용자 ID가 없으면 401을 반환한다")
	void orderApiWithoutUserIdReturnsUnauthorized() throws Exception {
		mockMvc.perform(get("/api/v2/orders"))
			.andExpect(status().isUnauthorized())
			.andExpect(jsonPath("$.code").value(ErrorCode.INVALID_AUTHENTICATION.getCode()));

		then(orderQueryUseCase).shouldHaveNoInteractions();
	}


	@Test
	@DisplayName("기존 v1 주문 조회 API는 사용자 ID만으로 호출할 수 있다")
	void v1OrderQueryRemainsAvailable() throws Exception {
		given(orderQueryUseCase.getOrders(eq(BUYER_ID), any()))
			.willReturn(new PageImpl<>(List.of()));

		mockMvc.perform(get("/api/v2/orders")
				.header(AuthHeaders.USER_ID, BUYER_ID.toString()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.success").value(true));

		then(orderQueryUseCase).should().getOrders(eq(BUYER_ID), any());
	}
}
