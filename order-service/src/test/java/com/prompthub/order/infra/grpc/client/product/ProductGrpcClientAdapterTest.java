package com.prompthub.order.infra.grpc.client.product;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.prompthub.exception.BusinessException;
import com.prompthub.product.grpc.GetCartSnapshotsRequest;
import com.prompthub.product.grpc.GetCartSnapshotsResponse;
import com.prompthub.product.grpc.GetOrderSnapshotsRequest;
import com.prompthub.product.grpc.GetOrderSnapshotsResponse;
import com.prompthub.product.grpc.GetProductContentRequest;
import com.prompthub.product.grpc.GetProductContentResponse;
import com.prompthub.product.grpc.ProductCartSnapshotMessage;
import com.prompthub.product.grpc.ProductQueryServiceGrpc;
import com.prompthub.order.application.dto.ProductCartSnapshot;
import com.prompthub.order.application.dto.ProductContent;
import com.prompthub.order.application.dto.ProductOrderSnapshot;
import com.prompthub.order.global.exception.ErrorCode;
import io.github.resilience4j.bulkhead.Bulkhead;
import io.github.resilience4j.bulkhead.BulkheadConfig;
import io.github.resilience4j.bulkhead.BulkheadRegistry;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.grpc.ManagedChannel;
import io.grpc.Status;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.inprocess.InProcessServerBuilder;
import io.grpc.stub.StreamObserver;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import io.grpc.Server;

class ProductGrpcClientAdapterTest {

	private static final UUID PRODUCT_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
	private static final UUID SECOND_PRODUCT_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");
	private static final UUID SELLER_ID = UUID.fromString("33333333-3333-3333-3333-333333333333");
	private static final UUID BUYER_ID = UUID.fromString("44444444-4444-4444-4444-444444444444");

	private Server server;
	private ManagedChannel channel;

	@AfterEach
	void tearDown() {
		if (channel != null) {
			channel.shutdownNow();
		}
		if (server != null) {
			server.shutdownNow();
		}
	}

	@Test
	void mapsOrderSnapshotsResponse() throws IOException {
		ProductGrpcClientAdapter adapter = adapterWith(new ProductQueryServiceGrpc.ProductQueryServiceImplBase() {
			@Override
			public void getOrderSnapshots(
				GetOrderSnapshotsRequest request,
				StreamObserver<GetOrderSnapshotsResponse> responseObserver
			) {
				assertThat(request.getProductIdsList()).containsExactly(PRODUCT_ID.toString(), SECOND_PRODUCT_ID.toString());
				responseObserver.onNext(GetOrderSnapshotsResponse.newBuilder()
					.addProducts(orderSnapshot(PRODUCT_ID))
					.addProducts(orderSnapshot(SECOND_PRODUCT_ID))
					.build());
				responseObserver.onCompleted();
			}
		});

		List<ProductOrderSnapshot> snapshots = adapter.getOrderSnapshots(List.of(PRODUCT_ID, SECOND_PRODUCT_ID));

		assertThat(snapshots).extracting(ProductOrderSnapshot::productId)
			.containsExactly(PRODUCT_ID, SECOND_PRODUCT_ID);
		assertThat(snapshots.getFirst().sellerId()).isEqualTo(SELLER_ID);
		assertThat(snapshots.getFirst().title()).isEqualTo("테스트 상품");
		assertThat(snapshots.getFirst().productType()).isEqualTo("PROMPT");
		assertThat(snapshots.getFirst().amount()).isEqualTo(10000);
		assertThat(snapshots.getFirst().model()).isEqualTo("GPT-4");
	}

