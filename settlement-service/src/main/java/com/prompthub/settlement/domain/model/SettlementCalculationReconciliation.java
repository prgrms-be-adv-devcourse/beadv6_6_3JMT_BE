package com.prompthub.settlement.domain.model;

import com.prompthub.settlement.domain.model.enums.SettlementCalculationReconciliationStatus;
import com.prompthub.settlement.domain.model.enums.SettlementLineType;
import com.prompthub.settlement.global.common.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(
        name = "settlement_calculation_reconciliation",
        indexes = @Index(
                name = "idx_settlement_calc_reconciliation_batch_verified",
                columnList = "settlement_batch_id, verified_at"))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class SettlementCalculationReconciliation extends BaseEntity {

    private static final int FAILURE_REASON_MAX_LENGTH = 2_000;

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "reconciliation_id")
    private UUID id;

    @Column(name = "settlement_batch_id", nullable = false, updatable = false)
    private UUID settlementBatchId;

    @Column(name = "settlement_id", nullable = false, updatable = false)
    private UUID settlementId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private SettlementCalculationReconciliationStatus status;

    @Column(name = "expected_product_count", nullable = false)
    private int expectedProductCount;

    @Column(name = "actual_product_count", nullable = false)
    private int actualProductCount;

    @Column(name = "expected_total_amount", nullable = false, precision = 12, scale = 2)
    private BigDecimal expectedTotalAmount;

    @Column(name = "actual_total_amount", nullable = false, precision = 12, scale = 2)
    private BigDecimal actualTotalAmount;

    @Column(name = "expected_refund_amount", nullable = false, precision = 12, scale = 2)
    private BigDecimal expectedRefundAmount;

    @Column(name = "actual_refund_amount", nullable = false, precision = 12, scale = 2)
    private BigDecimal actualRefundAmount;

    @Column(name = "expected_fee_total_amount", nullable = false, precision = 12, scale = 2)
    private BigDecimal expectedFeeTotalAmount;

    @Column(name = "actual_fee_total_amount", nullable = false, precision = 12, scale = 2)
    private BigDecimal actualFeeTotalAmount;

    @Column(name = "expected_settlement_total_amount", nullable = false, precision = 12, scale = 2)
    private BigDecimal expectedSettlementTotalAmount;

    @Column(name = "actual_settlement_total_amount", nullable = false, precision = 12, scale = 2)
    private BigDecimal actualSettlementTotalAmount;

    @Column(name = "expected_source_line_count", nullable = false)
    private int expectedSourceLineCount;

    @Column(name = "actual_detail_count", nullable = false)
    private int actualDetailCount;

    @Column(name = "actual_linked_source_line_count", nullable = false)
    private int actualLinkedSourceLineCount;

    @Column(name = "failure_reason", length = 2000)
    private String failureReason;

    @Column(name = "verified_at", nullable = false, updatable = false)
    private LocalDateTime verifiedAt;

    public static SettlementCalculationReconciliation verify(
            UUID settlementBatchId,
            UUID settlementId,
            SettlementCalculationSummary expected,
            List<SettlementDetail> details,
            List<UUID> sourceLineIds,
            LocalDateTime verifiedAt) {
        Objects.requireNonNull(expected, "expected는 필수입니다.");
        Objects.requireNonNull(details, "details는 필수입니다.");
        Objects.requireNonNull(sourceLineIds, "sourceLineIds는 필수입니다.");

        SettlementCalculationReconciliation result =
                new SettlementCalculationReconciliation();
        result.settlementBatchId = Objects.requireNonNull(
                settlementBatchId, "settlementBatchId는 필수입니다.");
        result.settlementId = Objects.requireNonNull(
                settlementId, "settlementId는 필수입니다.");
        result.expectedProductCount = expected.productCount();
        result.expectedTotalAmount = expected.totalAmount();
        result.expectedRefundAmount = expected.refundAmount();
        result.expectedFeeTotalAmount = expected.feeTotalAmount();
        result.expectedSettlementTotalAmount = expected.settlementTotalAmount();
        result.actualProductCount = (int) details.stream()
                .filter(detail -> detail.getLineType() == SettlementLineType.SALE)
                .count();
        result.actualTotalAmount = sum(
                details.stream()
                        .filter(detail -> detail.getLineType() == SettlementLineType.SALE)
                        .toList(),
                SettlementDetail::getLineAmount);
        result.actualRefundAmount = sum(
                details.stream()
                        .filter(detail -> detail.getLineType() == SettlementLineType.REFUND)
                        .toList(),
                SettlementDetail::getLineAmount).abs();
        result.actualFeeTotalAmount = sum(details, SettlementDetail::getFeeAmount);
        result.actualSettlementTotalAmount = sum(
                details, SettlementDetail::getLineSettlementAmount);
        result.expectedSourceLineCount = sourceLineIds.size();
        result.actualDetailCount = details.size();
        result.actualLinkedSourceLineCount = (int) details.stream()
                .map(SettlementDetail::getSettlementSourceLineId)
                .filter(Objects::nonNull)
                .distinct()
                .count();
        result.verifiedAt = Objects.requireNonNull(verifiedAt, "verifiedAt은 필수입니다.");

        List<String> failures = result.failures(expected, details, sourceLineIds);
        result.status = failures.isEmpty()
                ? SettlementCalculationReconciliationStatus.MATCHED
                : SettlementCalculationReconciliationStatus.MISMATCHED;
        result.failureReason = failures.isEmpty()
                ? null
                : truncate(String.join("; ", failures));
        return result;
    }

    public boolean isMatched() {
        return status == SettlementCalculationReconciliationStatus.MATCHED;
    }

    private List<String> failures(
            SettlementCalculationSummary expected,
            List<SettlementDetail> details,
            List<UUID> sourceLineIds) {
        List<String> failures = new ArrayList<>();
        addMismatch(failures, "productCount", expected.productCount(), actualProductCount);
        addMismatch(failures, "totalAmount", expected.totalAmount(), actualTotalAmount);
        addMismatch(failures, "refundAmount", expected.refundAmount(), actualRefundAmount);
        addMismatch(
                failures,
                "feeTotalAmount",
                expected.feeTotalAmount(),
                actualFeeTotalAmount);
        addMismatch(
                failures,
                "settlementTotalAmount",
                expected.settlementTotalAmount(),
                actualSettlementTotalAmount);
        addSourceLinkFailures(failures, details, sourceLineIds);
        return failures;
    }

    private void addSourceLinkFailures(
            List<String> failures,
            List<SettlementDetail> details,
            List<UUID> sourceLineIds) {
        List<UUID> linkedIds = details.stream()
                .map(SettlementDetail::getSettlementSourceLineId)
                .filter(Objects::nonNull)
                .toList();
        long nullCount = details.size() - linkedIds.size();
        if (nullCount > 0) {
            failures.add("source line 연결 NULL(actual=" + nullCount + ")");
        }

        Map<UUID, Integer> frequencies = new HashMap<>();
        linkedIds.forEach(id -> frequencies.merge(id, 1, Integer::sum));
        List<UUID> duplicates = frequencies.entrySet().stream()
                .filter(entry -> entry.getValue() > 1)
                .map(Map.Entry::getKey)
                .sorted()
                .toList();
        if (!duplicates.isEmpty()) {
            failures.add("source line 연결 중복(ids=" + duplicates + ")");
        }

        Set<UUID> expectedIds = new HashSet<>(sourceLineIds);
        Set<UUID> actualIds = new HashSet<>(linkedIds);
        Set<UUID> missingIds = new HashSet<>(expectedIds);
        missingIds.removeAll(actualIds);
        if (!missingIds.isEmpty()) {
            failures.add("source line 연결 누락(ids=" + sorted(missingIds) + ")");
        }

        Set<UUID> unknownIds = new HashSet<>(actualIds);
        unknownIds.removeAll(expectedIds);
        if (!unknownIds.isEmpty()) {
            failures.add("알 수 없는 source line 연결(ids=" + sorted(unknownIds) + ")");
        }
    }

    private List<UUID> sorted(Set<UUID> ids) {
        return ids.stream().sorted().toList();
    }

    private void addMismatch(
            List<String> failures,
            String field,
            int expected,
            int actual) {
        if (expected != actual) {
            failures.add(field + " 불일치(expected=" + expected + ", actual=" + actual + ")");
        }
    }

    private void addMismatch(
            List<String> failures,
            String field,
            BigDecimal expected,
            BigDecimal actual) {
        if (expected.compareTo(actual) != 0) {
            failures.add(field + " 불일치(expected=" + expected + ", actual=" + actual + ")");
        }
    }

    private static BigDecimal sum(
            List<SettlementDetail> details,
            Function<SettlementDetail, BigDecimal> field) {
        return details.stream()
                .map(field)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private static String truncate(String reason) {
        if (reason.length() <= FAILURE_REASON_MAX_LENGTH) {
            return reason;
        }
        return reason.substring(0, FAILURE_REASON_MAX_LENGTH);
    }
}
