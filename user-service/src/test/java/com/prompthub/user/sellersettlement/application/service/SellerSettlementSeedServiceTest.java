package com.prompthub.user.sellersettlement.application.service;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;

import com.prompthub.user.sellersettlement.application.event.SettlementCreatedMessage;
import com.prompthub.user.sellersettlement.domain.model.SellerSettlement;
import com.prompthub.user.sellersettlement.domain.repository.SellerSettlementRepository;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class SellerSettlementSeedServiceTest {

    @Mock
    private SellerSettlementRepository sellerSettlementRepository;

    @InjectMocks
    private SellerSettlementApplicationService service;

    private SettlementCreatedMessage message(UUID settlementId) {
        return new SettlementCreatedMessage(
                settlementId, UUID.randomUUID(),
                LocalDate.of(2026, 6, 1), LocalDate.of(2026, 6, 30),
                1, new BigDecimal("100.00"), new BigDecimal("85.00"),
                new BigDecimal("15.00"), BigDecimal.ZERO, LocalDateTime.of(2026, 7, 1, 4, 0));
    }

    @Test
    void seed_처음이면_저장한다() {
        UUID settlementId = UUID.randomUUID();
        given(sellerSettlementRepository.existsBySettlementId(settlementId)).willReturn(false);

        service.seed(message(settlementId));

        then(sellerSettlementRepository).should().save(any(SellerSettlement.class));
    }

    @Test
    void seed_이미_있으면_무시한다() {
        UUID settlementId = UUID.randomUUID();
        given(sellerSettlementRepository.existsBySettlementId(settlementId)).willReturn(true);

        service.seed(message(settlementId));

        then(sellerSettlementRepository).should(never()).save(any());
    }
}