	@Test
	void mapsCartSnapshotResponse() throws IOException {
		ProductGrpcClientAdapter adapter = adapterWith(new ProductQueryServiceGrpc.ProductQueryServiceImplBase() {
			public void getCartSnapshots(
				GetCartSnapshotsRequest request,
				StreamObserver<GetCartSnapshotsResponse> responseObserver
			) {
				assertThat(request.getProductIdsList()).containsExactly(PRODUCT_ID.toString());
				responseObserver.onNext(GetCartSnapshotsResponse.newBuilder()
					.addProducts(cartSnapshot(PRODUCT_ID))
					.build());
				responseObserver.onCompleted();
			}
		});

		ProductCartSnapshot snapshot = adapter.getCartSnapshot(PRODUCT_ID);

		assertThat(snapshot.productId()).isEqualTo(PRODUCT_ID);
		assertThat(snapshot.sellerId()).isEqualTo(SELLER_ID);
		assertThat(snapshot.sellerNickname()).isEqualTo("판매자");
		assertThat(snapshot.status()).isEqualTo("ON_SALE");
	}

	@Test
	void mapsCartSnapshotsResponse() throws IOException {
		ProductGrpcClientAdapter adapter = adapterWith(new ProductQueryServiceGrpc.ProductQueryServiceImplBase() {
			@Override
			public void getCartSnapshots(
				GetCartSnapshotsRequest request,
				StreamObserver<GetCartSnapshotsResponse> responseObserver
			) {
				assertThat(request.getProductIdsList()).containsExactly(PRODUCT_ID.toString(), SECOND_PRODUCT_ID.toString());
				responseObserver.onNext(GetCartSnapshotsResponse.newBuilder()
					.addProducts(cartSnapshot(PRODUCT_ID))
					.addProducts(cartSnapshot(SECOND_PRODUCT_ID))
					.build());
				responseObserver.onCompleted();
			}
		});

		List<ProductCartSnapshot> snapshots = adapter.getCartSnapshots(List.of(PRODUCT_ID, SECOND_PRODUCT_ID));

		assertThat(snapshots).extracting(ProductCartSnapshot::productId)
			.containsExactly(PRODUCT_ID, SECOND_PRODUCT_ID);
	}

	@Test
	void mapsProductContentResponse() throws IOException {
		ProductGrpcClientAdapter adapter = adapterWith(new ProductQueryServiceGrpc.ProductQueryServiceImplBase() {
			@Override
			public void getProductContent(
				GetProductContentRequest request,
				StreamObserver<GetProductContentResponse> responseObserver
			) {
				assertThat(request.getProductId()).isEqualTo(PRODUCT_ID.toString());
				responseObserver.onNext(GetProductContentResponse.newBuilder()
					.setProductId(PRODUCT_ID.toString())
					.setContent("구매 콘텐츠")
					.build());
				responseObserver.onCompleted();
			}
		});

		ProductContent content = adapter.getProductContent(PRODUCT_ID);

		assertThat(content.productId()).isEqualTo(PRODUCT_ID);
		assertThat(content.content()).isEqualTo("구매 콘텐츠");
	}


	@Test
	void mapsGrpcFailureToBusinessException() throws IOException {
		ProductGrpcClientAdapter adapter = adapterWith(new ProductQueryServiceGrpc.ProductQueryServiceImplBase() {
			public void getCartSnapshots(
				GetCartSnapshotsRequest request,
				StreamObserver<GetCartSnapshotsResponse> responseObserver
			) {
				responseObserver.onError(Status.UNAVAILABLE.asRuntimeException());
			}
		});

		assertThatThrownBy(() -> adapter.getCartSnapshot(PRODUCT_ID))
			.isInstanceOf(BusinessException.class)
			.extracting(exception -> ((BusinessException) exception).getErrorCode())
			.isEqualTo(ErrorCode.PRODUCT_SERVICE_UNAVAILABLE);
	}

