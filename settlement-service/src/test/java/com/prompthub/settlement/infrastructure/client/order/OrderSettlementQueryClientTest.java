package com.prompthub.settlement.infrastructure.client.order;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;

import com.google.protobuf.Descriptors.FieldDescriptor;
import com.prompthub.order.grpc.GetSettleableLinesRequest;
import com.prompthub.settlement.application.dto.SettleableLine;
import com.prompthub.settlement.domain.model.SettlementPeriod;
import com.prompthub.settlement.domain.model.enums.SettlementSourceLineType;
import com.prompthub.settlement.global.exception.SettlementException;
import com.prompthub.order.grpc.OrderQueryServiceGrpc.OrderQueryServiceBlockingStub;
import com.prompthub.order.grpc.GetSettleableLinesResponse;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class OrderSettlementQueryClientTest {

    private static final SettlementPeriod PERIOD = SettlementPeriod.of(
            LocalDate.of(2026, 6, 1), LocalDate.of(2026, 6, 7));

    @Mock
    private OrderQueryServiceBlockingStub stub;

    @InjectMocks
    private OrderSettlementQueryClient client;

    @Test
    @DisplayName("gRPC 응답을 SettleableLine 목록으로 매핑한다")
    void fetch_mapsResponse() {
        // given
        UUID orderId = UUID.randomUUID();
        UUID orderProductId = UUID.randomUUID();
        UUID sellerId = UUID.randomUUID();
        GetSettleableLinesResponse response = GetSettleableLinesResponse.newBuilder()
                .addLines(com.prompthub.order.grpc.SettleableLine.newBuilder()
                        .setLineType("PAID")
                        .setOrderId(orderId.toString())
                        .setOrderProductId(orderProductId.toString())
                        .setSellerId(sellerId.toString())
                        .setLineAmount(15000)
                        .setOccurredAt("2026-06-03T10:15:30")
                        .build())
                .build();
        given(stub.getSettleableLines(any())).willReturn(response);

        // when
        List<SettleableLine> lines = client.fetchSettleableLines(PERIOD);

        // then
        assertThat(lines).hasSize(1);
        SettleableLine line = lines.get(0);
        assertThat(line.lineType()).isEqualTo(SettlementSourceLineType.PAID);
        assertThat(line.orderId()).isEqualTo(orderId);
        assertThat(line.orderProductId()).isEqualTo(orderProductId);
        assertThat(line.sellerId()).isEqualTo(sellerId);
        assertThat(line.lineAmount()).isEqualByComparingTo("15000");
        assertThat(line.occurredAt()).isEqualTo(LocalDateTime.of(2026, 6, 3, 10, 15, 30));
        ArgumentCaptor<GetSettleableLinesRequest> requestCaptor =
                ArgumentCaptor.forClass(GetSettleableLinesRequest.class);
        then(stub).should().getSettleableLines(requestCaptor.capture());
        GetSettleableLinesRequest request = requestCaptor.getValue();
        assertThat(request.getAllFields().keySet())
                .extracting(FieldDescriptor::getName)
                .containsExactlyInAnyOrder("period_start", "period_end");
        assertThat(request.getPeriodStart()).isEqualTo("2026-06-01");
        assertThat(request.getPeriodEnd()).isEqualTo("2026-06-07");
    }

    @Test
    @DisplayName("gRPC 호출이 실패하면 SettlementException 으로 변환해 던진다(배치가 조용히 0건 정산하지 않도록)")
    void fetch_grpcFailure_throwsSettlementException() {
        // given
        given(stub.getSettleableLines(any())).willThrow(new StatusRuntimeException(Status.UNAVAILABLE));

        // when & then
        assertThatThrownBy(() -> client.fetchSettleableLines(PERIOD))
                .isInstanceOf(SettlementException.class);
    }
}
