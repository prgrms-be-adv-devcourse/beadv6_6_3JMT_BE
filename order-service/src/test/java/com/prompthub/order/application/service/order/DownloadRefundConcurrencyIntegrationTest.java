package com.prompthub.order.application.service.order;

import com.prompthub.order.application.client.ProductClient;
import com.prompthub.order.application.client.SellerClient;
import com.prompthub.order.application.dto.ProductContent;
import com.prompthub.order.application.service.event.PaymentRefundedProcessor;
import com.prompthub.order.application.service.event.outbox.OutboxEventAppender;
import com.prompthub.order.domain.enums.OrderProductStatus;
import com.prompthub.order.domain.enums.OrderStatus;
import com.prompthub.order.domain.model.Order;
import com.prompthub.order.domain.model.OrderProduct;
import com.prompthub.order.domain.repository.ProcessedEventRepository;
import com.prompthub.order.global.exception.ErrorCode;
import com.prompthub.order.global.exception.OrderException;
import com.prompthub.order.infra.messaging.kafka.event.PaymentRefundedPayload;
import com.prompthub.order.infra.persistence.order.OrderPersistence;
import com.prompthub.order.presentation.dto.response.OrderProductDownloadResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.time.LocalDateTime;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static com.prompthub.order.fixture.PaymentEventFixture.APPROVED_AT;
import static com.prompthub.order.fixture.PaymentEventFixture.BUYER_ID;
import static com.prompthub.order.fixture.PaymentEventFixture.ORDER_A;
import static com.prompthub.order.fixture.PaymentEventFixture.ORDER_PRODUCT_A;
import static com.prompthub.order.fixture.PaymentEventFixture.PAYMENT_ID;
import static com.prompthub.order.fixture.PaymentEventFixture.PRODUCT_A;
import static com.prompthub.order.fixture.PaymentEventFixture.createdOrder;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.BDDMockito.willAnswer;
import static org.mockito.Mockito.reset;

@SpringBootTest
@ActiveProfiles("test")
class DownloadRefundConcurrencyIntegrationTest {

	private static final LocalDateTime REFUNDED_AT = LocalDateTime.of(2026, 7, 17, 11, 0);
	private static final String REFUNDED_AT_OFFSET = "2026-07-17T11:00:00+09:00";
	private static final long WAIT_SECONDS = 5;

	@Autowired
	private ConfirmDownloadCommandHandler confirmDownloadCommandHandler;

	@Autowired
	private PaymentRefundedProcessor paymentRefundedProcessor;

	@Autowired
	private OrderPersistence orderPersistence;

	@Autowired
	private ProcessedEventRepository processedEventRepository;

	@MockitoBean
	private ProductClient productClient;

	@MockitoBean
	private SellerClient sellerClient;

	@MockitoBean
	private OrderExpirationStore orderExpirationStore;

	@MockitoBean
	private OutboxEventAppender outboxEventAppender;

	private ExecutorService executor;

	@BeforeEach
	void setUp() {
		executor = Executors.newFixedThreadPool(2);
	}

	@AfterEach
	void tearDown() throws InterruptedException {
		executor.shutdownNow();
		executor.awaitTermination(WAIT_SECONDS, TimeUnit.SECONDS);
		processedEventRepository.deleteAll();
		orderPersistence.deleteAll();
		reset(productClient, sellerClient, orderExpirationStore, outboxEventAppender);
	}

