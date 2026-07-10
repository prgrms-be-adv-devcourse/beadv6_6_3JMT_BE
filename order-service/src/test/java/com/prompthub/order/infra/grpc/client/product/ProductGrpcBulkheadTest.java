package com.prompthub.order.infra.grpc.client.product;

import com.prompthub.exception.BusinessException;
import com.prompthub.grpc.product.v1.GetCartSnapshotsRequest;
import com.prompthub.grpc.product.v1.GetCartSnapshotsResponse;
import com.prompthub.grpc.product.v1.GetOrderSnapshotsRequest;
import com.prompthub.grpc.product.v1.GetOrderSnapshotsResponse;
import com.prompthub.grpc.product.v1.GetProductContentRequest;
import com.prompthub.grpc.product.v1.GetProductContentResponse;
import com.prompthub.grpc.product.v1.ProductCartSnapshotMessage;
import com.prompthub.grpc.product.v1.ProductInternalServiceGrpc;
import com.prompthub.order.application.dto.ProductContent;
import com.prompthub.order.global.exception.ErrorCode;
import io.github.resilience4j.bulkhead.Bulkhead;
import io.github.resilience4j.bulkhead.BulkheadConfig;
import io.github.resilience4j.bulkhead.BulkheadRegistry;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.grpc.ManagedChannel;
import io.grpc.Server;
import io.grpc.Status;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.inprocess.InProcessServerBuilder;
import io.grpc.stub.StreamObserver;
import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ProductGrpcBulkheadTest {

	private static final UUID PRODUCT_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
	private static final UUID SELLER_ID = UUID.fromString("33333333-3333-3333-3333-333333333333");

	private Server server;
	private ManagedChannel channel;
	private ExecutorService executor;

	@AfterEach
	void tearDown() throws InterruptedException {
		if (executor != null) {
			executor.shutdownNow();
			executor.awaitTermination(1, TimeUnit.SECONDS);
		}
		if (channel != null) {
			channel.shutdownNow();
		}
		if (server != null) {
			server.shutdownNow();
		}
	}

	@Test
	void sharesTwentyPermitsAcrossOrderCartAndContentCallsAndRejectsTheTwentyFirstImmediately() throws Exception {
		CountDownLatch entered = new CountDownLatch(20);
		CountDownLatch release = new CountDownLatch(1);
		AtomicInteger orderCalls = new AtomicInteger();
		AtomicInteger cartCalls = new AtomicInteger();
		AtomicInteger contentCalls = new AtomicInteger();
		ProductGrpcResilience resilience = resilience(20);
		ProductGrpcClientAdapter adapter = adapterWith(blockingService(
			entered, release, orderCalls, cartCalls, contentCalls, Outcome.SUCCESS
		), 1_000, resilience);
		executor = Executors.newFixedThreadPool(20);

		List<Future<?>> runningCalls = new ArrayList<>();
		for (int count = 0; count < 5; count++) {
			runningCalls.add(executor.submit(() -> adapter.getOrderSnapshots(List.of(PRODUCT_ID))));
		}
		for (int count = 0; count < 10; count++) {
			runningCalls.add(executor.submit(() -> adapter.getCartSnapshots(List.of(PRODUCT_ID))));
		}
		for (int count = 0; count < 5; count++) {
			runningCalls.add(executor.submit(() -> adapter.getProductContent(PRODUCT_ID)));
		}

		assertThat(entered.await(1, TimeUnit.SECONDS)).isTrue();
		assertThat(resilience.productGrpcBulkhead().getMetrics().getAvailableConcurrentCalls()).isZero();
		assertThat(orderCalls).hasValue(5);
		assertThat(cartCalls).hasValue(10);
		assertThat(contentCalls).hasValue(5);

		assertThatThrownBy(() -> adapter.getProductContent(PRODUCT_ID))
			.isInstanceOf(BusinessException.class)
			.extracting(exception -> ((BusinessException) exception).getErrorCode())
			.isEqualTo(ErrorCode.PRODUCT_SERVICE_UNAVAILABLE);
		assertThat(contentCalls).hasValue(5);
		assertThat(resilience.productQueryGrpc().getMetrics().getNumberOfFailedCalls()).isZero();

		release.countDown();
		for (Future<?> runningCall : runningCalls) {
			runningCall.get(1, TimeUnit.SECONDS);
		}
		assertThat(resilience.productGrpcBulkhead().getMetrics().getAvailableConcurrentCalls()).isEqualTo(20);
	}

	@Test
	void returnsPermitAfterSuccessfulGrpcCall() throws Exception {
		assertPermitIsReturned(Outcome.SUCCESS, 1_000);
	}

	@Test
	void returnsPermitAfterUnavailableGrpcCall() throws Exception {
		assertPermitIsReturned(Outcome.UNAVAILABLE, 1_000);
	}

	@Test
	void returnsPermitAfterDeadlineExceededGrpcCall() throws Exception {
		assertPermitIsReturned(Outcome.DEADLINE, 50);
	}

	private void assertPermitIsReturned(Outcome outcome, int deadlineMs) throws Exception {
		AtomicInteger contentCalls = new AtomicInteger();
		ProductGrpcResilience resilience = resilience(1);
		ProductGrpcClientAdapter adapter = adapterWith(blockingService(
			new CountDownLatch(0), new CountDownLatch(0), new AtomicInteger(), new AtomicInteger(), contentCalls, outcome
		), deadlineMs, resilience);

		if (outcome == Outcome.SUCCESS) {
			assertThat(adapter.getProductContent(PRODUCT_ID).productId()).isEqualTo(PRODUCT_ID);
		} else {
			assertThatThrownBy(() -> adapter.getProductContent(PRODUCT_ID))
				.isInstanceOf(BusinessException.class)
				.extracting(exception -> ((BusinessException) exception).getErrorCode())
				.isEqualTo(ErrorCode.PRODUCT_SERVICE_UNAVAILABLE);
		}

		assertThat(resilience.productGrpcBulkhead().getMetrics().getAvailableConcurrentCalls()).isEqualTo(1);
		if (outcome == Outcome.SUCCESS) {
			assertThat(adapter.getProductContent(PRODUCT_ID).productId()).isEqualTo(PRODUCT_ID);
		} else {
			assertThatThrownBy(() -> adapter.getProductContent(PRODUCT_ID)).isInstanceOf(BusinessException.class);
		}
		assertThat(contentCalls).hasValue(2);
	}

	private ProductGrpcClientAdapter adapterWith(
		ProductInternalServiceGrpc.ProductInternalServiceImplBase service,
		int deadlineMs,
		ProductGrpcResilience resilience
	) throws IOException {
		String serverName = InProcessServerBuilder.generateName();
		server = InProcessServerBuilder.forName(serverName).directExecutor().addService(service).build().start();
		channel = InProcessChannelBuilder.forName(serverName).directExecutor().build();
		return new ProductGrpcClientAdapter(ProductInternalServiceGrpc.newBlockingStub(channel), deadlineMs, resilience);
	}

	private ProductInternalServiceGrpc.ProductInternalServiceImplBase blockingService(
		CountDownLatch entered,
		CountDownLatch release,
		AtomicInteger orderCalls,
		AtomicInteger cartCalls,
		AtomicInteger contentCalls,
		Outcome outcome
	) {
		return new ProductInternalServiceGrpc.ProductInternalServiceImplBase() {
			@Override
			public void getOrderSnapshots(GetOrderSnapshotsRequest request, StreamObserver<GetOrderSnapshotsResponse> observer) {
				orderCalls.incrementAndGet();
				complete(entered, release, outcome, observer, GetOrderSnapshotsResponse.newBuilder()
					.addProducts(com.prompthub.grpc.product.v1.ProductOrderSnapshot.newBuilder()
						.setProductId(PRODUCT_ID.toString()).setSellerId(SELLER_ID.toString()).setTitle("product")
						.setProductType("PROMPT").setAmount(1000).setModel("GPT-4")).build());
			}

			@Override
			public void getCartSnapshots(GetCartSnapshotsRequest request, StreamObserver<GetCartSnapshotsResponse> observer) {
				cartCalls.incrementAndGet();
				complete(entered, release, outcome, observer, GetCartSnapshotsResponse.newBuilder()
					.addProducts(ProductCartSnapshotMessage.newBuilder().setProductId(PRODUCT_ID.toString())
						.setSellerId(SELLER_ID.toString()).setSellerNickname("seller").setTitle("product")
						.setProductType("PROMPT").setAmount(1000)).build());
			}

			@Override
			public void getProductContent(GetProductContentRequest request, StreamObserver<GetProductContentResponse> observer) {
				contentCalls.incrementAndGet();
				complete(entered, release, outcome, observer, GetProductContentResponse.newBuilder()
					.setProductId(PRODUCT_ID.toString()).setContent("content").build());
			}
		};
	}

	private <T> void complete(
		CountDownLatch entered,
		CountDownLatch release,
		Outcome outcome,
		StreamObserver<T> observer,
		T response
	) {
		entered.countDown();
		try {
			if (outcome == Outcome.DEADLINE) {
				Thread.sleep(200);
			} else {
				release.await();
			}
		} catch (InterruptedException exception) {
			Thread.currentThread().interrupt();
		}
		if (outcome == Outcome.UNAVAILABLE) {
			observer.onError(Status.UNAVAILABLE.asRuntimeException());
			return;
		}
		observer.onNext(response);
		observer.onCompleted();
	}

	private ProductGrpcResilience resilience(int maxConcurrentCalls) {
		CircuitBreakerConfig circuitBreakerConfig = CircuitBreakerConfig.custom()
			.slidingWindowSize(100)
			.minimumNumberOfCalls(100)
			.failureRateThreshold(50)
			.recordException(new ProductGrpcFailurePredicate())
			.build();
		CircuitBreakerRegistry circuitBreakerRegistry = CircuitBreakerRegistry.of(circuitBreakerConfig);
		BulkheadRegistry bulkheadRegistry = BulkheadRegistry.of(BulkheadConfig.custom()
			.maxConcurrentCalls(maxConcurrentCalls)
			.maxWaitDuration(Duration.ZERO)
			.build());
		CircuitBreaker productOrderGrpc = circuitBreakerRegistry.circuitBreaker("productOrderGrpc");
		CircuitBreaker productQueryGrpc = circuitBreakerRegistry.circuitBreaker("productQueryGrpc");
		Bulkhead productGrpcBulkhead = bulkheadRegistry.bulkhead("productGrpcBulkhead");
		return new ProductGrpcResilience(productOrderGrpc, productQueryGrpc, productGrpcBulkhead,
			circuitBreakerRegistry, bulkheadRegistry);
	}

	private enum Outcome {
		SUCCESS,
		UNAVAILABLE,
		DEADLINE
	}
}
