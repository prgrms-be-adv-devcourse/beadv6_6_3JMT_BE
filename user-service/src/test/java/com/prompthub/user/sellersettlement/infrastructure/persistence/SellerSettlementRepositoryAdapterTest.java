package com.prompthub.user.sellersettlement.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;

import com.prompthub.user.sellersettlement.domain.model.enums.SettlementDisplayStatus;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class SellerSettlementRepositoryAdapterTest {

    @Mock
    private SellerSettlementJpaRepository jpaRepository;

    @InjectMocks
    private SellerSettlementRepositoryAdapter adapter;

    @Test
    void existsBySettlementId_JpaRepository에_위임() {
        UUID settlementId = UUID.randomUUID();
        given(jpaRepository.existsBySettlementId(settlementId)).willReturn(true);

        assertThat(adapter.existsBySettlementId(settlementId)).isTrue();
    }

    @Test
    @DisplayName("deliveryRequestId 조회를 상세 포함 JPA 조회에 위임한다")
    void findByDeliveryRequestIdDelegatesToJpaRepository() {
        UUID deliveryRequestId = UUID.randomUUID();
        given(jpaRepository.findByDeliveryRequestId(deliveryRequestId))
                .willReturn(Optional.empty());

        assertThat(adapter.findByDeliveryRequestId(deliveryRequestId)).isEmpty();

        then(jpaRepository).should().findByDeliveryRequestId(deliveryRequestId);
    }

    @Test
    void sumTotalAmountBySeller_취소를_제외한_상태를_집계한다() {
        UUID sellerId = UUID.randomUUID();
        List<SettlementDisplayStatus> statuses = List.of(
                SettlementDisplayStatus.WAITING,
                SettlementDisplayStatus.APPROVAL_ON_HOLD,
                SettlementDisplayStatus.APPROVED,
                SettlementDisplayStatus.PAYOUT_REQUESTED,
                SettlementDisplayStatus.PAYOUT_ON_HOLD,
                SettlementDisplayStatus.PAID);
        given(jpaRepository.sumTotalAmountBySellerAndStatusIn(sellerId, statuses))
                .willReturn(new BigDecimal("600000"));

        BigDecimal totalAmount = adapter.sumTotalAmountBySeller(sellerId);

        assertThat(totalAmount).isEqualByComparingTo("600000");
        then(jpaRepository).should().sumTotalAmountBySellerAndStatusIn(sellerId, statuses);
    }

    @Test
    void sumApprovedSettlementAmountBySeller_승인_이후_상태를_집계한다() {
        UUID sellerId = UUID.randomUUID();
        List<SettlementDisplayStatus> statuses = List.of(
                SettlementDisplayStatus.APPROVED,
                SettlementDisplayStatus.PAYOUT_REQUESTED,
                SettlementDisplayStatus.PAYOUT_ON_HOLD,
                SettlementDisplayStatus.PAID);
        given(jpaRepository.sumSettlementTotalAmountBySellerAndStatusIn(sellerId, statuses))
                .willReturn(new BigDecimal("340000"));

        BigDecimal settlementAmount =
                adapter.sumApprovedSettlementAmountBySeller(sellerId);

        assertThat(settlementAmount).isEqualByComparingTo("340000");
        then(jpaRepository).should()
                .sumSettlementTotalAmountBySellerAndStatusIn(sellerId, statuses);
    }
}