	@Test
	void mapsAllSystemGrpcStatusesToProductServiceUnavailableAndRecordsFailures() throws IOException {
		AtomicReference<Status> status = new AtomicReference<>(Status.UNAVAILABLE);
		ProductGrpcResilience resilience = resilience(20, 10, 20);
		ProductGrpcClientAdapter adapter = adapterWith(new ProductQueryServiceGrpc.ProductQueryServiceImplBase() {
			@Override
			public void getProductContent(
				GetProductContentRequest request,
				StreamObserver<GetProductContentResponse> responseObserver
			) {
				responseObserver.onError(status.get().asRuntimeException());
			}
		}, 2_000, resilience);

		for (Status systemStatus : List.of(
			Status.UNAVAILABLE,
			Status.DEADLINE_EXCEEDED,
			Status.RESOURCE_EXHAUSTED,
			Status.INTERNAL,
			Status.UNKNOWN
		)) {
			status.set(systemStatus);
			assertThatThrownBy(() -> adapter.getProductContent(PRODUCT_ID))
				.isInstanceOf(BusinessException.class)
				.extracting(exception -> ((BusinessException) exception).getErrorCode())
				.isEqualTo(ErrorCode.PRODUCT_SERVICE_UNAVAILABLE);
		}

		assertThat(resilience.productQueryGrpc().getMetrics().getNumberOfFailedCalls()).isEqualTo(5);
	}

	@Test
	void mapsNotFoundToProductNotFoundWithoutRecordingCircuitBreakerFailure() throws IOException {
		ProductGrpcResilience resilience = resilience(20, 2, 2);
		ProductGrpcClientAdapter adapter = adapterWith(new ProductQueryServiceGrpc.ProductQueryServiceImplBase() {
			@Override
			public void getProductContent(
				GetProductContentRequest request,
				StreamObserver<GetProductContentResponse> responseObserver
			) {
				responseObserver.onError(Status.NOT_FOUND.asRuntimeException());
			}
		}, 2000, resilience);

		assertThatThrownBy(() -> adapter.getProductContent(PRODUCT_ID))
			.isInstanceOf(BusinessException.class)
			.extracting(exception -> ((BusinessException) exception).getErrorCode())
			.isEqualTo(ErrorCode.PRODUCT_NOT_FOUND);
		assertThat(resilience.productQueryGrpc().getMetrics().getNumberOfFailedCalls()).isZero();
	}

	@Test
	void mapsAllExcludedGrpcStatusesToTheirDomainErrors() throws IOException {
		AtomicReference<Status> status = new AtomicReference<>(Status.NOT_FOUND);
		ProductGrpcResilience resilience = resilience(20, 10, 20);
		ProductGrpcClientAdapter adapter = adapterWith(new ProductQueryServiceGrpc.ProductQueryServiceImplBase() {
			@Override
			public void getProductContent(
				GetProductContentRequest request,
				StreamObserver<GetProductContentResponse> responseObserver
			) {
				responseObserver.onError(status.get().asRuntimeException());
			}
		}, 2000, resilience);

		assertMappedError(adapter, status, Status.NOT_FOUND, ErrorCode.PRODUCT_NOT_FOUND);
		assertMappedError(adapter, status, Status.INVALID_ARGUMENT, ErrorCode.PRODUCT_REQUEST_INVALID);
		assertMappedError(adapter, status, Status.ALREADY_EXISTS, ErrorCode.PRODUCT_OPERATION_CONFLICT);
		assertMappedError(adapter, status, Status.UNAUTHENTICATED, ErrorCode.PRODUCT_SERVICE_UNAUTHENTICATED);
		assertMappedError(adapter, status, Status.PERMISSION_DENIED, ErrorCode.PRODUCT_SERVICE_ACCESS_DENIED);
		assertThat(resilience.productQueryGrpc().getMetrics().getNumberOfFailedCalls()).isZero();
		assertThat(resilience.productQueryGrpc().getState()).isEqualTo(CircuitBreaker.State.CLOSED);
	}

