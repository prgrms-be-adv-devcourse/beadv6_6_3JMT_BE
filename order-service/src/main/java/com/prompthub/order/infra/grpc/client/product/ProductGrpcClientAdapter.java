package com.prompthub.order.infra.grpc.client.product;

import com.prompthub.exception.BusinessException;
import com.prompthub.grpc.product.v1.GetCartSnapshotsRequest;
import com.prompthub.grpc.product.v1.GetOrderSnapshotsRequest;
import com.prompthub.grpc.product.v1.GetProductContentRequest;
import com.prompthub.grpc.product.v1.ProductCartSnapshotMessage;
import com.prompthub.grpc.product.v1.ProductInternalServiceGrpc;
import com.prompthub.order.application.client.ProductClient;
import com.prompthub.order.application.dto.ProductCartSnapshot;
import com.prompthub.order.application.dto.ProductContent;
import com.prompthub.order.application.dto.ProductOrderSnapshot;
import com.prompthub.order.global.exception.ErrorCode;
import io.github.resilience4j.bulkhead.Bulkhead;
import io.github.resilience4j.bulkhead.BulkheadFullException;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.grpc.StatusRuntimeException;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

@Component
@Profile({"dev", "prod"})
@Slf4j
public class ProductGrpcClientAdapter implements ProductClient {

	private static final String ON_SALE = "ON_SALE";
	private static final String PRODUCT_ORDER_GRPC = "productOrderGrpc";
	private static final String PRODUCT_QUERY_GRPC = "productQueryGrpc";

	private final ProductInternalServiceGrpc.ProductInternalServiceBlockingStub stub;
	private final int deadlineMs;
	private final ProductGrpcResilience resilience;

	public ProductGrpcClientAdapter(
		ProductInternalServiceGrpc.ProductInternalServiceBlockingStub stub,
		@Value("${prompthub.grpc.product.deadline-ms:1000}") int deadlineMs,
		ProductGrpcResilience resilience
	) {
		this.stub = stub;
		this.deadlineMs = deadlineMs;
		this.resilience = resilience;
	}

	@Override
	public List<ProductOrderSnapshot> getOrderSnapshots(List<UUID> productIds) {
		return execute(PRODUCT_ORDER_GRPC, "GetOrderSnapshots", () ->
			withDeadline().getOrderSnapshots(GetOrderSnapshotsRequest.newBuilder()
					.addAllProductIds(toStrings(productIds))
					.build())
				.getProductsList()
				.stream()
				.map(this::toOrderSnapshot)
				.toList()
		);
	}

	@Override
	public ProductCartSnapshot getCartSnapshot(UUID productId) {
		return execute(PRODUCT_QUERY_GRPC, "GetCartSnapshots", () -> {
			var response = withDeadline().getCartSnapshots(GetCartSnapshotsRequest.newBuilder()
					.addProductIds(productId.toString())
					.build())
				.getProductsList();
			if (response.isEmpty()) {
				throw new BusinessException(ErrorCode.PRODUCT_NOT_FOUND);
			}
			return toCartSnapshot(response.getFirst());
		});
	}

	@Override
	public List<ProductCartSnapshot> getCartSnapshots(List<UUID> productIds) {
		return execute(PRODUCT_QUERY_GRPC, "GetCartSnapshots", () ->
			withDeadline().getCartSnapshots(GetCartSnapshotsRequest.newBuilder()
					.addAllProductIds(toStrings(productIds))
					.build())
				.getProductsList()
				.stream()
				.map(this::toCartSnapshot)
				.toList()
		);
	}

	@Override
	public ProductContent getProductContent(UUID productId) {
		return execute(PRODUCT_QUERY_GRPC, "GetProductContent", () -> {
			var response = withDeadline().getProductContent(GetProductContentRequest.newBuilder()
				.setProductId(productId.toString())
				.build());
			return new ProductContent(UUID.fromString(response.getProductId()), response.getContent());
		});
	}

