package com.prompthub.admin.settlement.service;

import com.prompthub.admin.global.exception.AdminErrorCode;
import com.prompthub.admin.global.exception.AdminException;
import com.prompthub.admin.settlement.dto.SettlementDeliveryListQuery;
import com.prompthub.admin.settlement.dto.response.SettlementDeliveryListResponse;
import com.prompthub.admin.settlement.dto.response.SettlementDeliverySummaryResponse;
import com.prompthub.admin.settlement.entity.SettlementDelivery;
import com.prompthub.admin.settlement.infrastructure.kubernetes.SettlementDeliveryRetryJobAlreadyExistsException;
import com.prompthub.admin.settlement.infrastructure.kubernetes.SettlementDeliveryRetryJobClient;
import com.prompthub.admin.settlement.repository.SettlementDeliveryQueryRepository;
import com.prompthub.admin.settlement.repository.SettlementDeliveryQueryRepository.DeliveryPage;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class SettlementDeliveryService {

    private final SettlementDeliveryQueryRepository repository;
    private final SettlementDeliveryRetryJobClient retryJobClient;

    public SettlementDeliveryListResponse getList(
            SettlementDeliveryListQuery query) {
        DeliveryPage page = repository.findPage(query);
        Set<UUID> activeDeliveryIds = retryJobClient.findActiveDeliveryIds();
        return SettlementDeliveryListResponse.from(
                page.content(),
                page.totalElements(),
                query.page(),
                query.size(),
                activeDeliveryIds);
    }

    public SettlementDeliverySummaryResponse getSummary() {
        Set<UUID> activeDeliveryIds = retryJobClient.findActiveDeliveryIds();
        return SettlementDeliverySummaryResponse.from(
                repository.countByStatus(),
                activeDeliveryIds.size());
    }

    public void retry(UUID settlementDeliveryId) {
        SettlementDelivery delivery = repository.findById(settlementDeliveryId)
                .orElseThrow(() ->
                        new AdminException(
                                AdminErrorCode.SETTLEMENT_DELIVERY_NOT_FOUND));
        if (!delivery.canRetry()) {
            throw new AdminException(
                    AdminErrorCode.SETTLEMENT_DELIVERY_RETRY_NOT_ALLOWED);
        }
        if (retryJobClient.isActive(settlementDeliveryId)) {
            throw new AdminException(
                    AdminErrorCode.SETTLEMENT_DELIVERY_RETRY_ALREADY_RUNNING);
        }
        try {
            retryJobClient.launch(
                    settlementDeliveryId,
                    delivery.getAttemptCount());
        } catch (SettlementDeliveryRetryJobAlreadyExistsException exception) {
            throw new AdminException(
                    AdminErrorCode.SETTLEMENT_DELIVERY_RETRY_ALREADY_RUNNING);
        }
    }
}
