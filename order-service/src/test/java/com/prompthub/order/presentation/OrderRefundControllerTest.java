package com.prompthub.order.presentation;

import com.prompthub.order.application.usecase.OrderRefundUseCase;
import com.prompthub.order.global.exception.ErrorCode;
import com.prompthub.order.global.exception.GlobalExceptionHandler;
import com.prompthub.order.global.exception.OrderException;
import com.prompthub.order.global.web.AuthHeaders;
import com.prompthub.order.global.web.OrderServiceAuthInterceptor;
import com.prompthub.order.presentation.dto.request.RefundOrderRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;
import tools.jackson.databind.ObjectMapper;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.then;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class OrderRefundControllerTest {

	private static final UUID BUYER_ID =
		UUID.fromString("00000000-0000-0000-0000-000000000001");
	private static final UUID ORDER_ID =
		UUID.fromString("00000000-0000-0000-0000-000000000501");
	private static final UUID PAYMENT_ID =
		UUID.fromString("00000000-0000-0000-0000-000000000401");
	private static final UUID ORDER_PRODUCT_ID =
		UUID.fromString("00000000-0000-0000-0000-000000000601");

	private final ObjectMapper objectMapper = new ObjectMapper();

	@Mock
	private OrderRefundUseCase orderRefundUseCase;

	private MockMvc mockMvc;

	@BeforeEach
	void setUp() {
		LocalValidatorFactoryBean validator = new LocalValidatorFactoryBean();
		validator.afterPropertiesSet();

		mockMvc = MockMvcBuilders.standaloneSetup(new OrderRefundController(orderRefundUseCase))
			.setControllerAdvice(new GlobalExceptionHandler())
			.addInterceptors(new OrderServiceAuthInterceptor())
			.setValidator(validator)
			.build();
	}

	@Test
	@DisplayName("단건 환불 요청을 접수하면 202와 빈 body를 반환한다")
	void requestRefund_validRequest_accepted() throws Exception {
		RefundOrderRequest request = new RefundOrderRequest(PAYMENT_ID, ORDER_PRODUCT_ID);
		assertThat(RefundOrderRequest.class.getRecordComponents())
			.extracting(component -> component.getName())
			.containsExactly("paymentId", "orderProductId");

		mockMvc.perform(post("/api/v2/orders/{orderId}/refund", ORDER_ID)
				.header(AuthHeaders.USER_ID, BUYER_ID.toString())
				.header(AuthHeaders.USER_ROLE, AuthHeaders.BUYER)
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(request)))
			.andExpect(status().isAccepted())
			.andExpect(content().string(""));

		then(orderRefundUseCase).should()
			.requestRefund(BUYER_ID, ORDER_ID, PAYMENT_ID, ORDER_PRODUCT_ID);
	}

	@Test
	@DisplayName("로컬 주문 결제가 없으면 404와 O016을 반환한다")
	void requestRefund_paymentNotFound_notFound() throws Exception {
		RefundOrderRequest request = new RefundOrderRequest(PAYMENT_ID, ORDER_PRODUCT_ID);
		willThrow(new OrderException(ErrorCode.ORDER_PAYMENT_NOT_FOUND))
			.given(orderRefundUseCase)
			.requestRefund(BUYER_ID, ORDER_ID, PAYMENT_ID, ORDER_PRODUCT_ID);

		perform(request)
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.code").value(ErrorCode.ORDER_PAYMENT_NOT_FOUND.getCode()));
	}

	@Test
	@DisplayName("paymentId가 없으면 400과 V001을 반환한다")
	void requestRefund_missingPaymentId_badRequest() throws Exception {
		RefundOrderRequest request = new RefundOrderRequest(null, ORDER_PRODUCT_ID);

		perform(request)
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value(ErrorCode.INVALID_INPUT_VALUE.getCode()));

		verifyNoInteractions(orderRefundUseCase);
	}

	@Test
	@DisplayName("orderProductId가 없으면 400과 V001을 반환한다")
	void requestRefund_missingOrderProductId_badRequest() throws Exception {
		RefundOrderRequest request = new RefundOrderRequest(PAYMENT_ID, null);

		perform(request)
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value(ErrorCode.INVALID_INPUT_VALUE.getCode()));

		verifyNoInteractions(orderRefundUseCase);
	}

	@Test
	@DisplayName("구매자 인증 헤더가 없으면 401과 A003을 반환한다")
	void requestRefund_missingAuthentication_unauthorized() throws Exception {
		RefundOrderRequest request = new RefundOrderRequest(PAYMENT_ID, ORDER_PRODUCT_ID);

		mockMvc.perform(post("/api/v2/orders/{orderId}/refund", ORDER_ID)
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(request)))
			.andExpect(status().isUnauthorized())
			.andExpect(jsonPath("$.code").value(ErrorCode.INVALID_AUTHENTICATION.getCode()));

		verifyNoInteractions(orderRefundUseCase);
	}

	@Test
	@DisplayName("BUYER가 아닌 역할이면 403과 A004를 반환한다")
	void requestRefund_nonBuyerRole_forbidden() throws Exception {
		RefundOrderRequest request = new RefundOrderRequest(PAYMENT_ID, ORDER_PRODUCT_ID);

		mockMvc.perform(post("/api/v2/orders/{orderId}/refund", ORDER_ID)
				.header(AuthHeaders.USER_ID, BUYER_ID.toString())
				.header(AuthHeaders.USER_ROLE, AuthHeaders.SELLER)
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(request)))
			.andExpect(status().isForbidden())
			.andExpect(jsonPath("$.code").value(ErrorCode.FORBIDDEN.getCode()));

		verifyNoInteractions(orderRefundUseCase);
	}

	@Test
	@DisplayName("진행 중인 환불이 있으면 409와 O018을 반환한다")
	void requestRefund_inProgress_conflict() throws Exception {
		RefundOrderRequest request = new RefundOrderRequest(PAYMENT_ID, ORDER_PRODUCT_ID);
		willThrow(new OrderException(ErrorCode.ORDER_REFUND_IN_PROGRESS))
			.given(orderRefundUseCase)
			.requestRefund(BUYER_ID, ORDER_ID, PAYMENT_ID, ORDER_PRODUCT_ID);

		perform(request)
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value(ErrorCode.ORDER_REFUND_IN_PROGRESS.getCode()));
	}

	@Test
	@DisplayName("낙관적 잠금 충돌은 409와 O019로 변환한다")
	void requestRefund_optimisticLockConflict_conflict() throws Exception {
		RefundOrderRequest request = new RefundOrderRequest(PAYMENT_ID, ORDER_PRODUCT_ID);
		willThrow(new ObjectOptimisticLockingFailureException(OrderRefundController.class, ORDER_ID))
			.given(orderRefundUseCase)
			.requestRefund(BUYER_ID, ORDER_ID, PAYMENT_ID, ORDER_PRODUCT_ID);

		perform(request)
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value(ErrorCode.ORDER_CONCURRENT_MODIFICATION.getCode()));
	}

	private org.springframework.test.web.servlet.ResultActions perform(RefundOrderRequest request) throws Exception {
		return mockMvc.perform(post("/api/v2/orders/{orderId}/refund", ORDER_ID)
			.header(AuthHeaders.USER_ID, BUYER_ID.toString())
			.header(AuthHeaders.USER_ROLE, AuthHeaders.BUYER)
			.contentType(MediaType.APPLICATION_JSON)
			.content(objectMapper.writeValueAsString(request)));
	}
}
