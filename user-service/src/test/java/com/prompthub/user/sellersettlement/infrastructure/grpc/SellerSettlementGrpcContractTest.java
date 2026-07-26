package com.prompthub.user.sellersettlement.infrastructure.grpc;

import static org.assertj.core.api.Assertions.assertThat;

import com.google.protobuf.Descriptors.Descriptor;
import com.google.protobuf.Descriptors.FieldDescriptor;
import com.google.protobuf.Descriptors.FileDescriptor;
import com.google.protobuf.Descriptors.MethodDescriptor;
import com.google.protobuf.Descriptors.ServiceDescriptor;
import com.prompthub.user.grpc.sellersettlement.SellerSettlementQueryProto;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("판매자 정산 분석 gRPC 계약")
class SellerSettlementGrpcContractTest {

    @Test
    @DisplayName("서비스와 RPC, 필드 번호, 금융 scalar, 식별자 경계를 고정한다")
    void preservesSellerSettlementQueryContract() {
        FileDescriptor file = SellerSettlementQueryProto.getDescriptor();
        ServiceDescriptor service = file.findServiceByName("SellerSettlementQueryService");

        assertThat(service.getFullName())
                .isEqualTo("prompthub.sellersettlement.SellerSettlementQueryService");
        assertThat(service.getMethods())
                .extracting(
                        MethodDescriptor::getName,
                        method -> method.getInputType().getName(),
                        method -> method.getOutputType().getName())
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(
                                "GetSettlementSummary",
                                "GetSettlementSummaryRequest",
                                "GetSettlementSummaryResponse"),
                        org.assertj.core.groups.Tuple.tuple(
                                "CompareSettlementPeriods",
                                "CompareSettlementPeriodsRequest",
                                "CompareSettlementPeriodsResponse"),
                        org.assertj.core.groups.Tuple.tuple(
                                "GetWeeklySettlementBreakdown",
                                "GetWeeklySettlementBreakdownRequest",
                                "GetWeeklySettlementBreakdownResponse"),
                        org.assertj.core.groups.Tuple.tuple(
                                "GetPayoutStatus",
                                "GetPayoutStatusRequest",
                                "GetPayoutStatusResponse"));

        assertFields(file, "SettlementAggregate", Map.ofEntries(
                Map.entry("included_start_date", 1),
                Map.entry("included_end_date", 2),
                Map.entry("data_through", 3),
                Map.entry("partial", 4),
                Map.entry("sale_count", 5),
                Map.entry("refund_count", 6),
                Map.entry("gross_sale_amount", 7),
                Map.entry("gross_refund_amount", 8),
                Map.entry("sale_fee_amount", 9),
                Map.entry("refunded_fee_amount", 10),
                Map.entry("net_fee_amount", 11),
                Map.entry("payout_amount", 12)));
        assertFields(file, "CountChange", Map.of(
                "difference", 1, "change_rate_percent", 2, "comparable", 3));
        assertFields(file, "DecimalChange", Map.of(
                "difference", 1, "change_rate_percent", 2, "comparable", 3));
        assertFields(file, "SettlementAggregateComparison", Map.of(
                "sale_count", 1,
                "refund_count", 2,
                "gross_sale_amount", 3,
                "gross_refund_amount", 4,
                "sale_fee_amount", 5,
                "refunded_fee_amount", 6,
                "net_fee_amount", 7,
                "payout_amount", 8));
        assertFields(file, "GetSettlementSummaryRequest", Map.of(
                "period_type", 1, "period", 2));
        assertFields(file, "GetSettlementSummaryResponse", Map.of(
                "period_type", 1, "requested_period", 2, "aggregate", 3));
        assertFields(file, "CompareSettlementPeriodsRequest", Map.of(
                "period_type", 1, "current_period", 2, "comparison_period", 3));
        assertFields(file, "CompareSettlementPeriodsResponse", Map.of(
                "period_type", 1,
                "current_period", 2,
                "comparison_period", 3,
                "current", 4,
                "comparison", 5,
                "changes", 6));
        assertFields(file, "GetWeeklySettlementBreakdownRequest", Map.of("month", 1));
        assertFields(file, "WeeklySettlementBucket", Map.of(
                "week_start_date", 1,
                "week_end_date", 2,
                "boundary_week", 3,
                "aggregate", 4));
        assertFields(file, "GetWeeklySettlementBreakdownResponse", Map.of(
                "requested_month", 1, "partial", 2, "data_through", 3, "weeks", 4));
        assertFields(file, "GetPayoutStatusRequest", Map.of("settlement_month", 1));
        assertFields(file, "PayoutStatusCount", Map.of("status", 1, "count", 2));
        assertFields(file, "WeeklyPayoutStatus", Map.of(
                "period_start_date", 1,
                "period_end_date", 2,
                "status", 3,
                "paid_at", 4));
        assertFields(file, "GetPayoutStatusResponse", Map.of(
                "settlement_month", 1, "status_counts", 2, "weekly_settlements", 3));

        Descriptor aggregate = file.findMessageTypeByName("SettlementAggregate");
        Descriptor countChange = file.findMessageTypeByName("CountChange");
        Descriptor decimalChange = file.findMessageTypeByName("DecimalChange");
        assertThat(List.of(
                aggregate.findFieldByName("gross_sale_amount"),
                aggregate.findFieldByName("gross_refund_amount"),
                aggregate.findFieldByName("sale_fee_amount"),
                aggregate.findFieldByName("refunded_fee_amount"),
                aggregate.findFieldByName("net_fee_amount"),
                aggregate.findFieldByName("payout_amount"),
                countChange.findFieldByName("change_rate_percent"),
                decimalChange.findFieldByName("difference"),
                decimalChange.findFieldByName("change_rate_percent")))
                .extracting(FieldDescriptor::getJavaType)
                .allMatch(type -> type == FieldDescriptor.JavaType.STRING);

        Set<String> forbiddenFields = Set.of(
                "seller_id", "settlement_id", "settlement_detail_id",
                "order_product_id", "currency_code");
        assertThat(file.getMessageTypes().stream()
                .flatMap(message -> message.getFields().stream())
                .map(FieldDescriptor::getName))
                .doesNotContainAnyElementsOf(forbiddenFields);
    }

    private void assertFields(FileDescriptor file, String messageName, Map<String, Integer> expected) {
        Descriptor message = file.findMessageTypeByName(messageName);
        Map<String, Integer> actual = message.getFields().stream()
                .collect(Collectors.toMap(FieldDescriptor::getName, FieldDescriptor::getNumber));

        assertThat(actual).containsExactlyInAnyOrderEntriesOf(expected);
    }
}
