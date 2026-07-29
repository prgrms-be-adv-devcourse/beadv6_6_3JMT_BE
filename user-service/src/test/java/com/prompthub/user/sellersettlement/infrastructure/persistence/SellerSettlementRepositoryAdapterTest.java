package com.prompthub.user.sellersettlement.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;

import com.prompthub.user.sellersettlement.domain.model.enums.SettlementDisplayStatus;
import java.math.BigDecimal;
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
    void sumTotalAmountBySeller_지급완료_정산만_집계한다() {
        UUID sellerId = UUID.randomUUID();
        given(jpaRepository.sumTotalAmountBySellerAndStatus(
                sellerId, SettlementDisplayStatus.PAID)).willReturn(new BigDecimal("170000"));

        BigDecimal totalAmount = adapter.sumTotalAmountBySeller(sellerId);

        assertThat(totalAmount).isEqualByComparingTo("170000");
        then(jpaRepository).should().sumTotalAmountBySellerAndStatus(
                sellerId, SettlementDisplayStatus.PAID);
    }
}
