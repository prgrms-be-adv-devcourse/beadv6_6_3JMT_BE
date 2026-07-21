package com.prompthub.order.application.service.order;

import com.prompthub.order.application.client.ProductClient;
import com.prompthub.order.application.dto.ProductContent;
import com.prompthub.order.application.service.event.outbox.OutboxEventAppender;
import com.prompthub.order.application.service.refund.OrderRefundService;
import com.prompthub.order.domain.enums.OrderProductStatus;
import com.prompthub.order.domain.enums.OrderStatus;
import com.prompthub.order.domain.model.Order;
import com.prompthub.order.domain.model.OrderProduct;
import com.prompthub.order.global.exception.ErrorCode;
import com.prompthub.order.global.exception.OrderException;
import com.prompthub.order.infra.persistence.order.OrderPersistence;
import com.prompthub.order.presentation.dto.response.OrderProductDownloadResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static com.prompthub.order.fixture.PaymentEventFixture.APPROVED_AT;
import static com.prompthub.order.fixture.PaymentEventFixture.BUYER_ID;
import static com.prompthub.order.fixture.PaymentEventFixture.ORDER_A;
import static com.prompthub.order.fixture.PaymentEventFixture.ORDER_PRODUCT_A;
import static com.prompthub.order.fixture.PaymentEventFixture.PRODUCT_A;
import static com.prompthub.order.fixture.PaymentEventFixture.createdOrder;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willAnswer;
import static org.mockito.Mockito.reset;

@SpringBootTest
@ActiveProfiles("test")
class DownloadRefundConcurrencyIntegrationTest {

	private static final long WAIT_SECONDS = 5;

	@Autowired
	private ConfirmDownloadCommandHandler confirmDownloadCommandHandler;
	@Autowired
	private OrderRefundService orderRefundService;
	@Autowired
	private OrderPersistence orderPersistence;

	@MockitoBean
	private ProductClient productClient;
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
		orderPersistence.deleteAll();
		reset(productClient, orderExpirationStore, outboxEventAppender);
	}

	@Test
	void downloadWinsLock_refundSeesDownloadedProductAndIsRejected() throws Exception {
		savePaidOrder();
		CountDownLatch downloadHoldingLock = new CountDownLatch(1);
		CountDownLatch releaseDownload = new CountDownLatch(1);
		given(productClient.getProductContent(PRODUCT_A)).willAnswer(invocation -> {
			downloadHoldingLock.countDown();
			releaseDownload.await(WAIT_SECONDS, TimeUnit.SECONDS);
			return new ProductContent(PRODUCT_A, "content");
		});

		Future<OrderProductDownloadResponse> downloadFuture = executor.submit(() ->
			confirmDownloadCommandHandler.confirmDownload(BUYER_ID, ORDER_A, ORDER_PRODUCT_A)
		);
		assertThat(downloadHoldingLock.await(WAIT_SECONDS, TimeUnit.SECONDS)).isTrue();
		Future<?> refundFuture = executor.submit(() ->
			orderRefundService.requestRefund(BUYER_ID, ORDER_A, List.of(ORDER_PRODUCT_A))
		);
		assertThat(refundFuture.isDone()).isFalse();

		releaseDownload.countDown();
		assertThat(downloadFuture.get(WAIT_SECONDS, TimeUnit.SECONDS).downloaded()).isTrue();
		assertOrderException(refundFuture, ErrorCode.ORDER_REFUND_NOT_ALLOWED);

		Order reloaded = reloadOrder();
		assertThat(reloaded.getOrderStatus()).isEqualTo(OrderStatus.COMPLETED);
		assertThat(findProduct(reloaded).getOrderStatus()).isEqualTo(OrderProductStatus.PAID);
		assertThat(findProduct(reloaded).isDownloaded()).isTrue();
	}

	@Test
	void refundWinsLock_downloadSeesRequestedProductAndIsRejected() throws Exception {
		savePaidOrder();
		CountDownLatch refundHoldingLock = new CountDownLatch(1);
		CountDownLatch releaseRefund = new CountDownLatch(1);
		willAnswer(invocation -> {
			refundHoldingLock.countDown();
			releaseRefund.await(WAIT_SECONDS, TimeUnit.SECONDS);
			return null;
		}).given(outboxEventAppender).append(any());

		Future<?> refundFuture = executor.submit(() ->
			orderRefundService.requestRefund(BUYER_ID, ORDER_A, List.of(ORDER_PRODUCT_A))
		);
		assertThat(refundHoldingLock.await(WAIT_SECONDS, TimeUnit.SECONDS)).isTrue();
		Future<OrderProductDownloadResponse> downloadFuture = executor.submit(() ->
			confirmDownloadCommandHandler.confirmDownload(BUYER_ID, ORDER_A, ORDER_PRODUCT_A)
		);
		assertThat(downloadFuture.isDone()).isFalse();

		releaseRefund.countDown();
		refundFuture.get(WAIT_SECONDS, TimeUnit.SECONDS);
		assertOrderException(downloadFuture, ErrorCode.ORDER_CONTENT_ACCESS_DENIED);

		Order reloaded = reloadOrder();
		assertThat(reloaded.getOrderStatus()).isEqualTo(OrderStatus.REFUND_REQUESTED);
		assertThat(findProduct(reloaded).getOrderStatus()).isEqualTo(OrderProductStatus.REFUND_REQUESTED);
	}

	private void savePaidOrder() {
		Order order = createdOrder();
		order.markCompleted(APPROVED_AT);
		orderPersistence.saveAndFlush(order);
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

	private void assertOrderException(Future<?> future, ErrorCode errorCode) throws Exception {
		try {
			future.get(WAIT_SECONDS, TimeUnit.SECONDS);
			throw new AssertionError("OrderException expected");
		} catch (ExecutionException exception) {
			assertThat(exception.getCause())
				.isInstanceOf(OrderException.class)
				.hasFieldOrPropertyWithValue("errorCode", errorCode);
		}
	}
}