	@Test
	void opensCircuitBreakerAndSkipsFurtherGrpcCalls() throws IOException {
		ListAppender<ILoggingEvent> appender = startLogCapture();
		setRequestId("request-circuit-open");
		AtomicInteger calls = new AtomicInteger();
		ProductGrpcResilience resilience = resilience(20, 2, 2);
		ProductGrpcClientAdapter adapter = adapterWith(new ProductQueryServiceGrpc.ProductQueryServiceImplBase() {
			@Override
			public void getProductContent(
				GetProductContentRequest request,
				StreamObserver<GetProductContentResponse> responseObserver
			) {
				calls.incrementAndGet();
				responseObserver.onError(Status.UNAVAILABLE.asRuntimeException());
			}
		}, 2000, resilience);

		try {
			assertThatThrownBy(() -> adapter.getProductContent(PRODUCT_ID)).isInstanceOf(BusinessException.class);
			assertThatThrownBy(() -> adapter.getProductContent(PRODUCT_ID)).isInstanceOf(BusinessException.class);
			assertThat(resilience.productQueryGrpc().getState()).isEqualTo(CircuitBreaker.State.OPEN);

			assertThatThrownBy(() -> adapter.getProductContent(PRODUCT_ID)).isInstanceOf(BusinessException.class);
			assertThat(calls).hasValue(2);
			assertFailureLog(appender, "request-circuit-open", "GetProductContent", "productQueryGrpc", "circuit_open");
		} finally {
			stopLogCapture(appender);
		}
	}

	@Test
	void keepsOrderCircuitBreakerClosedWhenQueryCircuitBreakerOpens() throws IOException {
		AtomicInteger orderCalls = new AtomicInteger();
		ProductGrpcResilience resilience = resilience(20, 2, 2);
		ProductGrpcClientAdapter adapter = adapterWith(new ProductQueryServiceGrpc.ProductQueryServiceImplBase() {
			@Override
			public void getProductContent(
				GetProductContentRequest request,
				StreamObserver<GetProductContentResponse> responseObserver
			) {
				responseObserver.onError(Status.UNAVAILABLE.asRuntimeException());
			}

			@Override
			public void getOrderSnapshots(
				GetOrderSnapshotsRequest request,
				StreamObserver<GetOrderSnapshotsResponse> responseObserver
			) {
				orderCalls.incrementAndGet();
				responseObserver.onNext(GetOrderSnapshotsResponse.newBuilder()
					.addProducts(orderSnapshot(PRODUCT_ID))
					.build());
				responseObserver.onCompleted();
			}
		}, 2000, resilience);

		assertThatThrownBy(() -> adapter.getProductContent(PRODUCT_ID)).isInstanceOf(BusinessException.class);
		assertThatThrownBy(() -> adapter.getProductContent(PRODUCT_ID)).isInstanceOf(BusinessException.class);
		assertThat(resilience.productQueryGrpc().getState()).isEqualTo(CircuitBreaker.State.OPEN);

		assertThat(adapter.getOrderSnapshots(List.of(PRODUCT_ID))).hasSize(1);
		assertThat(resilience.productOrderGrpc().getState()).isEqualTo(CircuitBreaker.State.CLOSED);
		assertThat(orderCalls).hasValue(1);
	}

