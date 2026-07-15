package com.prompthub.order.application.service.refund;

import com.prompthub.order.application.client.ProductClient;
import com.prompthub.order.application.client.SellerClient;
import com.prompthub.order.application.dto.ProductContent;
import com.prompthub.order.application.usecase.ConfirmDownloadUseCase;
import com.prompthub.order.application.usecase.OrderRefundUseCase;
import com.prompthub.order.domain.enums.OrderStatus;
import com.prompthub.order.domain.model.Order;
import com.prompthub.order.domain.model.OrderPayment;
import com.prompthub.order.domain.model.OrderProduct;
import com.prompthub.order.global.exception.ErrorCode;
import com.prompthub.order.global.exception.OrderException;
import com.prompthub.order.infra.messaging.kafka.consumer.payment.PaymentEventConsumer;
import com.prompthub.order.infra.messaging.kafka.consumer.product.ProductEventConsumer;
import com.prompthub.order.infra.persistence.order.OrderPaymentPersistence;
import com.prompthub.order.infra.persistence.order.OrderPersistence;
import com.prompthub.order.infra.persistence.outbox.OutboxEventPersistence;
import com.prompthub.order.infra.persistence.refund.OrderRefundPersistence;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static com.prompthub.order.fixture.OrderFixture.APPROVED_AT;
import static com.prompthub.order.fixture.OrderFixture.BUYER_ID;
import static com.prompthub.order.fixture.OrderFixture.PAYMENT_ID;
import static com.prompthub.order.fixture.OrderFixture.createPaidOrderWithProducts;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;

@SpringBootTest(properties = "spring.kafka.listener.auto-startup=false")
@ActiveProfiles("test")
class OrderRefundConcurrencyTest {

	@Autowired
	private OrderRefundUseCase orderRefundUseCase;

	@Autowired
	private ConfirmDownloadUseCase confirmDownloadUseCase;

	@Autowired
	private OrderPersistence orderPersistence;

	@Autowired
	private OrderPaymentPersistence orderPaymentPersistence;

	@Autowired
	private OrderRefundPersistence orderRefundPersistence;

	@Autowired
	private OutboxEventPersistence outboxEventPersistence;

	@MockitoBean
	private ProductClient productClient;

	@MockitoBean
	private SellerClient sellerClient;

	@MockitoBean
	private PaymentEventConsumer paymentEventConsumer;

	@MockitoBean
	private ProductEventConsumer productEventConsumer;

	@BeforeEach
	void cleanUp() {
		orderRefundPersistence.deleteAll();
		outboxEventPersistence.deleteAll();
		orderPaymentPersistence.deleteAll();
		orderPersistence.deleteAll();
	}

	@Test
	@DisplayName("다운로드가 먼저 커밋되면 같은 상품의 환불 요청은 거절된다")
	void downloadFirst_sameProductRefundRejected() {
		Order order = savePaidOrderAndPayment();
		OrderProduct target = order.getOrderProducts().getFirst();
		given(productClient.getProductContent(target.getProductId()))
			.willReturn(new ProductContent(target.getProductId(), "content"));

		confirmDownloadUseCase.confirmDownload(BUYER_ID, order.getId(), target.getId());

		assertThatThrownBy(() ->
			orderRefundUseCase.requestRefund(BUYER_ID, order.getId(), PAYMENT_ID, target.getId())
		)
			.isInstanceOf(OrderException.class)
			.satisfies(exception ->
				assertThat(((OrderException) exception).getErrorCode())
					.isEqualTo(ErrorCode.ORDER_REFUND_NOT_ALLOWED)
			);

		Order savedOrder = orderPersistence.findByIdWithOrderProducts(order.getId()).orElseThrow();
		OrderProduct savedTarget = findProduct(savedOrder, target.getId());
		assertThat(savedTarget.isDownloaded()).isTrue();
		assertThat(savedTarget.getOrderStatus()).isEqualTo(OrderStatus.PAID);
		assertThat(orderRefundPersistence.count()).isZero();
		assertThat(outboxEventPersistence.count()).isZero();
	}

	@Test
	@DisplayName("환불이 먼저 커밋되면 이미 조회 중이던 같은 상품 다운로드는 버전 충돌로 롤백된다")
	void refundFirst_sameProductDownloadConflicts() throws Exception {
		Order order = savePaidOrderAndPayment();
		OrderProduct target = order.getOrderProducts().getFirst();
		CountDownLatch contentRequested = new CountDownLatch(1);
		CountDownLatch refundCommitted = new CountDownLatch(1);
		given(productClient.getProductContent(target.getProductId())).willAnswer(invocation -> {
			contentRequested.countDown();
			if (!refundCommitted.await(5, TimeUnit.SECONDS)) {
				throw new IllegalStateException("환불 커밋 대기 시간 초과");
			}
			return new ProductContent(target.getProductId(), "content");
		});

		ExecutorService executor = Executors.newSingleThreadExecutor();
		try {
			Future<?> download = executor.submit(() ->
				confirmDownloadUseCase.confirmDownload(BUYER_ID, order.getId(), target.getId())
			);
			assertThat(contentRequested.await(5, TimeUnit.SECONDS)).isTrue();

			orderRefundUseCase.requestRefund(BUYER_ID, order.getId(), PAYMENT_ID, target.getId());
			refundCommitted.countDown();

			assertThatThrownBy(() -> download.get(5, TimeUnit.SECONDS))
				.hasCauseInstanceOf(ObjectOptimisticLockingFailureException.class);
		} finally {
			refundCommitted.countDown();
			executor.shutdownNow();
		}

		Order savedOrder = orderPersistence.findByIdWithOrderProducts(order.getId()).orElseThrow();
		OrderProduct savedTarget = findProduct(savedOrder, target.getId());
		assertThat(savedTarget.getOrderStatus()).isEqualTo(OrderStatus.REFUND_REQUESTED);
		assertThat(savedTarget.isDownloaded()).isFalse();
		assertThat(orderRefundPersistence.count()).isOne();
		assertThat(outboxEventPersistence.count()).isOne();
	}

