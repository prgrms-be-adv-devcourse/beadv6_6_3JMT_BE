package com.prompthub.order.application.service.refund;

import com.prompthub.order.application.dto.CreateOrderRefundCommand;
import com.prompthub.order.application.dto.OrderRefundResult;
import com.prompthub.order.application.usecase.CreateOrderRefundUseCase;
import com.prompthub.order.domain.model.Order;
import com.prompthub.order.domain.model.OrderPayment;
import com.prompthub.order.domain.model.OrderProduct;
import com.prompthub.order.global.exception.OrderException;
import com.prompthub.order.infra.persistence.order.OrderPaymentPersistence;
import com.prompthub.order.infra.persistence.order.OrderPersistence;
import com.prompthub.order.infra.persistence.outbox.OutboxEventPersistence;
import com.prompthub.order.infra.persistence.refund.OrderRefundPersistence;
import com.prompthub.order.support.PostgresIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;

class OrderRefundConcurrencyIntegrationTest extends PostgresIntegrationTest {

	@Autowired CreateOrderRefundUseCase useCase;
	@Autowired OrderPersistence orderPersistence;
	@Autowired OrderPaymentPersistence paymentPersistence;
	@Autowired OrderRefundPersistence refundPersistence;
	@Autowired OutboxEventPersistence outboxPersistence;

	@BeforeEach
	void clean() {
		outboxPersistence.deleteAll();
		refundPersistence.deleteAll();
		paymentPersistence.deleteAll();
		orderPersistence.deleteAll();
	}

	@Test
	void exactConcurrentRequests_reuseOneRefundAndOneOutboxEvent() throws Exception {
		Fixture fixture = persistPaidOrder(2);
		CreateOrderRefundCommand command = command(fixture, fixture.productIds());

		List<Outcome> outcomes = runConcurrently(command, command);

		assertThat(outcomes).allMatch(Outcome::succeeded);
		assertThat(outcomes.stream().map(outcome -> outcome.result().refundRequestId()).collect(java.util.stream.Collectors.toSet()))
			.hasSize(1);
		assertThat(refundPersistence.count()).isEqualTo(1);
		assertThat(refundPersistence.findAllByOrderIdWithProducts(fixture.orderId()).getFirst().getProducts())
			.hasSize(2);
		assertThat(outboxPersistence.findAll()).filteredOn(event -> event.getEventType().equals("REFUND_REQUESTED"))
			.hasSize(1);
	}

	@Test
	void overlappingConcurrentRequests_persistOnlyOneTwoProductBatch() throws Exception {
		Fixture fixture = persistPaidOrder(3);
		CreateOrderRefundCommand first = command(fixture, fixture.productIds().subList(0, 2));
		CreateOrderRefundCommand second = command(fixture, fixture.productIds().subList(1, 3));

		List<Outcome> outcomes = runConcurrently(first, second);

		assertThat(outcomes).filteredOn(Outcome::succeeded).hasSize(1);
		assertThat(outcomes).filteredOn(outcome -> outcome.failure() instanceof OrderException).hasSize(1);
		assertThat(refundPersistence.count()).isEqualTo(1);
		assertThat(refundPersistence.findAllByOrderIdWithProducts(fixture.orderId()).getFirst().getProducts())
			.hasSize(2);
		assertThat(outboxPersistence.findAll()).filteredOn(event -> event.getEventType().equals("REFUND_REQUESTED"))
			.hasSize(1);
	}

	private List<Outcome> runConcurrently(CreateOrderRefundCommand first, CreateOrderRefundCommand second)
		throws Exception {
		CountDownLatch ready = new CountDownLatch(2);
		CountDownLatch start = new CountDownLatch(1);
		try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
			Future<Outcome> firstFuture = executor.submit(() -> invoke(first, ready, start));
			Future<Outcome> secondFuture = executor.submit(() -> invoke(second, ready, start));
			ready.await();
			start.countDown();
			return List.of(firstFuture.get(), secondFuture.get());
		}
	}

	private Outcome invoke(CreateOrderRefundCommand command, CountDownLatch ready, CountDownLatch start) {
		ready.countDown();
		try {
			start.await();
			return Outcome.success(useCase.create(command));
		} catch (Throwable failure) {
			return Outcome.failure(failure);
		}
	}

	private CreateOrderRefundCommand command(Fixture fixture, List<UUID> productIds) {
		return new CreateOrderRefundCommand(fixture.buyerId(), fixture.orderId(), fixture.paymentId(), productIds);
	}

	private Fixture persistPaidOrder(int productCount) {
		UUID buyerId = UUID.randomUUID();
		Order order = Order.create(
			buyerId,
			"ORD-" + UUID.randomUUID().toString().substring(0, 20),
			60_000,
			productCount
		);
		for (int index = 0; index < productCount; index++) {
			order.addOrderProduct(OrderProduct.create(
				UUID.randomUUID(), UUID.randomUUID(), "상품 " + index, "PROMPT", "MODEL", 10_000 + index
			));
		}
		order.markPaid(LocalDateTime.of(2026, 7, 14, 9, 0));
		Order saved = orderPersistence.saveAndFlush(order);
		UUID paymentId = UUID.randomUUID();
		paymentPersistence.saveAndFlush(OrderPayment.create(
			saved.getId(), paymentId, buyerId, saved.getTotalOrderAmount(), LocalDateTime.of(2026, 7, 14, 9, 0)
		));
		return new Fixture(
			buyerId, saved.getId(), paymentId, saved.getOrderProducts().stream().map(OrderProduct::getId).toList()
		);
	}

	private record Fixture(UUID buyerId, UUID orderId, UUID paymentId, List<UUID> productIds) {
	}

	private record Outcome(OrderRefundResult result, Throwable failure) {
		static Outcome success(OrderRefundResult result) { return new Outcome(result, null); }
		static Outcome failure(Throwable failure) { return new Outcome(null, failure); }
		boolean succeeded() { return failure == null; }
	}
}