	@Test
	@DisplayName("다운로드가 먼저 잠그면 환불이 직렬화되고 최종 상품 상태는 REFUNDED를 유지한다")
	void downloadWinsLock_refundWaitsAndFinalStatusCannotBeStalePaid() throws Exception {
		Order paidOrder = savePaidOrder();
		CountDownLatch downloadHoldingLock = new CountDownLatch(1);
		CountDownLatch releaseDownload = new CountDownLatch(1);
		given(productClient.getProductContent(PRODUCT_A)).willAnswer(invocation -> {
			downloadHoldingLock.countDown();
			await(releaseDownload);
			return new ProductContent(PRODUCT_A, "content");
		});

		Future<OrderProductDownloadResponse> downloadFuture = executor.submit(() ->
			confirmDownloadCommandHandler.confirmDownload(BUYER_ID, ORDER_A, ORDER_PRODUCT_A)
		);
		assertThat(downloadHoldingLock.await(WAIT_SECONDS, TimeUnit.SECONDS)).isTrue();

		CountDownLatch refundStarted = new CountDownLatch(1);
		Future<?> refundFuture = executor.submit(() -> {
			refundStarted.countDown();
			paymentRefundedProcessor.process(
				UUID.randomUUID(),
				"PAYMENT_REFUNDED",
				REFUNDED_AT,
				refundedPayload(paidOrder)
			);
			return null;
		});
		assertThat(refundStarted.await(WAIT_SECONDS, TimeUnit.SECONDS)).isTrue();
		assertStillWaiting(refundFuture);

		releaseDownload.countDown();
		assertThat(downloadFuture.get(WAIT_SECONDS, TimeUnit.SECONDS).downloaded()).isTrue();
		refundFuture.get(WAIT_SECONDS, TimeUnit.SECONDS);

		Order reloaded = reloadOrder();
		OrderProduct target = findProduct(reloaded);
		assertThat(reloaded.getOrderStatus()).isEqualTo(OrderStatus.PARTIAL_REFUNDED);
		assertThat(target.getOrderStatus()).isEqualTo(OrderProductStatus.REFUNDED);
		assertThat(target.isDownloaded()).isTrue();
	}

	@Test
	@DisplayName("환불이 먼저 잠그면 다운로드는 최신 REFUNDED 상태를 읽고 콘텐츠 접근을 거부한다")
	void refundWinsLock_downloadWaitsAndRejectsRefundedProduct() throws Exception {
		Order paidOrder = savePaidOrder();
		CountDownLatch refundHoldingLock = new CountDownLatch(1);
		CountDownLatch releaseRefund = new CountDownLatch(1);
		willAnswer(invocation -> {
			refundHoldingLock.countDown();
			await(releaseRefund);
			return null;
		}).given(outboxEventAppender).append(any());

		Future<?> refundFuture = executor.submit(() -> {
			paymentRefundedProcessor.process(
				UUID.randomUUID(),
				"PAYMENT_REFUNDED",
				REFUNDED_AT,
				refundedPayload(paidOrder)
			);
			return null;
		});
		assertThat(refundHoldingLock.await(WAIT_SECONDS, TimeUnit.SECONDS)).isTrue();

		Future<OrderProductDownloadResponse> downloadFuture = executor.submit(() ->
			confirmDownloadCommandHandler.confirmDownload(BUYER_ID, ORDER_A, ORDER_PRODUCT_A)
		);
		assertStillWaiting(downloadFuture);

		releaseRefund.countDown();
		refundFuture.get(WAIT_SECONDS, TimeUnit.SECONDS);
		assertThatThrownBy(() -> downloadFuture.get(WAIT_SECONDS, TimeUnit.SECONDS))
			.isInstanceOf(ExecutionException.class)
			.satisfies(exception -> assertThat(exception.getCause())
				.isInstanceOf(OrderException.class)
				.hasFieldOrPropertyWithValue("errorCode", ErrorCode.ORDER_CONTENT_ACCESS_DENIED));

		OrderProduct target = findProduct(reloadOrder());
		assertThat(target.getOrderStatus()).isEqualTo(OrderProductStatus.REFUNDED);
		assertThat(target.isDownloaded()).isFalse();
		then(productClient).shouldHaveNoInteractions();
	}

	private Order savePaidOrder() {
		Order order = createdOrder();
		order.markCompleted(APPROVED_AT);
		return orderPersistence.saveAndFlush(order);
	}

	private PaymentRefundedPayload refundedPayload(Order order) {
		return new PaymentRefundedPayload(
			PAYMENT_ID,
			order.getId(),
			order.getBuyerId(),
			ORDER_PRODUCT_A,
			10_000,
			"PARTIAL_REFUNDED",
			REFUNDED_AT_OFFSET
		);
	}

	private Order reloadOrder() {
		return orderPersistence.findByIdWithOrderProducts(ORDER_A).orElseThrow();
	}

	private OrderProduct findProduct(Order order) {
		return order.getOrderProducts().stream()
			.filter(product -> product.getId().equals(ORDER_PRODUCT_A))
			.findFirst()
			.orElseThrow();
	}

	private void assertStillWaiting(Future<?> future) {
		assertThatThrownBy(() -> future.get(300, TimeUnit.MILLISECONDS))
			.isInstanceOf(TimeoutException.class);
	}

	private void await(CountDownLatch latch) throws InterruptedException {
		if (!latch.await(WAIT_SECONDS, TimeUnit.SECONDS)) {
			throw new IllegalStateException("concurrency test latch timed out");
		}
	}
}
