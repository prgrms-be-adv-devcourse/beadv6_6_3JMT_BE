package com.prompthub.order.presentation;

import com.prompthub.order.application.dto.CreateOrderCommand;
import com.prompthub.order.application.usecase.ConfirmDownloadUseCase;
import com.prompthub.order.application.usecase.CreateOrderUseCase;
import com.prompthub.order.application.usecase.OrderQueryUseCase;
import com.prompthub.order.application.service.refund.OrderRefundService;
import com.prompthub.order.global.exception.ErrorCode;
import com.prompthub.order.global.exception.GlobalExceptionHandler;
import com.prompthub.order.global.web.AuthHeaders;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;

import java.util.stream.Stream;

import static com.prompthub.order.fixture.OrderV2Fixture.AMOUNT_A1;
import static com.prompthub.order.fixture.OrderV2Fixture.BUYER_ID;
import static com.prompthub.order.fixture.OrderV2Fixture.ORDER_A;
import static com.prompthub.order.fixture.OrderV2Fixture.PRODUCT_A2;
import static com.prompthub.order.fixture.OrderV2Fixture.PRODUCT_B1;
import static com.prompthub.order.fixture.OrderV2Fixture.PRODUCT_C1;
import static com.prompthub.order.fixture.OrderV2Fixture.PRODUCT_A1;
import static com.prompthub.order.fixture.OrderV2Fixture.REQUEST_TITLE_A1;
import static com.prompthub.order.fixture.OrderV2Fixture.SELLER_A;
import static com.prompthub.order.fixture.OrderV2Fixture.SELLER_B;
import static com.prompthub.order.fixture.OrderV2Fixture.SELLER_C;
import static com.prompthub.order.fixture.OrderV2Fixture.TOTAL_AMOUNT;
import static com.prompthub.order.fixture.OrderV2Fixture.requestJson;
import static com.prompthub.order.fixture.OrderV2Fixture.result;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class OrderControllerCreateTest {

	private MockMvc mockMvc;

	@Mock
	private CreateOrderUseCase createOrderUseCase;

	@Mock
	private ConfirmDownloadUseCase confirmDownloadUseCase;

	@Mock
	private OrderQueryUseCase orderQueryUseCase;

	@Mock
	private OrderRefundService orderRefundService;

	@BeforeEach
	void setUp() {
		LocalValidatorFactoryBean validator = new LocalValidatorFactoryBean();
		validator.afterPropertiesSet();

		mockMvc = MockMvcBuilders.standaloneSetup(new OrderController(
			confirmDownloadUseCase,
			orderQueryUseCase,
			createOrderUseCase,
			orderRefundService
		))
			.setControllerAdvice(new GlobalExceptionHandler())
			.setValidator(validator)
			.build();
	}

	@Test
	@DisplayName("POST /api/v2/orders는 총액과 생성된 단일 주문을 반환한다")
	void createOrderReturnsSingleOrder() throws Exception {
		given(createOrderUseCase.createOrder(eq(BUYER_ID), any(CreateOrderCommand.class)))
			.willReturn(result());

		mockMvc.perform(post("/api/v2/orders")
				.header(AuthHeaders.USER_ID, BUYER_ID.toString())
				.contentType(MediaType.APPLICATION_JSON)
				.content(requestJson()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.success").value(true))
			.andExpect(jsonPath("$.message").value("success"))
			.andExpect(jsonPath("$.data.totalAmount").value(TOTAL_AMOUNT))
			.andExpect(jsonPath("$.data.order.orderId").value(ORDER_A.toString()))
			.andExpect(jsonPath("$.data.order.orderStatus").value("CREATED"))
			.andExpect(jsonPath("$.data.order.orderAmount").value(TOTAL_AMOUNT))
			.andExpect(jsonPath("$.data.order.products.length()").value(4))
			.andExpect(jsonPath("$.data.order.products[0].productId").value(PRODUCT_A1.toString()))
			.andExpect(jsonPath("$.data.order.products[0].sellerId").value(SELLER_A.toString()))
			.andExpect(jsonPath("$.data.order.products[0].productTitle").value(REQUEST_TITLE_A1))
			.andExpect(jsonPath("$.data.order.products[0].productAmount").value(AMOUNT_A1))
			.andExpect(jsonPath("$.data.order.products[0].orderProductStatus").value("PENDING"))
			.andExpect(jsonPath("$.data.order.products[1].productId").value(PRODUCT_B1.toString()))
			.andExpect(jsonPath("$.data.order.products[1].sellerId").value(SELLER_B.toString()))
			.andExpect(jsonPath("$.data.order.products[2].productId").value(PRODUCT_A2.toString()))
			.andExpect(jsonPath("$.data.order.products[2].sellerId").value(SELLER_A.toString()))
			.andExpect(jsonPath("$.data.order.products[3].productId").value(PRODUCT_C1.toString()))
			.andExpect(jsonPath("$.data.order.products[3].sellerId").value(SELLER_C.toString()))
			.andExpect(jsonPath("$.data.orders").doesNotExist());

		ArgumentCaptor<CreateOrderCommand> commandCaptor = ArgumentCaptor.forClass(CreateOrderCommand.class);
		then(createOrderUseCase).should().createOrder(eq(BUYER_ID), commandCaptor.capture());
		assertThat(commandCaptor.getValue().products())
			.extracting(CreateOrderCommand.Product::productId)
			.containsExactly(
				com.prompthub.order.fixture.OrderV2Fixture.PRODUCT_A1,
				com.prompthub.order.fixture.OrderV2Fixture.PRODUCT_B1,
				com.prompthub.order.fixture.OrderV2Fixture.PRODUCT_A2,
				com.prompthub.order.fixture.OrderV2Fixture.PRODUCT_C1
			);
	}

	@Nested
	@DisplayName("구매자 식별자")
	class BuyerIdentity {

		@Test
		@DisplayName("X-User-Id가 없으면 401을 반환한다")
		void missingUserIdReturnsUnauthorized() throws Exception {
			mockMvc.perform(post("/api/v2/orders")
					.contentType(MediaType.APPLICATION_JSON)
					.content(requestJson()))
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.code").value(ErrorCode.INVALID_AUTHENTICATION.getCode()));

			verifyNoInteractions(createOrderUseCase);
		}
	}

	@ParameterizedTest(name = "{0}")
	@MethodSource("invalidBodies")
	@DisplayName("유효하지 않은 상품 요청은 V001을 반환한다")
	void invalidRequestReturnsV001(String description, String body) throws Exception {
		mockMvc.perform(post("/api/v2/orders")
				.header(AuthHeaders.USER_ID, BUYER_ID.toString())
				.contentType(MediaType.APPLICATION_JSON)
				.content(body))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value(ErrorCode.INVALID_INPUT_VALUE.getCode()));

		verifyNoInteractions(createOrderUseCase);
	}

	@Test
	@DisplayName("제목 200자는 허용한다")
	void titleOfTwoHundredCharactersIsAccepted() throws Exception {
		given(createOrderUseCase.createOrder(eq(BUYER_ID), any(CreateOrderCommand.class)))
			.willReturn(result());
		String title = "가".repeat(200);
		String body = """
			{"products":[{"productId":"%s","productTitle":"%s"}]}
			""".formatted(PRODUCT_A1, title);

		mockMvc.perform(post("/api/v2/orders")
				.header(AuthHeaders.USER_ID, BUYER_ID.toString())
				.contentType(MediaType.APPLICATION_JSON)
				.content(body))
			.andExpect(status().isOk());
	}

	@Test
	@DisplayName("요청 본문이 없으면 400을 반환한다")
	void missingBodyReturnsBadRequest() throws Exception {
		mockMvc.perform(post("/api/v2/orders")
				.header(AuthHeaders.USER_ID, BUYER_ID.toString())
				.contentType(MediaType.APPLICATION_JSON))
			.andExpect(status().isBadRequest());

		verifyNoInteractions(createOrderUseCase);
	}

	private static Stream<org.junit.jupiter.params.provider.Arguments> invalidBodies() {
		String longTitle = "가".repeat(201);
		return Stream.of(
			org.junit.jupiter.params.provider.Arguments.of("products null", "{\"products\":null}"),
			org.junit.jupiter.params.provider.Arguments.of("products empty", "{\"products\":[]}"),
			org.junit.jupiter.params.provider.Arguments.of("product item null", "{\"products\":[null]}"),
			org.junit.jupiter.params.provider.Arguments.of(
				"productId null",
				"{\"products\":[{\"productId\":null,\"productTitle\":\"제목\"}]}"
			),
			org.junit.jupiter.params.provider.Arguments.of(
				"title null",
				"{\"products\":[{\"productId\":\"%s\",\"productTitle\":null}]}".formatted(PRODUCT_A1)
			),
			org.junit.jupiter.params.provider.Arguments.of(
				"title blank",
				"{\"products\":[{\"productId\":\"%s\",\"productTitle\":\"   \"}]}".formatted(PRODUCT_A1)
			),
			org.junit.jupiter.params.provider.Arguments.of(
				"title too long",
				"{\"products\":[{\"productId\":\"%s\",\"productTitle\":\"%s\"}]}"
					.formatted(PRODUCT_A1, longTitle)
			)
		);
	}
}
