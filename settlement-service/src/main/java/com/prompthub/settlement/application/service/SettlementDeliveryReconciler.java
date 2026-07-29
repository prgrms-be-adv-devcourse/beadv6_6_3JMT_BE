package com.prompthub.settlement.application.service;

import com.prompthub.settlement.application.dto.SellerSettlementRegistrationCommand;
import com.prompthub.settlement.application.dto.SellerSettlementStoredSnapshot;
import com.prompthub.settlement.application.dto.SettlementDeliveryComparison;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Component;

@Component
public class SettlementDeliveryReconciler {

    public SettlementDeliveryComparison compare(
            SellerSettlementRegistrationCommand expected,
            SellerSettlementStoredSnapshot actual) {
        Mismatches mismatches = new Mismatches();
        mismatches.value("deliveryRequestId", expected.deliveryRequestId(), actual.deliveryRequestId());
        mismatches.value("settlementId", expected.settlementId(), actual.settlementId());
        mismatches.value("sellerId", expected.sellerId(), actual.sellerId());
        mismatches.value("periodStart", expected.periodStart(), actual.periodStart());
        mismatches.value("periodEnd", expected.periodEnd(), actual.periodEnd());
        mismatches.value("productCount", expected.productCount(), actual.productCount());
        mismatches.decimal("grossSalesAmount", expected.grossSalesAmount(), actual.grossSalesAmount());
        mismatches.decimal("refundAmount", expected.refundAmount(), actual.refundAmount());
        mismatches.decimal("feeTotalAmount", expected.feeTotalAmount(), actual.feeTotalAmount());
        mismatches.decimal("settlementTotalAmount", expected.settlementTotalAmount(),
                actual.settlementTotalAmount());
        mismatches.time("calculatedAt", expected.calculatedAt(), actual.calculatedAt());
        compareDetails(expected, actual, mismatches);
        return mismatches.result();
    }

    private void compareDetails(
            SellerSettlementRegistrationCommand expected,
            SellerSettlementStoredSnapshot actual,
            Mismatches mismatches) {
        Map<UUID, SellerSettlementStoredSnapshot.Detail> actualById = new HashMap<>();
        for (SellerSettlementStoredSnapshot.Detail detail : actual.details()) {
            if (actualById.put(detail.settlementDetailId(), detail) != null) {
                mismatches.add("settlementDetailId 중복: actual=" + detail.settlementDetailId());
            }
        }
        for (SellerSettlementRegistrationCommand.Detail detail : expected.details()) {
            var stored = actualById.remove(detail.settlementDetailId());
            if (stored == null) {
                mismatches.add("settlementDetailId 누락: expected=" + detail.settlementDetailId());
                continue;
            }
            String prefix = "settlementDetailId=" + detail.settlementDetailId() + " ";
            mismatches.value(prefix + "orderProductId", detail.orderProductId(), stored.orderProductId());
            mismatches.value(prefix + "lineType", detail.lineType(), stored.lineType());
            mismatches.decimal(prefix + "lineAmount", detail.lineAmount(), stored.lineAmount());
            mismatches.decimal(prefix + "feeRate", detail.feeRate(), stored.feeRate());
            mismatches.decimal(prefix + "feeAmount", detail.feeAmount(), stored.feeAmount());
            mismatches.decimal(prefix + "lineSettlementAmount", detail.lineSettlementAmount(),
                    stored.lineSettlementAmount());
            mismatches.time(prefix + "occurredAt", detail.occurredAt(), stored.occurredAt());
        }
        actualById.keySet().forEach(id ->
                mismatches.add("settlementDetailId 추가: actual=" + id));
    }

    private static final class Mismatches {
        private int count;
        private String first;

        void value(String field, Object expected, Object actual) {
            if (!Objects.equals(expected, actual)) {
                add(field + " 불일치: expected=" + expected + ", actual=" + actual);
            }
        }

        void decimal(String field, BigDecimal expected, BigDecimal actual) {
            if (expected == null || actual == null
                    ? expected != actual : expected.compareTo(actual) != 0) {
                add(field + " 불일치: expected=" + expected + ", actual=" + actual);
            }
        }

        void time(String field, LocalDateTime expected, LocalDateTime actual) {
            LocalDateTime left = expected == null ? null : expected.truncatedTo(ChronoUnit.MICROS);
            LocalDateTime right = actual == null ? null : actual.truncatedTo(ChronoUnit.MICROS);
            value(field, left, right);
        }

        void add(String message) {
            if (first == null) {
                first = message;
            }
            count++;
        }

        SettlementDeliveryComparison result() {
            return count == 0
                    ? SettlementDeliveryComparison.success()
                    : SettlementDeliveryComparison.mismatched(first, count);
        }
    }
}