	@Test
	void keepsQueryCircuitBreakerClosedWhenOrderCircuitBreakerOpens() throws IOException {
		AtomicInteger cartCalls = new AtomicInteger();
		ProductGrpcResilience resilience = resilience(20, 2, 2);
		ProductGrpcClientAdapter adapter = adapterWith(new ProductQueryServiceGrpc.ProductQueryServiceImplBase() {
			@Override
			public void getOrderSnapshots(
				GetOrderSnapshotsRequest request,
				StreamObserver<GetOrderSnapshotsResponse> responseObserver
			) {
				responseObserver.onError(Status.UNAVAILABLE.asRuntimeException());
			}

			@Override
			public void getCartSnapshots(
				GetCartSnapshotsRequest request,
				StreamObserver<GetCartSnapshotsResponse> responseObserver
			) {
				cartCalls.incrementAndGet();
				responseObserver.onNext(GetCartSnapshotsResponse.newBuilder().addProducts(cartSnapshot(PRODUCT_ID)).build());
				responseObserver.onCompleted();
			}
		}, 2_000, resilience);

		assertThatThrownBy(() -> adapter.getOrderSnapshots(List.of(PRODUCT_ID))).isInstanceOf(BusinessException.class);
		assertThatThrownBy(() -> adapter.getOrderSnapshots(List.of(PRODUCT_ID))).isInstanceOf(BusinessException.class);
		assertThat(resilience.productOrderGrpc().getState()).isEqualTo(CircuitBreaker.State.OPEN);

		assertThat(adapter.getCartSnapshots(List.of(PRODUCT_ID))).hasSize(1);
		assertThat(resilience.productQueryGrpc().getState()).isEqualTo(CircuitBreaker.State.CLOSED);
		assertThat(cartCalls).hasValue(1);
	}

	@Test
	void rejectsImmediatelyWhenSharedBulkheadIsFull() throws Exception {
		ListAppender<ILoggingEvent> appender = startLogCapture();
		setRequestId("request-bulkhead-full");
		CountDownLatch entered = new CountDownLatch(20);
		CountDownLatch release = new CountDownLatch(1);
		ProductGrpcResilience resilience = resilience(20, 10, 20);
		ProductGrpcClientAdapter adapter = adapterWith(new ProductQueryServiceGrpc.ProductQueryServiceImplBase() {
			@Override
			public void getProductContent(
				GetProductContentRequest request,
				StreamObserver<GetProductContentResponse> responseObserver
			) {
				entered.countDown();
				try {
					release.await();
				} catch (InterruptedException exception) {
					Thread.currentThread().interrupt();
				}
				responseObserver.onNext(GetProductContentResponse.newBuilder()
					.setProductId(PRODUCT_ID.toString())
					.setContent("구매 콘텐츠")
					.build());
				responseObserver.onCompleted();
			}
		}, 2000, resilience);

		ExecutorService executor = Executors.newFixedThreadPool(20);
		try {
			List<Future<ProductContent>> runningCalls = java.util.stream.IntStream.range(0, 20)
				.mapToObj(ignored -> executor.submit(() -> adapter.getProductContent(PRODUCT_ID)))
				.toList();
			assertThat(entered.await(1, TimeUnit.SECONDS)).isTrue();

			assertThatThrownBy(() -> adapter.getCartSnapshots(List.of(PRODUCT_ID)))
				.isInstanceOf(BusinessException.class)
				.extracting(exception -> ((BusinessException) exception).getErrorCode())
				.isEqualTo(ErrorCode.PRODUCT_SERVICE_UNAVAILABLE);
			assertThat(resilience.productQueryGrpc().getMetrics().getNumberOfFailedCalls()).isZero();
			assertFailureLog(appender, "request-bulkhead-full", "GetCartSnapshots", "productQueryGrpc", "bulkhead_full");

			release.countDown();
			for (Future<ProductContent> runningCall : runningCalls) {
				assertThat(runningCall.get(1, TimeUnit.SECONDS).productId()).isEqualTo(PRODUCT_ID);
			}
			assertThat(adapter.getProductContent(PRODUCT_ID).productId()).isEqualTo(PRODUCT_ID);
		} finally {
			release.countDown();
			executor.shutdownNow();
			stopLogCapture(appender);
		}
	}

