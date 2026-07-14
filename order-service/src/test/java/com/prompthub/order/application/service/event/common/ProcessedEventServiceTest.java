package com.prompthub.order.application.service.event.common;

import com.prompthub.order.domain.model.OrderProcessedEvent;
import com.prompthub.order.domain.repository.ProcessedEventRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;

@ExtendWith(MockitoExtension.class)
class ProcessedEventServiceTest {

	private static final ConsumedEventContext CONTEXT = new ConsumedEventContext(
		UUID.fromString("00000000-0000-0000-0000-000000000301"),
		"PAYMENT_PARTIAL_REFUNDED",
		LocalDateTime.of(2026, 7, 13, 12, 0)
	);

	@Mock private ProcessedEventRepository repository;
	@InjectMocks private ProcessedEventService service;

	@Test
	void executeOnce_unprocessedEvent_executesActionAndMarksProcessed() {
		Runnable action = mock(Runnable.class);
		given(repository.existsByEventIdAndConsumerGroup(CONTEXT.eventId(), "order-service"))
			.willReturn(false);

		assertThat(service.executeOnce(CONTEXT, action)).isTrue();

		then(action).should().run();
		then(repository).should().save(any(OrderProcessedEvent.class));
	}

	@Test
	void executeOnce_duplicateEvent_skipsActionAndMarker() {
		Runnable action = mock(Runnable.class);
		given(repository.existsByEventIdAndConsumerGroup(CONTEXT.eventId(), "order-service"))
			.willReturn(true);

		assertThat(service.executeOnce(CONTEXT, action)).isFalse();

		then(action).shouldHaveNoInteractions();
		then(repository).should(never()).save(any());
	}

	@Test
	void executeOnce_actionFails_doesNotMarkProcessed() {
		Runnable action = () -> {
			throw new IllegalStateException("failure");
		};

		assertThatThrownBy(() -> service.executeOnce(CONTEXT, action))
			.isInstanceOf(IllegalStateException.class)
			.hasMessage("failure");
		then(repository).should(never()).save(any());
	}
}
