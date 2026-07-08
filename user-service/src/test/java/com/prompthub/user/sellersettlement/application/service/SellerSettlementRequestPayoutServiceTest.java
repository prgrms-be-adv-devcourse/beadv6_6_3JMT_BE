package com.prompthub.user.sellersettlement.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.lenient;

import com.prompthub.user.sellersettlement.application.dto.SellerSettlementResult;
import com.prompthub.user.sellersettlement.domain.exception.SellerSettlementAccessDeniedException;
import com.prompthub.user.sellersettlement.domain.exception.SellerSettlementInvalidStateException;
import com.prompthub.user.sellersettlement.domain.exception.SellerSettlementNotFoundException;
import com.prompthub.user.sellersettlement.domain.model.SellerSettlement;
import com.prompthub.user.sellersettlement.domain.model.SettlementDisplayStatus;
import com.prompthub.user.sellersettlement.domain.repository.SellerSettlementRepository;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class SellerSettlementRequestPayoutServiceTest {

    @Mock
    private SellerSettlementRepository sellerSettlementRepository;

    @InjectMocks
    private SellerSettlementApplicationService service;

    private SellerSettlement rowOf(UUID sellerId) {
        return SellerSettlement.seed(
                UUID.randomUUID(), sellerId,
                LocalDate.of(2026, 6, 1), LocalDate.of(2026, 6, 30),
                1, new BigDecimal("100.00"), new BigDecimal("85.00"),
                new BigDecimal("15.00"), new BigDecimal("0.00"), LocalDateTime.of(2026, 7, 1, 4, 0));
    }

    @Test
    void requestPayout_APPROVED면_PAYOUT_REQUESTED로_전이하고_저장() {
        UUID sellerId = UUID.randomUUID();
        SellerSettlement row = rowOf(sellerId);
        row.approve();
        UUID settlementId = row.getSettlementId();
        given(sellerSettlementRepository.findBySettlementId(settlementId)).willReturn(Optional.of(row));
        given(sellerSettlementRepository.save(row)).willReturn(row);

        SellerSettlementResult result = service.requestPayout(sellerId, settlementId);

        assertThat(result.status()).isEqualTo(SettlementDisplayStatus.PAYOUT_REQUESTED);
    }

    @Test
    void requestPayout_없으면_NotFound() {
        UUID settlementId = UUID.randomUUID();
        given(sellerSettlementRepository.findBySettlementId(settlementId)).willReturn(Optional.empty());

        assertThatThrownBy(() -> service.requestPayout(UUID.randomUUID(), settlementId))
                .isInstanceOf(SellerSettlementNotFoundException.class);
    }

    @Test
    void requestPayout_본인이_아니면_AccessDenied() {
        SellerSettlement row = rowOf(UUID.randomUUID());
        row.approve();
        UUID settlementId = row.getSettlementId();
        given(sellerSettlementRepository.findBySettlementId(settlementId)).willReturn(Optional.of(row));

        assertThatThrownBy(() -> service.requestPayout(UUID.randomUUID(), settlementId))
                .isInstanceOf(SellerSettlementAccessDeniedException.class);
    }

    @Test
    void requestPayout_APPROVED가_아니면_InvalidState() {
        UUID sellerId = UUID.randomUUID();
        SellerSettlement row = rowOf(sellerId);
        UUID settlementId = row.getSettlementId();
        given(sellerSettlementRepository.findBySettlementId(settlementId)).willReturn(Optional.of(row));
        lenient().when(sellerSettlementRepository.save(row)).thenReturn(row);

        assertThatThrownBy(() -> service.requestPayout(sellerId, settlementId))
                .isInstanceOf(SellerSettlementInvalidStateException.class);
    }
}