	@Test
	void appliesDeadlineToAllProductGrpcMethodsWithoutRetry() throws IOException {
		AtomicInteger orderCalls = new AtomicInteger();
		AtomicInteger cartCalls = new AtomicInteger();
		AtomicInteger contentCalls = new AtomicInteger();
		ProductGrpcResilience resilience = resilience(20, 10, 20);
		ProductGrpcClientAdapter adapter = adapterWith(new ProductQueryServiceGrpc.ProductQueryServiceImplBase() {
			@Override
			public void getOrderSnapshots(GetOrderSnapshotsRequest request, StreamObserver<GetOrderSnapshotsResponse> observer) {
				orderCalls.incrementAndGet();
				delay();
			}

			@Override
			public void getCartSnapshots(GetCartSnapshotsRequest request, StreamObserver<GetCartSnapshotsResponse> observer) {
				cartCalls.incrementAndGet();
				delay();
			}

			@Override
			public void getProductContent(GetProductContentRequest request, StreamObserver<GetProductContentResponse> observer) {
				contentCalls.incrementAndGet();
				delay();
			}

			private void delay() {
				try {
					Thread.sleep(300);
				} catch (InterruptedException exception) {
					Thread.currentThread().interrupt();
				}
			}
		}, 50, resilience);

		assertThatThrownBy(() -> adapter.getOrderSnapshots(List.of(PRODUCT_ID))).isInstanceOf(BusinessException.class);
		assertThatThrownBy(() -> adapter.getCartSnapshots(List.of(PRODUCT_ID))).isInstanceOf(BusinessException.class);
		assertThatThrownBy(() -> adapter.getProductContent(PRODUCT_ID)).isInstanceOf(BusinessException.class);
		assertThat(orderCalls).hasValue(1);
		assertThat(cartCalls).hasValue(1);
		assertThat(contentCalls).hasValue(1);
		assertThat(resilience.productOrderGrpc().getMetrics().getNumberOfFailedCalls()).isEqualTo(1);
		assertThat(resilience.productQueryGrpc().getMetrics().getNumberOfFailedCalls()).isEqualTo(2);
	}

	@Test
	@DisplayName("gRPC 서버 지연으로 인해 Deadline(Timeout) 초과 시 BusinessException 예외가 발생한다")
	void mapsDeadlineExceededToBusinessException() throws IOException {
		ListAppender<ILoggingEvent> appender = startLogCapture();
		setRequestId("request-deadline");
		ProductGrpcClientAdapter adapter = adapterWith(new ProductQueryServiceGrpc.ProductQueryServiceImplBase() {
			@Override
			public void getCartSnapshots(
				GetCartSnapshotsRequest request,
				StreamObserver<GetCartSnapshotsResponse> responseObserver
			) {
				try {
					Thread.sleep(500); // 500ms 지연
				} catch (InterruptedException e) {
					Thread.currentThread().interrupt();
				}
				responseObserver.onNext(GetCartSnapshotsResponse.newBuilder()
					.addProducts(cartSnapshot(PRODUCT_ID))
					.build());
				responseObserver.onCompleted();
			}
		}, 100); // 클라이언트 deadline을 100ms로 설정

		try {
			assertThatThrownBy(() -> adapter.getCartSnapshot(PRODUCT_ID))
				.isInstanceOf(BusinessException.class);
			assertFailureLog(appender, "request-deadline", "GetCartSnapshots", "productQueryGrpc", "deadline_exceeded");
		} finally {
			stopLogCapture(appender);
		}
	}

	private ProductGrpcClientAdapter adapterWith(ProductQueryServiceGrpc.ProductQueryServiceImplBase service)
		throws IOException {
		return adapterWith(service, 2000);
	}

	private ProductGrpcClientAdapter adapterWith(ProductQueryServiceGrpc.ProductQueryServiceImplBase service, int deadlineMs)
		throws IOException {
		return adapterWith(service, deadlineMs, resilience(20, 10, 20));
	}

	private ProductGrpcClientAdapter adapterWith(
		ProductQueryServiceGrpc.ProductQueryServiceImplBase service,
		int deadlineMs,
		ProductGrpcResilience resilience
	) throws IOException {
		String serverName = InProcessServerBuilder.generateName();
		server = InProcessServerBuilder.forName(serverName)
			.directExecutor()
			.addService(service)
			.build()
			.start();
		channel = InProcessChannelBuilder.forName(serverName)
			.directExecutor()
			.build();
		return new ProductGrpcClientAdapter(ProductQueryServiceGrpc.newBlockingStub(channel), deadlineMs, resilience);
	}

