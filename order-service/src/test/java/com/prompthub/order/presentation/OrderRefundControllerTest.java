package com.prompthub.order.presentation;

import com.prompthub.order.application.dto.CreateOrderRefundCommand;
import com.prompthub.order.application.dto.OrderRefundResult;
import com.prompthub.order.application.usecase.CreateOrderRefundUseCase;
import com.prompthub.order.domain.enums.OrderRefundStatus;
import com.prompthub.order.global.exception.ErrorCode;
import com.prompthub.order.global.exception.GlobalExceptionHandler;
import com.prompthub.order.global.exception.OrderException;
import com.prompthub.order.global.web.AuthHeaders;
import com.prompthub.order.global.web.OrderServiceAuthInterceptor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class OrderRefundControllerTest {

    private static final UUID BUYER_ID = UUID.fromString("00000000-0000-0000-0000-000000000101");
    private static final UUID ORDER_ID = UUID.fromString("00000000-0000-0000-0000-000000000201");
    private static final UUID PAYMENT_ID = UUID.fromString("00000000-0000-0000-0000-000000000301");
    private static final UUID ORDER_PRODUCT_ID_1 = UUID.fromString("00000000-0000-0000-0000-000000000401");
    private static final UUID ORDER_PRODUCT_ID_2 = UUID.fromString("00000000-0000-0000-0000-000000000402");
    private static final UUID REFUND_REQUEST_ID = UUID.fromString("00000000-0000-0000-0000-000000000501");
    private static final int EXPECTED_TOTAL = 15_000;
    private static final LocalDateTime REQUESTED_AT = LocalDateTime.of(2026, 7, 14, 12, 30);

    private MockMvc mockMvc;

    @Mock
    private CreateOrderRefundUseCase createOrderRefundUseCase;

    @BeforeEach
    void setUp() {
        LocalValidatorFactoryBean validator = new LocalValidatorFactoryBean();
        validator.afterPropertiesSet();

        mockMvc = MockMvcBuilders.standaloneSetup(new OrderRefundController(createOrderRefundUseCase))
            .setControllerAdvice(new GlobalExceptionHandler())
            .addInterceptors(new OrderServiceAuthInterceptor())
            .setValidator(validator)
            .build();
    }

    @Test
    @DisplayName("다건 부분 환불 요청을 접수하고 정확한 커맨드를 전달한다")
    void refundRequestedAccepted() throws Exception {
        given(createOrderRefundUseCase.create(any())).willReturn(result(OrderRefundStatus.REQUESTED));

        mockMvc.perform(validRequest())
            .andExpect(status().isAccepted())
            .andExpect(jsonPath("$.success").value(true))
            .andExpect(jsonPath("$.data.refundRequestId").value(REFUND_REQUEST_ID.toString()))
            .andExpect(jsonPath("$.data.orderId").value(ORDER_ID.toString()))
            .andExpect(jsonPath("$.data.paymentId").value(PAYMENT_ID.toString()))
            .andExpect(jsonPath("$.data.orderProductIds[0]").value(ORDER_PRODUCT_ID_1.toString()))
            .andExpect(jsonPath("$.data.orderProductIds[1]").value(ORDER_PRODUCT_ID_2.toString()))
            .andExpect(jsonPath("$.data.totalRefundAmount").value(EXPECTED_TOTAL))
            .andExpect(jsonPath("$.data.status").value("REQUESTED"))
            .andExpect(jsonPath("$.data.requestedAt").value("2026-07-14T12:30:00"));

        ArgumentCaptor<CreateOrderRefundCommand> captor = ArgumentCaptor.forClass(CreateOrderRefundCommand.class);
        then(createOrderRefundUseCase).should().create(captor.capture());
        assertThat(captor.getValue()).isEqualTo(new CreateOrderRefundCommand(
            BUYER_ID,
            ORDER_ID,
            PAYMENT_ID,
            List.of(ORDER_PRODUCT_ID_1, ORDER_PRODUCT_ID_2)
        ));
    }

    @Test
    @DisplayName("진행 중인 동일 환불 요청은 202를 반환한다")
    void activeDuplicateAccepted() throws Exception {
        given(createOrderRefundUseCase.create(any())).willReturn(result(OrderRefundStatus.PROCESSING));

        mockMvc.perform(validRequest())
            .andExpect(status().isAccepted())
            .andExpect(jsonPath("$.data.status").value("PROCESSING"));
    }

    @Test
    @DisplayName("완료된 동일 환불 요청은 200을 반환한다")
    void completedDuplicateOk() throws Exception {
        given(createOrderRefundUseCase.create(any())).willReturn(result(OrderRefundStatus.COMPLETED));

        mockMvc.perform(validRequest())
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.status").value("COMPLETED"));
    }

    @Test
    @DisplayName("결과 확인 중인 환불 상태가 누출되면 O022를 반환한다")
    void unknownResultConflict() throws Exception {
        given(createOrderRefundUseCase.create(any())).willReturn(result(OrderRefundStatus.UNKNOWN));

        mockMvc.perform(validRequest())
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.success").value(false))
            .andExpect(jsonPath("$.code").value(ErrorCode.ORDER_REFUND_RESULT_UNKNOWN.getCode()));
    }

    @Test
    @DisplayName("실패 환불 결과가 컨트롤러까지 누출되면 내부 계약 오류를 반환한다")
    void failedResultInternalServerError() throws Exception {
        given(createOrderRefundUseCase.create(any())).willReturn(result(OrderRefundStatus.FAILED));

        mockMvc.perform(validRequest())
            .andExpect(status().isInternalServerError())
            .andExpect(jsonPath("$.success").value(false))
            .andExpect(jsonPath("$.code").value(ErrorCode.INTERNAL_SERVER_ERROR.getCode()));
    }

    @Test
    @DisplayName("인증 헤더가 없으면 401을 반환한다")
    void missingHeadersUnauthorized() throws Exception {
        mockMvc.perform(post("/api/v2/orders/{orderId}/refund", ORDER_ID)
                .contentType(MediaType.APPLICATION_JSON)
                .content(validBody()))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.code").value(ErrorCode.INVALID_AUTHENTICATION.getCode()));

        then(createOrderRefundUseCase).shouldHaveNoInteractions();
    }

    @Test
    @DisplayName("BUYER가 아닌 역할이면 403을 반환한다")
    void wrongRoleForbidden() throws Exception {
        mockMvc.perform(post("/api/v2/orders/{orderId}/refund", ORDER_ID)
                .header(AuthHeaders.USER_ID, BUYER_ID)
                .header(AuthHeaders.USER_ROLE, AuthHeaders.ADMIN)
                .contentType(MediaType.APPLICATION_JSON)
                .content(validBody()))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.code").value(ErrorCode.FORBIDDEN.getCode()));

        then(createOrderRefundUseCase).shouldHaveNoInteractions();
    }

    @Test
    @DisplayName("주문상품 목록이 비어 있으면 400을 반환한다")
    void emptyOrderProductIdsBadRequest() throws Exception {
        mockMvc.perform(authorisedRequest("""
                {"payment_id":"%s","order_product_ids":[]}
                """.formatted(PAYMENT_ID)))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value(ErrorCode.INVALID_INPUT_VALUE.getCode()));

        then(createOrderRefundUseCase).shouldHaveNoInteractions();
    }

    @Test
    @DisplayName("UUID 형식이 올바르지 않으면 400을 반환한다")
    void invalidUuidBadRequest() throws Exception {
        mockMvc.perform(authorisedRequest("""
                {"payment_id":"not-a-uuid","order_product_ids":["%s"]}
                """.formatted(ORDER_PRODUCT_ID_1)))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value(ErrorCode.INVALID_INPUT_VALUE.getCode()));

        then(createOrderRefundUseCase).shouldHaveNoInteractions();
    }

    @Test
    @DisplayName("허용하지 않은 요청 필드가 있으면 400을 반환한다")
    void unknownPropertyBadRequest() throws Exception {
        mockMvc.perform(authorisedRequest("""
                {"payment_id":"%s","order_product_ids":["%s"],"buyer_id":"%s"}
                """.formatted(PAYMENT_ID, ORDER_PRODUCT_ID_1, BUYER_ID)))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value(ErrorCode.INVALID_INPUT_VALUE.getCode()));

        then(createOrderRefundUseCase).shouldHaveNoInteractions();
    }

    @Test
    @DisplayName("다른 구매자의 주문이면 도메인 403을 반환한다")
    void domainForbidden() throws Exception {
        assertDomainError(ErrorCode.ORDER_ACCESS_DENIED);
    }

    @Test
    @DisplayName("주문을 찾을 수 없으면 도메인 404를 반환한다")
    void domainNotFound() throws Exception {
        assertDomainError(ErrorCode.ORDER_NOT_FOUND);
    }

    @Test
    @DisplayName("환불 요청이 현재 상태와 충돌하면 도메인 409를 반환한다")
    void domainConflict() throws Exception {
        assertDomainError(ErrorCode.ORDER_REFUND_IN_PROGRESS);
    }

    private void assertDomainError(ErrorCode errorCode) throws Exception {
        given(createOrderRefundUseCase.create(any())).willThrow(new OrderException(errorCode));

        mockMvc.perform(validRequest())
            .andExpect(status().is(errorCode.getStatus().value()))
            .andExpect(jsonPath("$.success").value(false))
            .andExpect(jsonPath("$.code").value(errorCode.getCode()));
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder validRequest() {
        return authorisedRequest(validBody());
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder authorisedRequest(String body) {
        return post("/api/v2/orders/{orderId}/refund", ORDER_ID)
            .header(AuthHeaders.USER_ID, BUYER_ID)
            .header(AuthHeaders.USER_ROLE, AuthHeaders.BUYER)
            .contentType(MediaType.APPLICATION_JSON)
            .content(body);
    }

    private String validBody() {
        return """
            {"payment_id":"%s","order_product_ids":["%s","%s"]}
            """.formatted(PAYMENT_ID, ORDER_PRODUCT_ID_1, ORDER_PRODUCT_ID_2);
    }

    private OrderRefundResult result(OrderRefundStatus status) {
        return new OrderRefundResult(
            REFUND_REQUEST_ID,
            ORDER_ID,
            PAYMENT_ID,
            List.of(ORDER_PRODUCT_ID_1, ORDER_PRODUCT_ID_2),
            EXPECTED_TOTAL,
            status,
            REQUESTED_AT
        );
    }
}
