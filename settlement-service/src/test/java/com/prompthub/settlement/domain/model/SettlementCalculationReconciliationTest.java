package com.prompthub.settlement.domain.model;

import static org.assertj.core.api.Assertions.assertThat;

import com.prompthub.settlement.domain.model.enums.SettlementCalculationReconciliationStatus;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

class SettlementCalculationReconciliationTest {

    private static final UUID BATCH_ID =
            UUID.fromString("00000000-0000-0000-0000-000000000101");
    private static final UUID SETTLEMENT_ID =
            UUID.fromString("00000000-0000-0000-0000-000000000102");
    private static final UUID SALE_SOURCE_ID =
            UUID.fromString("00000000-0000-0000-0000-000000000103");
    private static final UUID REFUND_SOURCE_ID =
            UUID.fromString("00000000-0000-0000-0000-000000000104");
    private static final LocalDateTime VERIFIED_AT =
            LocalDateTime.of(2026, 7, 28, 10, 30);

    @Test
    @DisplayName("정산 요약·상세 합계와 source line 연결이 모두 일치하면 대사에 성공한다")
    void verify_matchingCalculation_returnsMatched() {
        SettlementCalculationReconciliation result = verify(
                summary(1, "100.00", "40.00", "9.00", "51.00"),
                matchingDetails(),
                List.of(SALE_SOURCE_ID, REFUND_SOURCE_ID));

        assertThat(result.getStatus())
                .isEqualTo(SettlementCalculationReconciliationStatus.MATCHED);
        assertThat(result.isMatched()).isTrue();
        assertThat(result.getFailureReason()).isNull();
        assertThat(result.getExpectedProductCount()).isEqualTo(1);
        assertThat(result.getActualProductCount()).isEqualTo(1);
        assertThat(result.getExpectedSourceLineCount()).isEqualTo(2);
        assertThat(result.getActualDetailCount()).isEqualTo(2);
        assertThat(result.getActualLinkedSourceLineCount()).isEqualTo(2);
        assertThat(result.getVerifiedAt()).isEqualTo(VERIFIED_AT);
    }

    @Test
    @DisplayName("SALE 상세 건수와 productCount가 다르면 실패한다")
    void verify_productCountMismatch_returnsMismatched() {
        SettlementCalculationReconciliation result = verify(
                summary(2, "100.00", "40.00", "9.00", "51.00"),
                matchingDetails(),
                List.of(SALE_SOURCE_ID, REFUND_SOURCE_ID));

        assertMismatch(result, "productCount");
    }

    @Test
    @DisplayName("SALE 원금 합계와 totalAmount가 다르면 실패한다")
    void verify_totalAmountMismatch_returnsMismatched() {
        SettlementCalculationReconciliation result = verify(
                summary(1, "101.00", "40.00", "9.00", "51.00"),
                matchingDetails(),
                List.of(SALE_SOURCE_ID, REFUND_SOURCE_ID));

        assertMismatch(result, "totalAmount");
    }

    @Test
    @DisplayName("REFUND 원금 절댓값과 refundAmount가 다르면 실패한다")
    void verify_refundAmountMismatch_returnsMismatched() {
        SettlementCalculationReconciliation result = verify(
                summary(1, "100.00", "41.00", "9.00", "51.00"),
                matchingDetails(),
                List.of(SALE_SOURCE_ID, REFUND_SOURCE_ID));

        assertMismatch(result, "refundAmount");
    }

    @Test
    @DisplayName("상세 수수료 합계와 feeTotalAmount가 다르면 실패한다")
    void verify_feeTotalAmountMismatch_returnsMismatched() {
        SettlementCalculationReconciliation result = verify(
                summary(1, "100.00", "40.00", "10.00", "51.00"),
                matchingDetails(),
                List.of(SALE_SOURCE_ID, REFUND_SOURCE_ID));

        assertMismatch(result, "feeTotalAmount");
    }

