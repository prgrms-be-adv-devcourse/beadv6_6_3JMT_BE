package com.prompthub.paymentservice.presentation;

import com.prompthub.exception.BusinessException;
import com.prompthub.paymentservice.application.dto.result.PaymentResult;
import com.prompthub.paymentservice.application.exception.PaymentErrorCode;
import com.prompthub.paymentservice.application.usecase.ConfirmPaymentUseCase;
import com.prompthub.paymentservice.application.usecase.RefundPaymentUseCase;
import com.prompthub.paymentservice.presentation.dto.request.ConfirmPaymentRequest;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;
import tools.jackson.databind.ObjectMapper;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class PaymentControllerTest {

    @Mock
    ConfirmPaymentUseCase confirmPaymentUseCase;
    @Mock
    RefundPaymentUseCase refundPaymentUseCase;

    MockMvc mockMvc;
    ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        LocalValidatorFactoryBean validator = new LocalValidatorFactoryBean();
        validator.afterPropertiesSet();
        mockMvc = MockMvcBuilders.standaloneSetup(new PaymentController(confirmPaymentUseCase, refundPaymentUseCase))
            .setControllerAdvice(new PaymentExceptionHandler())
            .setValidator(validator)
            .build();
    }

    @Test
    void 결제_승인_성공() throws Exception {
        UUID paymentId = UUID.randomUUID();
        when(confirmPaymentUseCase.confirm(any())).thenReturn(new PaymentResult(paymentId));

        mockMvc.perform(post("/api/v1/payments/confirm")
                .header("X-User-Id", UUID.randomUUID().toString())
                .header("X-User-Role", "BUYER")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(
                    new ConfirmPaymentRequest("toss-key", UUID.randomUUID(), 10_000)
                )))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.success").value(true))
            .andExpect(jsonPath("$.data.paymentId").value(paymentId.toString()));
    }

    @Test
    void paymentKey_누락_시_400_V001() throws Exception {
        mockMvc.perform(post("/api/v1/payments/confirm")
                .header("X-User-Id", UUID.randomUUID().toString())
                .header("X-User-Role", "BUYER")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"paymentKey\":\"\",\"orderId\":\"" + UUID.randomUUID() + "\",\"amount\":10000}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.success").value(false))
            .andExpect(jsonPath("$.code").value("V001"));
    }

    @Test
    void amount_음수_시_400_V001() throws Exception {
        mockMvc.perform(post("/api/v1/payments/confirm")
                .header("X-User-Id", UUID.randomUUID().toString())
                .header("X-User-Role", "BUYER")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"paymentKey\":\"key\",\"orderId\":\"" + UUID.randomUUID() + "\",\"amount\":-1}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("V001"));
    }

    @Test
    void 중복_결제_시_409_PAY002() throws Exception {
        when(confirmPaymentUseCase.confirm(any()))
            .thenThrow(new BusinessException(PaymentErrorCode.DUPLICATE_PAYMENT));

        mockMvc.perform(post("/api/v1/payments/confirm")
                .header("X-User-Id", UUID.randomUUID().toString())
                .header("X-User-Role", "BUYER")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(
                    new ConfirmPaymentRequest("toss-key", UUID.randomUUID(), 10_000)
                )))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.success").value(false))
            .andExpect(jsonPath("$.code").value("PAY002"));
    }

    @Test
    void PG사_결제_실패_시_422_PAY_FAILED() throws Exception {
        when(confirmPaymentUseCase.confirm(any()))
            .thenThrow(new BusinessException(PaymentErrorCode.PAYMENT_FAILED, "카드 한도 초과"));

        mockMvc.perform(post("/api/v1/payments/confirm")
                .header("X-User-Id", UUID.randomUUID().toString())
                .header("X-User-Role", "BUYER")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(
                    new ConfirmPaymentRequest("toss-key", UUID.randomUUID(), 10_000)
                )))
            .andExpect(status().isUnprocessableEntity())
            .andExpect(jsonPath("$.code").value("PAY_FAILED"));
    }

    @Test
    void BUYER_역할_없으면_결제승인_403_PAY007() throws Exception {
        mockMvc.perform(post("/api/v1/payments/confirm")
                .header("X-User-Id", UUID.randomUUID().toString())
                .header("X-User-Role", "ADMIN")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(
                    new ConfirmPaymentRequest("toss-key", UUID.randomUUID(), 10_000)
                )))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.code").value("PAY007"));
    }

    @Test
    void BUYER_역할_없으면_환불_403_PAY007() throws Exception {
        mockMvc.perform(post("/api/v1/payments/{paymentId}/refund", UUID.randomUUID())
                .header("X-User-Id", UUID.randomUUID().toString())
                .header("X-User-Role", "ADMIN"))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.code").value("PAY007"));
    }

    @Test
    void PAID_아닌_상태_환불_시_400_PAY004() throws Exception {
        doThrow(new BusinessException(PaymentErrorCode.REFUND_NOT_ALLOWED))
            .when(refundPaymentUseCase).refund(any());

        mockMvc.perform(post("/api/v1/payments/{paymentId}/refund", UUID.randomUUID())
                .header("X-User-Id", UUID.randomUUID().toString())
                .header("X-User-Role", "BUYER"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.success").value(false))
            .andExpect(jsonPath("$.code").value("PAY004"));
    }
}
