package com.prompthub.paymentservice.interfaces.web;

import com.prompthub.exception.BusinessException;
import com.prompthub.paymentservice.application.dto.result.PaymentResult;
import com.prompthub.paymentservice.application.exception.PaymentErrorCode;
import com.prompthub.paymentservice.application.usecase.ConfirmPaymentUseCase;
import com.prompthub.paymentservice.interfaces.web.dto.request.ConfirmPaymentRequest;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import tools.jackson.databind.ObjectMapper;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class PaymentControllerTest {

    @Mock
    ConfirmPaymentUseCase confirmPaymentUseCase;

    MockMvc mockMvc;
    ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(new PaymentController(confirmPaymentUseCase))
            .setControllerAdvice(new PaymentExceptionHandler())
            .build();
    }

    @Test
    void 결제_승인_성공() throws Exception {
        UUID paymentId = UUID.randomUUID();
        when(confirmPaymentUseCase.confirm(any())).thenReturn(new PaymentResult(paymentId));

        mockMvc.perform(post("/api/v1/payments/confirm")
                .header("X-User-Id", UUID.randomUUID().toString())
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
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(
                    new ConfirmPaymentRequest("toss-key", UUID.randomUUID(), 10_000)
                )))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.success").value(false))
            .andExpect(jsonPath("$.code").value("PAY002"));
    }

    @Test
    void PG사_결제_실패_시_400_PAY_FAILED() throws Exception {
        when(confirmPaymentUseCase.confirm(any()))
            .thenThrow(new BusinessException(PaymentErrorCode.PAYMENT_FAILED, "카드 한도 초과"));

        mockMvc.perform(post("/api/v1/payments/confirm")
                .header("X-User-Id", UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(
                    new ConfirmPaymentRequest("toss-key", UUID.randomUUID(), 10_000)
                )))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("PAY_FAILED"));
    }
}
