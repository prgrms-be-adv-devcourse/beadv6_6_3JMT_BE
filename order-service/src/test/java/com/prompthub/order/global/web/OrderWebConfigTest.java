package com.prompthub.order.global.web;

import com.prompthub.order.application.usecase.AdminOrderUseCase;
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
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
class OrderWebConfigTest {

	private static final UUID BUYER_ID = UUID.fromString("00000000-0000-0000-0000-000000000101");

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
	@DisplayName("BUYER 권한으로 관리자 API를 호출하면 403을 반환한다")
	void adminApiBuyerRoleForbidden() throws Exception {
		mockMvc.perform(get("/api/v1/admin/orders")
				.header(AuthHeaders.USER_ROLE, AuthHeaders.BUYER))
			.andExpect(status().isForbidden())
			.andExpect(jsonPath("$.success").value(false))
			.andExpect(jsonPath("$.code").value(ErrorCode.FORBIDDEN.getCode()));

		then(adminOrderUseCase).shouldHaveNoInteractions();
	}

	@Test
	@DisplayName("관리자 API 호출 시 권한 헤더가 없으면 401을 반환한다")
	void adminApiMissingRoleUnauthorized() throws Exception {
		mockMvc.perform(get("/api/v1/admin/orders"))
			.andExpect(status().isUnauthorized())
			.andExpect(jsonPath("$.success").value(false))
			.andExpect(jsonPath("$.code").value(ErrorCode.INVALID_AUTHENTICATION.getCode()));

		then(adminOrderUseCase).shouldHaveNoInteractions();
	}

	@Test
	@DisplayName("ADMIN 권한으로 관리자 API를 호출하면 유스케이스까지 도달한다")
	void adminApiAdminRoleReachesUseCase() throws Exception {
		given(adminOrderUseCase.getAdminOrders(any()))
			.willReturn(new PageImpl<>(List.of()));

		mockMvc.perform(get("/api/v1/admin/orders")
				.header(AuthHeaders.USER_ROLE, AuthHeaders.ADMIN))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.success").value(true));

		then(adminOrderUseCase).should().getAdminOrders(any());
	}

	@Test
	@DisplayName("주문 API 호출 시 구매자 인증 헤더가 없으면 401을 반환한다")
	void orderApiMissingBuyerHeadersUnauthorized() throws Exception {
		mockMvc.perform(get("/api/v1/orders"))
			.andExpect(status().isUnauthorized())
			.andExpect(jsonPath("$.success").value(false))
			.andExpect(jsonPath("$.code").value(ErrorCode.INVALID_AUTHENTICATION.getCode()));

		then(orderQueryUseCase).shouldHaveNoInteractions();
	}

	@Test
	@DisplayName("장바구니 API 호출 시 BUYER 권한이 없으면 403을 반환한다")
	void cartApiAdminRoleForbidden() throws Exception {
		mockMvc.perform(get("/api/v1/cart/products")
				.header(AuthHeaders.USER_ID, BUYER_ID.toString())
				.header(AuthHeaders.USER_ROLE, AuthHeaders.ADMIN))
			.andExpect(status().isForbidden())
			.andExpect(jsonPath("$.success").value(false))
			.andExpect(jsonPath("$.code").value(ErrorCode.FORBIDDEN.getCode()));

		then(cartUseCase).should(never()).getCart(any());
	}

	@Test
	@DisplayName("BUYER 권한으로 장바구니 API를 호출하면 유스케이스까지 도달한다")
	void cartApiBuyerRoleReachesUseCase() throws Exception {
		given(cartUseCase.getCart(eq(BUYER_ID)))
			.willReturn(new CartResponse(
				UUID.fromString("00000000-0000-0000-0000-000000000201"),
				BUYER_ID,
				List.of(),
				0,
				0
			));

		mockMvc.perform(get("/api/v1/cart/products")
				.header(AuthHeaders.USER_ID, BUYER_ID.toString())
				.header(AuthHeaders.USER_ROLE, AuthHeaders.BUYER))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.success").value(true));

		then(cartUseCase).should().getCart(BUYER_ID);
	}
}
