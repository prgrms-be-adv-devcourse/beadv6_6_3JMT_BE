package com.prompthub.order.infra.messaging.kafka.router;

import com.prompthub.common.event.EventMessage;
import com.prompthub.order.application.service.event.PaymentApprovedEventHandler;
import com.prompthub.order.application.service.event.PaymentFailedEventHandler;
import com.prompthub.order.application.service.event.PaymentRefundedEventHandler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.LocalDateTime;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class PaymentEventRouterTest {

    @Mock
    private PaymentApprovedEventHandler approvedHandler;
    @Mock
    private PaymentRefundedEventHandler refundedHandler;
    @Mock
    private PaymentFailedEventHandler failedHandler;

    @InjectMocks
    private PaymentEventRouter paymentEventRouter;

    private ObjectMapper objectMapper = new ObjectMapper();
    private JsonNode dummyPayload;

    @BeforeEach
    void setUp() throws Exception {
        dummyPayload = objectMapper.readTree("{\"orderId\":\"123\"}");
    }

    @Test
    @DisplayName("TC-ROUTER-001: payment.failed 이벤트는 PaymentFailedEventHandler로 라우팅")
    void route_paymentFailed_routesToFailedHandler() {
        EventMessage<JsonNode> message = new EventMessage<>(
                UUID.randomUUID(), "payment.failed", LocalDateTime.now(), "PAYMENT", UUID.randomUUID(), dummyPayload
        );

        paymentEventRouter.route(message);

        verify(failedHandler).handle(message);
        verify(approvedHandler, never()).handle(any());
        verify(refundedHandler, never()).handle(any());
    }

    @Test
    @DisplayName("TC-ROUTER-002: PAYMENT_FAILED 이벤트도 동일하게 PaymentFailedEventHandler로 라우팅")
    void route_paymentFailedEnum_routesToFailedHandler() {
        EventMessage<JsonNode> message = new EventMessage<>(
                UUID.randomUUID(), "PAYMENT_FAILED", LocalDateTime.now(), "PAYMENT", UUID.randomUUID(), dummyPayload
        );

        paymentEventRouter.route(message);

        verify(failedHandler).handle(message);
        verify(approvedHandler, never()).handle(any());
        verify(refundedHandler, never()).handle(any());
    }

    @Test
    @DisplayName("TC-ROUTER-003: 알 수 없는 이벤트 타입은 로그만 남기고 정상 종료")
    void route_unknownEvent_shouldReturnGracefully() {
        EventMessage<JsonNode> message = new EventMessage<>(
                UUID.randomUUID(), "UNKNOWN_EVENT_TYPE", LocalDateTime.now(), "PAYMENT", UUID.randomUUID(), dummyPayload
        );

        paymentEventRouter.route(message);

        verify(failedHandler, never()).handle(any());
        verify(approvedHandler, never()).handle(any());
        verify(refundedHandler, never()).handle(any());
    }

	@Test
	@DisplayName("PAYMENT_CANCELED 이벤트는 지원하지 않는다")
	void route_paymentCanceled_isUnsupported() {
		EventMessage<JsonNode> message = new EventMessage<>(
			UUID.randomUUID(),
			"PAYMENT_CANCELED",
			LocalDateTime.now(),
			"PAYMENT",
			UUID.randomUUID(),
			dummyPayload
		);

		paymentEventRouter.route(message);

		verify(approvedHandler, never()).handle(any());
		verify(refundedHandler, never()).handle(any());
		verify(failedHandler, never()).handle(any());
	}
}