    @Test
    @DisplayName("상세 정산액 합계와 settlementTotalAmount가 다르면 실패한다")
    void verify_settlementTotalAmountMismatch_returnsMismatched() {
        SettlementCalculationReconciliation result = verify(
                summary(1, "100.00", "40.00", "9.00", "52.00"),
                matchingDetails(),
                List.of(SALE_SOURCE_ID, REFUND_SOURCE_ID));

        assertMismatch(result, "settlementTotalAmount");
    }

    @Test
    @DisplayName("source line에 대응하는 detail이 누락되면 실패한다")
    void verify_missingDetailForSourceLine_returnsMismatched() {
        SettlementDetail sale = sale(SALE_SOURCE_ID, "100.00");

        SettlementCalculationReconciliation result = verify(
                summary(1, "100.00", "0.00", "15.00", "85.00"),
                List.of(sale),
                List.of(SALE_SOURCE_ID, REFUND_SOURCE_ID));

        assertMismatch(result, "source line 연결 누락");
    }

    @Test
    @DisplayName("하나의 source line이 여러 detail에 연결되면 실패한다")
    void verify_duplicateDetailLink_returnsMismatched() {
        List<SettlementDetail> duplicated = List.of(
                sale(SALE_SOURCE_ID, "100.00"),
                sale(SALE_SOURCE_ID, "200.00"));

        SettlementCalculationReconciliation result = verify(
                summary(2, "300.00", "0.00", "45.00", "255.00"),
                duplicated,
                List.of(SALE_SOURCE_ID));

        assertMismatch(result, "source line 연결 중복");
    }

    @Test
    @DisplayName("detail의 source line 연결이 NULL이면 실패한다")
    void verify_nullSourceLineLink_returnsMismatched() {
        SettlementDetail detail = sale(SALE_SOURCE_ID, "100.00");
        ReflectionTestUtils.setField(detail, "settlementSourceLineId", null);

        SettlementCalculationReconciliation result = verify(
                summary(1, "100.00", "0.00", "15.00", "85.00"),
                List.of(detail),
                List.of(SALE_SOURCE_ID));

        assertMismatch(result, "source line 연결 NULL");
    }

    private SettlementCalculationReconciliation verify(
            SettlementCalculationSummary expected,
            List<SettlementDetail> details,
            List<UUID> sourceLineIds) {
        return SettlementCalculationReconciliation.verify(
                BATCH_ID,
                SETTLEMENT_ID,
                expected,
                details,
                sourceLineIds,
                VERIFIED_AT);
    }

    private SettlementCalculationSummary summary(
            int productCount,
            String totalAmount,
            String refundAmount,
            String feeTotalAmount,
            String settlementTotalAmount) {
        return new SettlementCalculationSummary(
                productCount,
                new BigDecimal(totalAmount),
                new BigDecimal(refundAmount),
                new BigDecimal(feeTotalAmount),
                new BigDecimal(settlementTotalAmount));
    }

    private List<SettlementDetail> matchingDetails() {
        return List.of(
                sale(SALE_SOURCE_ID, "100.00"),
                SettlementDetail.refund(
                        REFUND_SOURCE_ID,
                        UUID.randomUUID(),
                        new BigDecimal("40.00"),
                        new BigDecimal("0.15"),
                        VERIFIED_AT.minusDays(1)));
    }

    private SettlementDetail sale(UUID sourceLineId, String amount) {
        return SettlementDetail.sale(
                sourceLineId,
                UUID.randomUUID(),
                new BigDecimal(amount),
                new BigDecimal("0.15"),
                VERIFIED_AT.minusDays(2));
    }

    private void assertMismatch(
            SettlementCalculationReconciliation result,
            String reasonFragment) {
        assertThat(result.getStatus())
                .isEqualTo(SettlementCalculationReconciliationStatus.MISMATCHED);
        assertThat(result.isMatched()).isFalse();
        assertThat(result.getFailureReason()).contains(reasonFragment);
    }
}
