package com.prompthub.user.sellersettlement.infrastructure.grpc;

import static org.assertj.core.api.Assertions.assertThat;

import com.google.protobuf.Descriptors.Descriptor;
import com.google.protobuf.Descriptors.FieldDescriptor;
import com.google.protobuf.Descriptors.FileDescriptor;
import com.google.protobuf.Descriptors.ServiceDescriptor;
import com.prompthub.user.grpc.sellersettlement.command.SellerSettlementCommandProto;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class SellerSettlementCommandGrpcContractTest {

    @Test
    @DisplayName("등록 요청과 응답은 deliveryRequestId와 저장 Snapshot을 고정된 필드로 제공한다")
    void requestAndResponseFieldsRemainStable() {
        FileDescriptor file = SellerSettlementCommandProto.getDescriptor();
        ServiceDescriptor service =
                file.findServiceByName("SellerSettlementCommandService");

        assertThat(service.getMethods())
                .extracting(method -> method.getName())
                .containsExactly("RegisterSellerSettlement");
        assertFields(file, "RegisterSellerSettlementRequest", Map.of(
                "delivery_request_id", 1,
                "settlement", 2));
        assertFields(file, "RegisterSellerSettlementResponse", Map.of(
                "stored_settlement", 1));
    }

    @Test
    @DisplayName("정산 Snapshot과 상세 계약은 직접 대사에 필요한 모든 필드를 제공한다")
    void snapshotFieldsRemainStable() {
        FileDescriptor file = SellerSettlementCommandProto.getDescriptor();

        assertFields(file, "SellerSettlementSnapshot", Map.ofEntries(
                Map.entry("delivery_request_id", 1),
                Map.entry("settlement_id", 2),
                Map.entry("seller_id", 3),
                Map.entry("period_start", 4),
                Map.entry("period_end", 5),
                Map.entry("product_count", 6),
                Map.entry("gross_sales_amount", 7),
                Map.entry("refund_amount", 8),
                Map.entry("fee_total_amount", 9),
                Map.entry("settlement_total_amount", 10),
                Map.entry("calculated_at", 11),
                Map.entry("details", 12)));
        assertFields(file, "SellerSettlementDetailSnapshot", Map.of(
                "settlement_detail_id", 1,
                "order_product_id", 2,
                "line_type", 3,
                "line_amount", 4,
                "fee_rate", 5,
                "fee_amount", 6,
                "line_settlement_amount", 7,
                "occurred_at", 8));
    }

    private void assertFields(
            FileDescriptor file, String messageName, Map<String, Integer> expected) {
        Descriptor message = file.findMessageTypeByName(messageName);
        Map<String, Integer> actual = message.getFields().stream()
                .collect(Collectors.toMap(
                        FieldDescriptor::getName,
                        FieldDescriptor::getNumber));
        assertThat(actual).containsExactlyInAnyOrderEntriesOf(expected);
    }
}
