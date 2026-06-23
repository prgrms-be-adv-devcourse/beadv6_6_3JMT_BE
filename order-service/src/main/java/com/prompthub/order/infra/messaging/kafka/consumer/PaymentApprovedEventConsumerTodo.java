package com.prompthub.order.infra.messaging.kafka.consumer;

public final class PaymentApprovedEventConsumerTodo {

	/*
	 * TODO: 결제 서비스 Kafka 발행 구현이 확정되면 실제 Consumer로 교체한다.
	 * - topic: payment.approved
	 * - payload: PaymentApprovedEvent
	 * - handler: OrderService.approveOrder(event)
	 * - retry/dead-letter 정책은 Kafka 도입 이슈에서 함께 정의한다.
	 */
	private PaymentApprovedEventConsumerTodo() {
	}
}
