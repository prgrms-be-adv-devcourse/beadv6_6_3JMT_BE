package com.prompthub.user.sellersettlement.application.service;

import com.prompthub.user.sellersettlement.application.dto.RegisteredSellerSettlementSnapshot;
import com.prompthub.user.sellersettlement.domain.exception.SellerSettlementNotFoundException;
import com.prompthub.user.sellersettlement.domain.repository.SellerSettlementRepository;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class SellerSettlementReadBackApplicationService {

    private final SellerSettlementRepository repository;

    @Transactional(readOnly = true)
    public RegisteredSellerSettlementSnapshot readBySettlementId(UUID settlementId) {
        return repository.findBySettlementId(settlementId)
                .map(RegisteredSellerSettlementSnapshot::from)
                .orElseThrow(SellerSettlementNotFoundException::new);
    }

    @Transactional(readOnly = true)
    public Optional<RegisteredSellerSettlementSnapshot> findByDeliveryRequestId(
            UUID deliveryRequestId) {
        return repository.findByDeliveryRequestId(deliveryRequestId)
                .map(RegisteredSellerSettlementSnapshot::from);
    }

    @Transactional(readOnly = true)
    public Optional<RegisteredSellerSettlementSnapshot> findBySettlementId(
            UUID settlementId) {
        return repository.findBySettlementId(settlementId)
                .map(RegisteredSellerSettlementSnapshot::from);
    }
}
