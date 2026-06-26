package com.prompthub.paymentservice.infrastructure.external.toss;

import com.prompthub.paymentservice.application.exception.PaymentErrorCode;
import com.prompthub.paymentservice.application.gateway.external.PaymentGateway;
import com.prompthub.paymentservice.application.gateway.external.PaymentGatewayException;
import com.prompthub.paymentservice.application.gateway.external.TossConfirmResult;
import com.prompthub.paymentservice.application.gateway.external.TossRefundResult;
import com.prompthub.paymentservice.infrastructure.external.toss.dto.TossConfirmRequest;
import com.prompthub.paymentservice.infrastructure.external.toss.dto.TossConfirmResponse;
import com.prompthub.paymentservice.infrastructure.external.toss.dto.TossErrorResponse;
import com.prompthub.paymentservice.infrastructure.external.toss.dto.TossRefundRequest;
import com.prompthub.paymentservice.infrastructure.external.toss.dto.TossRefundResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.ObjectMapper;

@Component
public class TossPaymentGateway implements PaymentGateway {

    private final RestClient restClient;
    private final ObjectMapper objectMapper;

    public TossPaymentGateway(
        @Value("${payment.toss.secret-key:test-dummy-key}") String secretKey,
        @Value("${payment.toss.base-url:https://api.tosspayments.com/v1}") String baseUrl,
        ObjectMapper objectMapper
    ) {
        String credentials = Base64.getEncoder()
            .encodeToString((secretKey + ":").getBytes(StandardCharsets.UTF_8));
        this.restClient = RestClient.builder()
            .baseUrl(baseUrl)
            .defaultHeader("Authorization", "Basic " + credentials)
            .build();
        this.objectMapper = objectMapper;
    }

    @Override
    public TossConfirmResult confirm(String paymentKey, UUID orderId, int amount) {
        TossConfirmRequest request = new TossConfirmRequest(paymentKey, orderId.toString(), amount);
        String requestJson = toJson(request);

        TossConfirmResponse response = restClient.post()
            .uri("/payments/confirm")
            .contentType(MediaType.APPLICATION_JSON)
            .body(request)
            .retrieve()
            .onStatus(HttpStatusCode::is4xxClientError, (req, resp) -> {
                TossErrorResponse error = parseError(resp);
                throw new PaymentGatewayException(
                    PaymentErrorCode.PAYMENT_FAILED,
                    error.code(), error.message(),
                    requestJson, null
                );
            })
            .onStatus(HttpStatusCode::is5xxServerError, (req, resp) -> {
                TossErrorResponse error = parseError(resp);
                throw new PaymentGatewayException(
                    PaymentErrorCode.PG_ERROR,
                    error.code(), error.message(),
                    requestJson, null
                );
            })
            .body(TossConfirmResponse.class);

        return new TossConfirmResult(
            response.method(),
            response.totalAmount(),
            toJson(response),
            response.approvedAt()
        );
    }

    @Override
    public TossRefundResult refund(String pgTxId, UUID paymentId, int amount) {
        TossRefundRequest request = new TossRefundRequest("구매자 환불 요청", null);

        TossRefundResponse response = restClient.post()
            .uri("/payments/{paymentKey}/cancels", pgTxId)
            .header("Idempotency-Key", "refund-" + paymentId)
            .contentType(MediaType.APPLICATION_JSON)
            .body(request)
            .retrieve()
            .onStatus(HttpStatusCode::is4xxClientError, (req, resp) -> {
                TossErrorResponse error = parseError(resp);
                throw new PaymentGatewayException(
                    PaymentErrorCode.PG_ERROR,
                    error.code(), error.message(),
                    null, null
                );
            })
            .onStatus(HttpStatusCode::is5xxServerError, (req, resp) -> {
                TossErrorResponse error = parseError(resp);
                throw new PaymentGatewayException(
                    PaymentErrorCode.PG_ERROR,
                    error.code(), error.message(),
                    null, null
                );
            })
            .body(TossRefundResponse.class);

        TossRefundResponse.TossCancel lastCancel = response.cancels().get(response.cancels().size() - 1);
        return new TossRefundResult(lastCancel.canceledAt());
    }

    private String toJson(Object obj) {
        return objectMapper.writeValueAsString(obj);
    }

    private TossErrorResponse parseError(org.springframework.http.client.ClientHttpResponse resp) {
        try {
            String body = new String(resp.getBody().readAllBytes(), StandardCharsets.UTF_8);
            return objectMapper.readValue(body, TossErrorResponse.class);
        } catch (IOException e) {
            return new TossErrorResponse("UNKNOWN", "PG사 응답 파싱 실패");
        }
    }
}
