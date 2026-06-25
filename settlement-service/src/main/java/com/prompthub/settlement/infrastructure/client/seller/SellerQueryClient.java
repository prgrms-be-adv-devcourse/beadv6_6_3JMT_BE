package com.prompthub.settlement.infrastructure.client.seller;

import com.prompthub.settlement.application.port.SellerQueryPort;
import com.prompthub.settlement.grpc.seller.SellerBatchQueryRequest;
import com.prompthub.settlement.grpc.seller.SellerBatchQueryResponse;
import com.prompthub.settlement.grpc.seller.SellerInfo;
import com.prompthub.settlement.grpc.seller.SellerQueryServiceGrpc.SellerQueryServiceBlockingStub;
import io.grpc.StatusRuntimeException;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class SellerQueryClient implements SellerQueryPort {

    private final SellerQueryServiceBlockingStub sellerQueryStub;

    @Override
    public Map<UUID, String> findSellerNames(List<UUID> sellerIds) {
        if (sellerIds.isEmpty()) {
            return Map.of();
        }
        SellerBatchQueryRequest request = SellerBatchQueryRequest.newBuilder()
                .addAllSellerIds(sellerIds.stream().map(UUID::toString).toList())
                .build();
        try {
            SellerBatchQueryResponse response = sellerQueryStub.findSellers(request);
            return response.getSellersList().stream()
                    .filter(seller -> !seller.getSellerName().isEmpty())
                    .collect(Collectors.toMap(
                            seller -> UUID.fromString(seller.getSellerId()), SellerInfo::getSellerName,
                            (existing, ignored) -> existing));
        } catch (StatusRuntimeException exception) {
            log.warn("판매자명 gRPC 조회에 실패해 빈 결과로 대체합니다. sellerIds={}", sellerIds, exception);
            return Map.of();
        }
    }
}
