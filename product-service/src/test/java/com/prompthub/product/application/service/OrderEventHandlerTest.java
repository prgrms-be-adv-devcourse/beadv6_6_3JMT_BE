package com.prompthub.product.application.service;

import com.prompthub.product.domain.model.entity.ProductProcessedEvent;
import com.prompthub.product.domain.repository.ProcessedEventRepository;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;

@ExtendWith(MockitoExtension.class)
class OrderEventHandlerTest {

	private static final UUID EVENT_ID = UUID.fromString("99999999-9999-9999-9999-999999999999");
	private static final String GROUP = "product-service";
	private static final List<UUID> PRODUCT_IDS =
		List.of(UUID.fromString("11111111-1111-1111-1111-111111111111"));

	@Mock
	private ProductSalesCountService productSalesCountService;

	@Mock
	private ProcessedEventRepository processedEventRepository;

	@InjectMocks
	private OrderEventHandler orderEventHandler;

	@Test
	@DisplayName("처음 처리하는 이벤트면 판매수를 증가시키고 처리 이력을 저장한다")
	void handlePaid_notProcessed_incrementsAndMarks() {
		given(processedEventRepository.existsByEventIdAndConsumerGroup(EVENT_ID, GROUP)).willReturn(false);

		orderEventHandler.handlePaid(EVENT_ID, null, PRODUCT_IDS);

		then(productSalesCountService).should().incrementSalesCount(PRODUCT_IDS);
		then(processedEventRepository).should().save(any(ProductProcessedEvent.class));
	}

	@Test
	@DisplayName("이미 처리한 이벤트면(재전송) 판매수도 이력도 건드리지 않는다")
	void handlePaid_alreadyProcessed_skips() {
		given(processedEventRepository.existsByEventIdAndConsumerGroup(EVENT_ID, GROUP)).willReturn(true);

		orderEventHandler.handlePaid(EVENT_ID, null, PRODUCT_IDS);

		then(productSalesCountService).should(never()).incrementSalesCount(any());
		then(processedEventRepository).should(never()).save(any());
	}

	@Test
	@DisplayName("환불 이벤트도 처음이면 판매수를 감소시키고 이력을 저장한다")
	void handleRefund_notProcessed_decrementsAndMarks() {
		given(processedEventRepository.existsByEventIdAndConsumerGroup(EVENT_ID, GROUP)).willReturn(false);

		orderEventHandler.handleRefund(EVENT_ID, null, PRODUCT_IDS);

		then(productSalesCountService).should().decrementSalesCount(PRODUCT_IDS);
		then(processedEventRepository).should().save(any(ProductProcessedEvent.class));
	}
}