	@Test
	@DisplayName("한 상품이 환불 요청 중이어도 같은 주문의 다른 PAID 상품은 다운로드할 수 있다")
	void differentProduct_refundAndDownloadBothSucceed() {
		Order order = savePaidOrderAndPayment();
		OrderProduct refundTarget = order.getOrderProducts().getFirst();
		OrderProduct downloadTarget = order.getOrderProducts().getLast();
		given(productClient.getProductContent(downloadTarget.getProductId()))
			.willReturn(new ProductContent(downloadTarget.getProductId(), "content"));

		orderRefundUseCase.requestRefund(BUYER_ID, order.getId(), PAYMENT_ID, refundTarget.getId());
		confirmDownloadUseCase.confirmDownload(BUYER_ID, order.getId(), downloadTarget.getId());

		Order savedOrder = orderPersistence.findByIdWithOrderProducts(order.getId()).orElseThrow();
		assertThat(findProduct(savedOrder, refundTarget.getId()).getOrderStatus())
			.isEqualTo(OrderStatus.REFUND_REQUESTED);
		assertThat(findProduct(savedOrder, downloadTarget.getId()).isDownloaded()).isTrue();
	}

	@Test
	@DisplayName("같은 주문의 동시 환불 요청은 하나만 커밋된다")
	void concurrentRefunds_sameOrder_onlyOneCommits() throws Exception {
		Order order = savePaidOrderAndPayment();
		List<UUID> productIds = order.getOrderProducts().stream()
			.map(OrderProduct::getId)
			.toList();
		CountDownLatch start = new CountDownLatch(1);
		ExecutorService executor = Executors.newFixedThreadPool(2);
		try {
			List<Future<Throwable>> results = productIds.stream()
				.map(productId -> executor.submit(() -> requestRefundAfter(start, order.getId(), productId)))
				.toList();
			start.countDown();

			List<Throwable> failures = results.stream()
				.map(this::getResult)
				.filter(result -> result != null)
				.toList();

			assertThat(failures).hasSize(1);
			assertThat(failures.getFirst()).satisfiesAnyOf(
				failure -> assertThat(failure).isInstanceOf(ObjectOptimisticLockingFailureException.class),
				failure -> {
					assertThat(failure).isInstanceOf(OrderException.class);
					assertThat(((OrderException) failure).getErrorCode())
						.isEqualTo(ErrorCode.ORDER_REFUND_IN_PROGRESS);
				}
			);
		} finally {
			start.countDown();
			executor.shutdownNow();
		}

		Order savedOrder = orderPersistence.findByIdWithOrderProducts(order.getId()).orElseThrow();
		assertThat(savedOrder.getOrderStatus()).isEqualTo(OrderStatus.REFUND_REQUESTED);
		assertThat(savedOrder.getOrderProducts())
			.extracting(OrderProduct::getOrderStatus)
			.containsExactlyInAnyOrder(OrderStatus.REFUND_REQUESTED, OrderStatus.PAID);
		assertThat(orderRefundPersistence.count()).isOne();
		assertThat(outboxEventPersistence.count()).isOne();
	}

	private Throwable requestRefundAfter(CountDownLatch start, UUID orderId, UUID orderProductId) {
		try {
			if (!start.await(5, TimeUnit.SECONDS)) {
				return new IllegalStateException("동시 요청 시작 대기 시간 초과");
			}
			orderRefundUseCase.requestRefund(BUYER_ID, orderId, PAYMENT_ID, orderProductId);
			return null;
		} catch (Throwable exception) {
			return exception;
		}
	}

	private Throwable getResult(Future<Throwable> result) {
		try {
			return result.get(10, TimeUnit.SECONDS);
		} catch (Exception exception) {
			return exception;
		}
	}

	private Order savePaidOrderAndPayment() {
		Order savedOrder = orderPersistence.saveAndFlush(createPaidOrderWithProducts());
		orderPaymentPersistence.saveAndFlush(OrderPayment.create(
			savedOrder.getId(),
			PAYMENT_ID,
			BUYER_ID,
			savedOrder.getTotalOrderAmount(),
			APPROVED_AT
		));
		return savedOrder;
	}

	private OrderProduct findProduct(Order order, UUID orderProductId) {
		return order.getOrderProducts().stream()
			.filter(product -> product.getId().equals(orderProductId))
			.findFirst()
			.orElseThrow();
	}
}
