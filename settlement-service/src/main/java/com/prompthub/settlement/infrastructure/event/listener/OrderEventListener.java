package com.prompthub.settlement.infrastructure.event.listener;

import com.prompthub.settlement.application.usecase.SettlementSourceUseCase;
import com.prompthub.settlement.infrastructure.event.message.OrderSettlementMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class OrderEventListener {

	private final SettlementSourceUseCase settlementSourceUseCase;

	@KafkaListener(
		topics = {
			"${settlement.kafka.topics.order-paid}",
			"${settlement.kafka.topics.order-refunded}"
		},
		containerFactory = "orderKafkaListenerContainerFactory",
		autoStartup = "${settlement.kafka.listener.order.enabled:false}"
	)
	public void consume(OrderSettlementMessage message, Acknowledgment acknowledgment) {
		message.toCommands().forEach(settlementSourceUseCase::record);
		acknowledgment.acknowledge();
	}
}