	private <T> T execute(String circuitBreakerName, String grpcMethod, Supplier<T> supplier) {
		CircuitBreaker circuitBreaker = resilience.circuitBreaker(circuitBreakerName);
		Bulkhead bulkhead = resilience.productGrpcBulkhead();
		long startedAt = System.nanoTime();

		try {
			return CircuitBreaker.decorateSupplier(
				circuitBreaker,
				Bulkhead.decorateSupplier(bulkhead, supplier)
			).get();
		} catch (CallNotPermittedException exception) {
			logFailure(circuitBreakerName, grpcMethod, "circuit_open", null, startedAt);
			throw productServiceUnavailable();
		} catch (BulkheadFullException exception) {
			logFailure(circuitBreakerName, grpcMethod, "bulkhead_full", null, startedAt);
			throw productServiceUnavailable();
		} catch (StatusRuntimeException exception) {
			logFailure(circuitBreakerName, grpcMethod, failureReason(exception), exception, startedAt);
			throw mapGrpcException(exception);
		}
	}

	private ProductInternalServiceGrpc.ProductInternalServiceBlockingStub withDeadline() {
		return stub.withDeadlineAfter(deadlineMs, TimeUnit.MILLISECONDS);
	}

	private List<String> toStrings(List<UUID> values) {
		return values.stream()
			.map(UUID::toString)
			.toList();
	}

	private ProductOrderSnapshot toOrderSnapshot(com.prompthub.grpc.product.v1.ProductOrderSnapshot response) {
		return new ProductOrderSnapshot(
			UUID.fromString(response.getProductId()),
			UUID.fromString(response.getSellerId()),
			response.getTitle(),
			response.getProductType(),
			response.getModel(),
			response.getAmount()
		);
	}

	private ProductCartSnapshot toCartSnapshot(ProductCartSnapshotMessage response) {
		return new ProductCartSnapshot(
			UUID.fromString(response.getProductId()),
			response.getTitle(),
			response.getProductType(),
			response.getAmount(),
			response.getThumbnailUrl(),
			UUID.fromString(response.getSellerId()),
			response.getSellerNickname(),
			ON_SALE
		);
	}

	private BusinessException productServiceUnavailable() {
		return new BusinessException(ErrorCode.PRODUCT_SERVICE_UNAVAILABLE);
	}

	private BusinessException mapGrpcException(StatusRuntimeException exception) {
		return switch (exception.getStatus().getCode()) {
			case NOT_FOUND -> new BusinessException(ErrorCode.PRODUCT_NOT_FOUND);
			case INVALID_ARGUMENT -> new BusinessException(ErrorCode.PRODUCT_REQUEST_INVALID);
			case ALREADY_EXISTS -> new BusinessException(ErrorCode.PRODUCT_OPERATION_CONFLICT);
			case UNAUTHENTICATED -> new BusinessException(ErrorCode.PRODUCT_SERVICE_UNAUTHENTICATED);
			case PERMISSION_DENIED -> new BusinessException(ErrorCode.PRODUCT_SERVICE_ACCESS_DENIED);
			default -> productServiceUnavailable();
		};
	}

	private String failureReason(StatusRuntimeException exception) {
		return switch (exception.getStatus().getCode()) {
			case DEADLINE_EXCEEDED -> "deadline_exceeded";
			case UNAVAILABLE -> "unavailable";
			case RESOURCE_EXHAUSTED -> "resource_exhausted";
			case INTERNAL -> "internal";
			case UNKNOWN -> "unknown";
			default -> "grpc_" + exception.getStatus().getCode().name().toLowerCase();
		};
	}

	private void logFailure(
		String circuitBreakerName,
		String grpcMethod,
		String failureReason,
		StatusRuntimeException exception,
		long startedAt
	) {
		long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt);
		String grpcStatus = exception == null ? "N/A" : exception.getStatus().getCode().name();
		String requestId = requestId();
		log.warn(
			"Product gRPC call failed. requestId={}, circuitBreakerName={}, bulkheadName=productGrpcBulkhead, "
				+ "grpcMethod={}, grpcStatus={}, failureReason={}, elapsedMs={}, circuitState={}",
			requestId, circuitBreakerName, grpcMethod, grpcStatus, failureReason, elapsedMs,
			resilience.circuitBreaker(circuitBreakerName).getState()
		);
	}

	private String requestId() {
		if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attributes) {
			String requestId = attributes.getRequest().getHeader("X-Request-Id");
			return requestId == null || requestId.isBlank() ? "요청 ID 없음" : requestId;
		}

		return "요청 ID 없음";
	}
}
