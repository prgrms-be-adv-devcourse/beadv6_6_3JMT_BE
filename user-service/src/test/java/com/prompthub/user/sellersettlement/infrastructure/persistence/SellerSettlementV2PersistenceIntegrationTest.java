package com.prompthub.user.sellersettlement.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.prompthub.user.global.config.JpaConfig;
import com.prompthub.user.sellersettlement.domain.model.SellerSettlement;
import com.prompthub.user.sellersettlement.domain.model.SellerSettlementDetail;
import com.prompthub.user.sellersettlement.domain.model.enums.SellerSettlementLineType;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jpa.test.autoconfigure.TestEntityManager;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

@DataJpaTest
@Import(JpaConfig.class)
@ActiveProfiles("test")
class SellerSettlementV2PersistenceIntegrationTest {

    @Autowired
    private SellerSettlementJpaRepository repository;

    @Autowired
    private TestEntityManager entityManager;

    @Test
    void V2_부모와_원본_UUID를_가진_Detail을_함께_저장한다() {
        UUID firstDetailId = UUID.randomUUID();
        UUID secondDetailId = UUID.randomUUID();
        UUID orderProductId = UUID.randomUUID();
        SellerSettlement settlement = SellerSettlement.seedV2(
                UUID.randomUUID(), UUID.randomUUID(),
                LocalDate.of(2026, 7, 13), LocalDate.of(2026, 7, 19),
                1, new BigDecimal("100.00"), new BigDecimal("51.00"),
                new BigDecimal("9.00"), new BigDecimal("40.00"),
                LocalDateTime.of(2026, 7, 20, 1, 12),
                List.of(
                        detail(firstDetailId, orderProductId, SellerSettlementLineType.SALE,
                                "100.00", "15.00", "85.00"),
                        detail(secondDetailId, orderProductId, SellerSettlementLineType.REFUND,
                                "-40.00", "-6.00", "-34.00")));

        repository.saveAndFlush(settlement);
        UUID parentId = settlement.getSellerSettlementId();
        entityManager.clear();

        SellerSettlement stored = repository.findById(parentId).orElseThrow();
        assertThat(stored.getPayloadVersion()).isEqualTo((short) 2);
        assertThat(stored.getDetails())
                .extracting(SellerSettlementDetail::getSettlementDetailId)
                .containsExactly(firstDetailId, secondDetailId);
    }

    private SellerSettlementDetail detail(
            UUID detailId,
            UUID orderProductId,
            SellerSettlementLineType lineType,
            String lineAmount,
            String feeAmount,
            String settlementAmount) {
        return SellerSettlementDetail.seed(
                detailId, orderProductId, lineType,
                new BigDecimal(lineAmount), new BigDecimal("0.1500"),
                new BigDecimal(feeAmount), new BigDecimal(settlementAmount),
                LocalDateTime.of(2026, 7, 14, 13, 10));
    }
}
