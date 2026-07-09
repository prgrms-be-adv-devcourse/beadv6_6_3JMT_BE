package com.prompthub.settlement.infrastructure.client.order;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;

import com.prompthub.settlement.application.dto.SettleableLine;
import com.prompthub.settlement.domain.model.enums.SettlementSourceEventType;
import com.prompthub.settlement.global.exception.SettlementException;
import com.prompthub.settlement.grpc.ordersettlement.OrderSettlementQueryServiceGrpc.OrderSettlementQueryServiceBlockingStub;
import com.prompthub.settlement.grpc.ordersettlement.SettleableLinesResponse;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class OrderSettlementQueryClientTest {

    @Mock
    private OrderSettlementQueryServiceBlockingStub stub;

    @InjectMocks
    private OrderSettlementQueryClient client;

    @Test
    @DisplayName("gRPC 응답을 SettleableLine 목록으로 매핑한다")
    void fetch_mapsResponse() {
        // given
        UUID eventId = UUID.randomUUID();
        UUID orderId = UUID.randomUUID();
        UUID orderProductId = UUID.randomUUID();
        UUID sellerId = UUID.randomUUID();
        SettleableLinesResponse response = SettleableLinesResponse.newBuilder()
                .addLines(com.prompthub.settlement.grpc.ordersettlement.SettleableLine.newBuilder()
                        .setEventId(eventId.toString())
                        .setEventType("PAID")
                        .setOrderId(orderId.toString())
                        .setOrderProductId(orderProductId.toString())
                        .setSellerId(sellerId.toString())
                        .setLineAmount(15000)
                        .setOccurredAt("2026-06-03T10:15:30")
                        .build())
                .build();
        given(stub.getSettleableLines(any())).willReturn(response);

        // when
        List<SettleableLine> lines = client.fetchSettleableLines(YearMonth.of(2026, 6));

        // then
        assertThat(lines).hasSize(1);
        SettleableLine line = lines.get(0);
        assertThat(line.eventId()).isEqualTo(eventId);
        assertThat(line.eventType()).isEqualTo(SettlementSourceEventType.PAID);
        assertThat(line.orderId()).isEqualTo(orderId);
        assertThat(line.orderProductId()).isEqualTo(orderProductId);
        assertThat(line.sellerId()).isEqualTo(sellerId);
        assertThat(line.lineAmount()).isEqualByComparingTo("15000");
        assertThat(line.occurredAt()).isEqualTo(LocalDateTime.of(2026, 6, 3, 10, 15, 30));
    }

    @Test
    @DisplayName("gRPC 호출이 실패하면 SettlementException 으로 변환해 던진다(배치가 조용히 0건 정산하지 않도록)")
    void fetch_grpcFailure_throwsSettlementException() {
        // given
        given(stub.getSettleableLines(any())).willThrow(new StatusRuntimeException(Status.UNAVAILABLE));

        // when & then
        assertThatThrownBy(() -> client.fetchSettleableLines(YearMonth.of(2026, 6)))
                .isInstanceOf(SettlementException.class);
    }
}
