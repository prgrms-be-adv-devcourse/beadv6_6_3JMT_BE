package com.prompthub.order.application.service.event;

import com.prompthub.order.application.service.order.OrderFailureCompensationService;
import com.prompthub.order.infra.messaging.kafka.event.PaymentFailedPayload;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.transaction.annotation.Transactional;

import java.lang.reflect.Method;
import java.time.LocalDateTime;
import java.util.UUID;

import static com.prompthub.order.fixture.PaymentEventFixture.FAILED_AT;
import static com.prompthub.order.fixture.PaymentEventFixture.failedPayload;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.then;

@ExtendWith(MockitoExtension.class)
class PaymentFailedProcessorTest {

	private static final UUID EVENT_ID = UUID.fromString("00000000-0000-0000-0000-000000000901");
	private static final String EVENT_TYPE = "PAYMENT_FAILED";

	@Mock
	private OrderFailureCompensationService compensationService;

	private PaymentFailedProcessor processor;

	@BeforeEach
	void setUp() {
		processor = new PaymentFailedProcessor(compensationService);
	}

	@Test
	@DisplayName("결제 실패 metadata와 payload를 공통 보상 서비스에 그대로 위임한다")
	void process_delegatesMetadataAndPayload() {
		PaymentFailedPayload payload = failedPayload();

		processor.process(EVENT_ID, EVENT_TYPE, FAILED_AT, payload);

		then(compensationService).should()
			.compensatePaymentFailure(EVENT_ID, EVENT_TYPE, FAILED_AT, payload);
	}

	@Test
	@DisplayName("Processor는 DB 트랜잭션 경계를 소유하지 않는다")
	void processorHasNoTransactionalBoundary() throws Exception {
		Method process = PaymentFailedProcessor.class.getDeclaredMethod(
			"process",
			UUID.class,
			String.class,
			LocalDateTime.class,
			PaymentFailedPayload.class
		);

		assertThat(AnnotatedElementUtils.hasAnnotation(PaymentFailedProcessor.class, Transactional.class)).isFalse();
		assertThat(AnnotatedElementUtils.hasAnnotation(process, Transactional.class)).isFalse();
	}
}
