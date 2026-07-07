package com.prompthub.paymentservice.infrastructure.external.toss;

import com.prompthub.paymentservice.application.exception.PaymentErrorCode;
import com.prompthub.paymentservice.application.gateway.external.PaymentGateway;
import com.prompthub.paymentservice.application.gateway.external.PaymentGatewayException;
import com.prompthub.paymentservice.application.gateway.external.ConfirmResult;
import com.prompthub.paymentservice.application.gateway.external.RefundResult;
import com.prompthub.paymentservice.infrastructure.external.toss.dto.TossConfirmRequest;
import com.prompthub.paymentservice.infrastructure.external.toss.dto.TossConfirmResponse;
import com.prompthub.paymentservice.infrastructure.external.toss.dto.TossErrorResponse;
import com.prompthub.paymentservice.infrastructure.external.toss.dto.TossRefundRequest;
import com.prompthub.paymentservice.infrastructure.external.toss.dto.TossRefundResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.ObjectMapper;

@Slf4j
@Component
public class TossPaymentGateway implements PaymentGateway {

    // 우리 서버가 잘못된 요청을 전송한 경우 — PG_INVALID_REQUEST(502)로 분류
    private static final Set<String> TOSS_SERVER_ERROR_CODES = Set.of(
        "INVALID_REQUEST",
        "INVALID_API_KEY",
        "UNAUTHORIZED_KEY",
        "FORBIDDEN_REQUEST",
        "NOT_FOUND_PAYMENT",
        "NOT_FOUND_PAYMENT_SESSION",
        "ALREADY_PROCESSED_PAYMENT"
    );

    private final RestClient restClient;
    private final ObjectMapper objectMapper;

    public TossPaymentGateway(
        @Value("${payment.toss.secret-key}") String secretKey,
        @Value("${payment.toss.base-url:https://api.tosspayments.com/v1}") String baseUrl,
        ObjectMapper objectMapper
    ) {
        String credentials = Base64.getEncoder()
            .encodeToString((secretKey + ":").getBytes(StandardCharsets.UTF_8));
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(5));
        factory.setReadTimeout(Duration.ofSeconds(10));
        this.restClient = RestClient.builder()
            .baseUrl(baseUrl)
            .defaultHeader("Authorization", "Basic " + credentials)
            .requestFactory(factory)
            .build();
        this.objectMapper = objectMapper;
    }

    @Override
    public ConfirmResult confirm(String paymentKey, UUID orderId, int amount) {
        TossConfirmRequest request = new TossConfirmRequest(paymentKey, orderId.toString(), amount);
        String requestJson = toJson(request);

        TossConfirmResponse response = restClient.post()
            .uri("/payments/confirm")
            .contentType(MediaType.APPLICATION_JSON)
            .body(request)
            .retrieve()
            .onStatus(HttpStatusCode::is4xxClientError, (req, resp) -> {
                TossErrorResponse error = parseError(resp);
                PaymentErrorCode errorCode = TOSS_SERVER_ERROR_CODES.contains(error.code())
                    ? PaymentErrorCode.PG_INVALID_REQUEST
                    : PaymentErrorCode.PAYMENT_FAILED;
                throw new PaymentGatewayException(
                    errorCode, error.code(), error.message(), requestJson, null
                );
            })
            .onStatus(HttpStatusCode::is5xxServerError, (req, resp) -> {
                TossErrorResponse error = parseError(resp);
                throw new PaymentGatewayException(
                    PaymentErrorCode.PG_SERVER_ERROR,
                    error.code(), error.message(),
                    requestJson, null
                );
            })
            .body(TossConfirmResponse.class);

        if (response == null) {
            throw new PaymentGatewayException(
                PaymentErrorCode.PG_INVALID_REQUEST, "NULL_RESPONSE", "PG사 응답이 없습니다.", requestJson, null
            );
        }

        return new ConfirmResult(
            response.method(),
            response.totalAmount(),
            toJson(response),
            response.approvedAt()
        );
    }

    @Override
    public RefundResult refund(String pgTxId, UUID paymentId, int amount) {
        TossRefundRequest request = new TossRefundRequest("구매자 환불 요청", null);

        TossRefundResponse response = restClient.post()
            .uri("/payments/{paymentKey}/cancel", pgTxId)
            .header("Idempotency-Key", "refund-" + paymentId)
            .contentType(MediaType.APPLICATION_JSON)
            .body(request)
            .retrieve()
            .onStatus(HttpStatusCode::is4xxClientError, (req, resp) -> {
                TossErrorResponse error = parseError(resp);
                PaymentErrorCode errorCode = TOSS_SERVER_ERROR_CODES.contains(error.code())
                    ? PaymentErrorCode.PG_INVALID_REQUEST
                    : PaymentErrorCode.PAYMENT_FAILED;
                throw new PaymentGatewayException(
                    errorCode, error.code(), error.message(), null, null
                );
            })
            .onStatus(HttpStatusCode::is5xxServerError, (req, resp) -> {
                TossErrorResponse error = parseError(resp);
                throw new PaymentGatewayException(
                    PaymentErrorCode.PG_SERVER_ERROR,
                    error.code(), error.message(),
                    null, null
                );
            })
            .body(TossRefundResponse.class);

        if (response == null) {
            throw new PaymentGatewayException(
                PaymentErrorCode.PG_SERVER_ERROR, "NULL_RESPONSE", "PG사 환불 응답이 없습니다.", null, null
            );
        }
        List<TossRefundResponse.TossCancel> cancels = response.cancels();
        if (cancels == null || cancels.isEmpty()) {
            throw new PaymentGatewayException(
                PaymentErrorCode.PG_SERVER_ERROR, "NO_CANCEL_DATA", "Toss 환불 응답에 취소 내역이 없습니다.", null, null
            );
        }
        TossRefundResponse.TossCancel lastCancel = cancels.get(cancels.size() - 1);
        return new RefundResult(lastCancel.canceledAt());
    }

    private String toJson(Object obj) {
        return objectMapper.writeValueAsString(obj);
    }

    private TossErrorResponse parseError(org.springframework.http.client.ClientHttpResponse resp) {
        try {
            String body = new String(resp.getBody().readAllBytes(), StandardCharsets.UTF_8);
            tools.jackson.databind.JsonNode node = objectMapper.readTree(body);
            String code = node.path("code").asText("UNKNOWN");
            tools.jackson.databind.JsonNode messageNode = node.path("message");
            String message = messageNode.isObject() ? messageNode.toString() : messageNode.asText("PG사 오류");
            return new TossErrorResponse(code, message);
        } catch (IOException e) {
            log.warn("Toss 에러 응답 파싱 실패 — cause={}", e.getMessage(), e);
            return new TossErrorResponse("UNKNOWN", "PG사 응답 파싱 실패");
        }
    }
}
