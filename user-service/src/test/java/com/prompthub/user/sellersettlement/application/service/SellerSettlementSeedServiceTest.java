package com.prompthub.user.sellersettlement.application.service;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;

import static org.assertj.core.api.Assertions.assertThat;

import com.prompthub.user.sellersettlement.application.event.SettlementCreatedEventV1;
import com.prompthub.user.sellersettlement.application.event.SettlementCreatedEventV2;
import com.prompthub.user.sellersettlement.application.event.SettlementDetailEvent;
import com.prompthub.user.sellersettlement.domain.model.SellerSettlement;
import com.prompthub.user.sellersettlement.domain.model.SellerSettlementDetail;
import com.prompthub.user.sellersettlement.domain.repository.SellerSettlementRepository;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.ArgumentCaptor;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class SellerSettlementSeedServiceTest {

    @Mock
    private SellerSettlementRepository sellerSettlementRepository;

    @InjectMocks
    private SellerSettlementApplicationService service;

    private SettlementCreatedEventV1 v1Event(UUID settlementId) {
        return new SettlementCreatedEventV1(
                null, settlementId, UUID.randomUUID(),
                LocalDate.of(2026, 6, 1), LocalDate.of(2026, 6, 30),
                1, new BigDecimal("100.00"), new BigDecimal("85.00"),
                new BigDecimal("15.00"), BigDecimal.ZERO, LocalDateTime.of(2026, 7, 1, 4, 0));
    }

    private SettlementCreatedEventV2 v2Event(
            UUID settlementId, UUID firstDetailId, UUID secondDetailId) {
        UUID orderProductId = UUID.randomUUID();
        return new SettlementCreatedEventV2(
                2, settlementId, UUID.randomUUID(),
                LocalDate.of(2026, 6, 1), LocalDate.of(2026, 6, 30),
                1, new BigDecimal("100.00"), new BigDecimal("51.00"),
                new BigDecimal("9.00"), new BigDecimal("40.00"),
                LocalDateTime.of(2026, 7, 1, 4, 0),
                List.of(
                        new SettlementDetailEvent(
                                firstDetailId, orderProductId, "SALE",
                                new BigDecimal("100.00"), new BigDecimal("0.1500"),
                                new BigDecimal("15.00"), new BigDecimal("85.00"),
                                LocalDateTime.of(2026, 6, 10, 12, 0)),
                        new SettlementDetailEvent(
                                secondDetailId, orderProductId, "REFUND",
                                new BigDecimal("-40.00"), new BigDecimal("0.1500"),
                                new BigDecimal("-6.00"), new BigDecimal("-34.00"),
                                LocalDateTime.of(2026, 6, 12, 12, 0))));
    }

    @Test
    void seed_처음이면_저장한다() {
        UUID settlementId = UUID.randomUUID();
        given(sellerSettlementRepository.existsBySettlementId(settlementId)).willReturn(false);

        service.seed(v1Event(settlementId));

        then(sellerSettlementRepository).should().save(any(SellerSettlement.class));
    }

    @Test
    void V2_seed는_부모와_원본_UUID를_가진_Detail을_함께_저장한다() {
        UUID settlementId = UUID.randomUUID();
        UUID firstDetailId = UUID.randomUUID();
        UUID secondDetailId = UUID.randomUUID();
        given(sellerSettlementRepository.existsBySettlementId(settlementId)).willReturn(false);

        service.seed(v2Event(settlementId, firstDetailId, secondDetailId));

        ArgumentCaptor<SellerSettlement> captor = ArgumentCaptor.forClass(SellerSettlement.class);
        then(sellerSettlementRepository).should().save(captor.capture());
        SellerSettlement saved = captor.getValue();
        assertThat(saved.getPayloadVersion()).isEqualTo((short) 2);
        assertThat(saved.getDetails())
                .extracting(SellerSettlementDetail::getSettlementDetailId)
                .containsExactly(firstDetailId, secondDetailId);
    }

    @Test
    void seed_이미_있으면_무시한다() {
        UUID settlementId = UUID.randomUUID();
        given(sellerSettlementRepository.existsBySettlementId(settlementId)).willReturn(true);

        service.seed(v1Event(settlementId));

        then(sellerSettlementRepository).should(never()).save(any());
    }

    @Test
    void V2_seed도_이미_있으면_Detail을_다시_저장하지_않는다() {
        UUID settlementId = UUID.randomUUID();
        given(sellerSettlementRepository.existsBySettlementId(settlementId)).willReturn(true);

        service.seed(v2Event(settlementId, UUID.randomUUID(), UUID.randomUUID()));

        then(sellerSettlementRepository).should(never()).save(any());
    }
}
