package com.prompthub.settlement.application.service.delivery;

import static org.assertj.core.api.Assertions.assertThat;

import com.prompthub.settlement.application.dto.delivery.SellerSettlementRegistrationCommand;
import com.prompthub.settlement.application.dto.delivery.SellerSettlementRegistrationCommand.Detail;
import com.prompthub.settlement.application.dto.delivery.SellerSettlementStoredSnapshot;
import com.prompthub.settlement.domain.model.calculation.SettlementLineType;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class SettlementDeliveryReconcilerTest {

    private final SettlementDeliveryReconciler reconciler =
            new SettlementDeliveryReconciler();

    @Test
    void 금액_scale과_detail_순서가_달라도_일치한다() {
        SellerSettlementRegistrationCommand expected = command();
        SellerSettlementStoredSnapshot actual = snapshot(expected, true, "15.000");

        var result = reconciler.compare(expected, actual);

        assertThat(result.matched()).isTrue();
        assertThat(result.reason()).isNull();
    }

    @Test
    void 모든_필드를_비교하고_첫_불일치와_전체_건수를_남긴다() {
        SellerSettlementRegistrationCommand expected = command();
        SellerSettlementStoredSnapshot actual = snapshot(expected, false, "14.00");

        var result = reconciler.compare(expected, actual);

        assertThat(result.matched()).isFalse();
        assertThat(result.mismatchCount()).isEqualTo(2);
        assertThat(result.reason()).contains("deliveryRequestId 불일치")
                .contains("mismatchCount=2");
    }

    private SellerSettlementRegistrationCommand command() {
        UUID requestId = UUID.randomUUID();
        UUID settlementId = UUID.randomUUID();
        return new SellerSettlementRegistrationCommand(
                requestId, settlementId, UUID.randomUUID(),
                LocalDate.of(2026, 7, 1), LocalDate.of(2026, 7, 7), 1,
                new BigDecimal("100.00"), BigDecimal.ZERO,
                new BigDecimal("15.00"), new BigDecimal("85.00"),
                LocalDateTime.of(2026, 7, 8, 2, 0, 0, 123456000),
                List.of(new Detail(
                        UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                        SettlementLineType.SALE,
                        new BigDecimal("100.00"), new BigDecimal("0.1500"),
                        new BigDecimal("15.00"), new BigDecimal("85.00"),
                        LocalDateTime.of(2026, 7, 3, 12, 0))));
    }

    private SellerSettlementStoredSnapshot snapshot(
            SellerSettlementRegistrationCommand expected,
            boolean sameRequest,
            String feeTotal) {
        Detail detail = expected.details().getFirst();
        return new SellerSettlementStoredSnapshot(
                sameRequest ? expected.deliveryRequestId() : UUID.randomUUID(),
                expected.settlementId(), expected.sellerId(),
                expected.periodStart(), expected.periodEnd(), expected.productCount(),
                new BigDecimal("100.0"), BigDecimal.ZERO,
                new BigDecimal(feeTotal), new BigDecimal("85.000"),
                expected.calculatedAt().plusNanos(999),
                List.of(new SellerSettlementStoredSnapshot.Detail(
                        detail.settlementDetailId(), detail.settlementSourceLineId(),
                        detail.orderProductId(), detail.lineType(),
                        detail.lineAmount(), detail.feeRate(), detail.feeAmount(),
                        detail.lineSettlementAmount(), detail.occurredAt())));
    }
}