	private ProductGrpcResilience resilience(int maxConcurrentCalls, int minimumNumberOfCalls, int slidingWindowSize) {
		CircuitBreakerConfig circuitBreakerConfig = CircuitBreakerConfig.custom()
			.slidingWindowSize(slidingWindowSize)
			.minimumNumberOfCalls(minimumNumberOfCalls)
			.failureRateThreshold(50)
			.slowCallDurationThreshold(Duration.ofMillis(700))
			.slowCallRateThreshold(50)
			.waitDurationInOpenState(Duration.ofSeconds(30))
			.permittedNumberOfCallsInHalfOpenState(3)
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
		return new ProductGrpcResilience(
			productOrderGrpc,
			productQueryGrpc,
			productGrpcBulkhead,
			circuitBreakerRegistry,
			bulkheadRegistry
		);
	}

	private void assertMappedError(
		ProductGrpcClientAdapter adapter,
		AtomicReference<Status> status,
		Status grpcStatus,
		ErrorCode expectedErrorCode
	) {
		status.set(grpcStatus);
		assertThatThrownBy(() -> adapter.getProductContent(PRODUCT_ID))
			.isInstanceOf(BusinessException.class)
			.extracting(exception -> ((BusinessException) exception).getErrorCode())
			.isEqualTo(expectedErrorCode);
	}

	private ListAppender<ILoggingEvent> startLogCapture() {
		Logger logger = (Logger) LoggerFactory.getLogger(ProductGrpcClientAdapter.class);
		ListAppender<ILoggingEvent> appender = new ListAppender<>();
		appender.start();
		logger.addAppender(appender);
		return appender;
	}

	private void stopLogCapture(ListAppender<ILoggingEvent> appender) {
		Logger logger = (Logger) LoggerFactory.getLogger(ProductGrpcClientAdapter.class);
		logger.detachAppender(appender);
		appender.stop();
		RequestContextHolder.resetRequestAttributes();
	}

	private void setRequestId(String requestId) {
		MockHttpServletRequest request = new MockHttpServletRequest();
		request.addHeader("X-Request-Id", requestId);
		RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
	}

	private void assertFailureLog(
		ListAppender<ILoggingEvent> appender,
		String requestId,
		String grpcMethod,
		String circuitBreakerName,
		String failureReason
	) {
		assertThat(appender.list)
			.extracting(ILoggingEvent::getFormattedMessage)
			.anySatisfy(message -> {
				assertThat(message).contains("requestId=" + requestId);
				assertThat(message).contains("grpcMethod=" + grpcMethod);
				assertThat(message).contains("circuitBreakerName=" + circuitBreakerName);
				assertThat(message).contains("failureReason=" + failureReason);
			});
	}

	private com.prompthub.product.grpc.ProductOrderSnapshot orderSnapshot(UUID productId) {
		return com.prompthub.product.grpc.ProductOrderSnapshot.newBuilder()
			.setProductId(productId.toString())
			.setSellerId(SELLER_ID.toString())
			.setTitle("테스트 상품")
			.setProductType("PROMPT")
			.setAmount(10000)
			.setModel("GPT-4")
			.build();
	}

	private ProductCartSnapshotMessage cartSnapshot(UUID productId) {
		return ProductCartSnapshotMessage.newBuilder()
			.setProductId(productId.toString())
			.setSellerId(SELLER_ID.toString())
			.setSellerNickname("판매자")
			.setTitle("테스트 상품")
			.setProductType("PROMPT")
			.setAmount(10000)
			.setThumbnailUrl("https://example.com/thumb.png")
			.build();
	}
}
