package com.prompthub.order.presentation;

import com.prompthub.order.application.usecase.OrderUseCase;
import com.prompthub.order.domain.enums.OrderStatus;
import com.prompthub.order.presentation.dto.request.CreateOrderRequest;
import com.prompthub.order.presentation.dto.response.CreateOrderResponse;
import com.prompthub.order.presentation.dto.response.OrderProductsResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;
import tools.jackson.databind.ObjectMapper;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static com.prompthub.order.fixture.OrderFixture.*;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class OrderControllerTest {

	private MockMvc mockMvc;

	private final ObjectMapper objectMapper = new ObjectMapper();

	@Mock
	private OrderUseCase orderUseCase;

	@BeforeEach
	void setUp() {
		LocalValidatorFactoryBean validator = new LocalValidatorFactoryBean();
		validator.afterPropertiesSet();

		mockMvc = MockMvcBuilders.standaloneSetup(new OrderController(orderUseCase))
			.setValidator(validator)
			.build();
	}

	@Test
	@DisplayName("주문 생성 성공")
	void createOrder_success() throws Exception {
		// given
		UUID orderId = UUID.fromString("33333333-3333-3333-3333-333333333333");
		UUID orderProductId1 = UUID.fromString("44444444-4444-4444-4444-444444444441");
		UUID orderProductId2 = UUID.fromString("44444444-4444-4444-4444-444444444442");

		CreateOrderRequest request = createOrderRequest();

		OrderProductsResponse productResponse1 = new OrderProductsResponse(
			orderProductId1,
			PRODUCT_ID_1,
			SELLER_ID_1,
			PRODUCT_TITLE_1,
			PRODUCT_TYPE_PROMPT,
			PRODUCT_AMOUNT_1,
			OrderStatus.PENDING
		);
		OrderProductsResponse productResponse2 = new OrderProductsResponse(
			orderProductId2,
			PRODUCT_ID_2,
			SELLER_ID_2,
			PRODUCT_TITLE_2,
			PRODUCT_TYPE_PROMPT,
			PRODUCT_AMOUNT_2,
			OrderStatus.PENDING
		);

		CreateOrderResponse response = new CreateOrderResponse(
			orderId,
			ORDER_NUMBER,
			BUYER_ID,
			OrderStatus.PENDING,
			List.of(productResponse1, productResponse2),
			TOTAL_AMOUNT,
			LocalDateTime.of(2026, 6, 19, 10, 0),
			null
		);

		when(orderUseCase.createOrder(eq(BUYER_ID), eq(request)))
			.thenReturn(response);

		// when & then
		mockMvc.perform(post("/api/v1/orders")
				.header("X-User-Id", BUYER_ID.toString())
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(request)))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.success").value(true))
			.andExpect(jsonPath("$.message").value("success"))
			.andExpect(jsonPath("$.data.orderId").value(orderId.toString()))
			.andExpect(jsonPath("$.data.orderNumber").value(ORDER_NUMBER))
			.andExpect(jsonPath("$.data.buyerId").value(BUYER_ID.toString()))
			.andExpect(jsonPath("$.data.orderStatus").value("PENDING"))
			.andExpect(jsonPath("$.data.totalAmount").value(TOTAL_AMOUNT))
			.andExpect(jsonPath("$.data.products[0].orderProductId").value(orderProductId1.toString()))
			.andExpect(jsonPath("$.data.products[0].productId").value(PRODUCT_ID_1.toString()))
			.andExpect(jsonPath("$.data.products[0].sellerId").value(SELLER_ID_1.toString()))
			.andExpect(jsonPath("$.data.products[0].productTitleSnapshot").value(PRODUCT_TITLE_1))
			.andExpect(jsonPath("$.data.products[0].productTypeSnapshot").value(PRODUCT_TYPE_PROMPT))
			.andExpect(jsonPath("$.data.products[0].productAmountSnapshot").value(PRODUCT_AMOUNT_1))
			.andExpect(jsonPath("$.data.products[0].orderStatus").value("PENDING"))
			.andExpect(jsonPath("$.data.products[1].orderProductId").value(orderProductId2.toString()))
			.andExpect(jsonPath("$.data.products[1].productId").value(PRODUCT_ID_2.toString()))
			.andExpect(jsonPath("$.data.products[1].sellerId").value(SELLER_ID_2.toString()))
			.andExpect(jsonPath("$.data.products[1].productTitleSnapshot").value(PRODUCT_TITLE_2))
			.andExpect(jsonPath("$.data.products[1].productTypeSnapshot").value(PRODUCT_TYPE_PROMPT))
			.andExpect(jsonPath("$.data.products[1].productAmountSnapshot").value(PRODUCT_AMOUNT_2))
			.andExpect(jsonPath("$.data.products[1].orderStatus").value("PENDING"));

		verify(orderUseCase).createOrder(eq(BUYER_ID), eq(request));
	}

	@Test
	@DisplayName("X-User-Id 헤더가 없으면 400 Bad Request")
	void createOrder_withoutUserIdHeader_badRequest() throws Exception {
		// given
		CreateOrderRequest request = createOrderRequest();

		// when & then
		mockMvc.perform(post("/api/v1/orders")
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(request)))
			.andExpect(status().isBadRequest());
	}

	@Test
	@DisplayName("X-User-Id 헤더가 UUID 형식이 아니면 400 Bad Request")
	void createOrder_invalidUserIdHeader_badRequest() throws Exception {
		// given
		CreateOrderRequest request = createOrderRequest();

		// when & then
		mockMvc.perform(post("/api/v1/orders")
				.header("X-User-Id", "invalid-uuid")
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(request)))
			.andExpect(status().isBadRequest());
	}

	@Test
	@DisplayName("RequestBody가 없으면 400 Bad Request")
	void createOrder_withoutRequestBody_badRequest() throws Exception {
		// given
		// when & then
		mockMvc.perform(post("/api/v1/orders")
				.header("X-User-Id", BUYER_ID.toString())
				.contentType(MediaType.APPLICATION_JSON))
			.andExpect(status().isBadRequest());
	}

	@Test
	@DisplayName("productIds가 비어 있으면 400 Bad Request")
	void createOrder_emptyProductIds_badRequest() throws Exception {
		// given
		CreateOrderRequest request = createOrderRequestWithEmptyProductIds();

		// when & then
		mockMvc.perform(post("/api/v1/orders")
				.header("X-User-Id", BUYER_ID.toString())
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(request)))
			.andExpect(status().isBadRequest());
	}
}
