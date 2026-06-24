package com.prompthub.settlement.infrastructure.event.message;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.prompthub.settlement.application.dto.RecordSettlementSourceCommand;
import com.prompthub.settlement.domain.model.enums.SettlementSourceEventType;
import com.prompthub.settlement.global.exception.SettlementException;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class OrderSettlementMessageTest {

    private static final Instant OCCURRED_AT_UTC = Instant.parse("2026-06-15T01:00:00Z");

    private OrderSettlementMessage.OrderProduct product(UUID orderProductId, String amount) {
        return new OrderSettlementMessage.OrderProduct(
                orderProductId, UUID.randomUUID(), UUID.randomUUID(), new BigDecimal(amount));
    }

    private OrderSettlementMessage message(String eventType, UUID eventId,
                                           List<OrderSettlementMessage.OrderProduct> products) {
        return new OrderSettlementMessage(eventType, eventId, UUID.randomUUID(), OCCURRED_AT_UTC, products);
    }

    @Test
    @DisplayName("order.paid를 주문상품 수만큼 PAID 커맨드로 펼친다")
    void toCommands_paid_expandsPerOrderProduct() {
        List<RecordSettlementSourceCommand> commands = message("order.paid", UUID.randomUUID(),
                List.of(product(UUID.randomUUID(), "9900"), product(UUID.randomUUID(), "5000"))).toCommands();

        assertThat(commands).hasSize(2);
        assertThat(commands).allMatch(c -> c.eventType() == SettlementSourceEventType.PAID);
    }

    @Test
    @DisplayName("order.refunded는 REFUND 커맨드로 변환된다")
    void toCommands_refunded_mapsToRefundType() {
        RecordSettlementSourceCommand command = message("order.refunded", UUID.randomUUID(),
                List.of(product(UUID.randomUUID(), "9900"))).toCommands().get(0);

        assertThat(command.eventType()).isEqualTo(SettlementSourceEventType.REFUND);
    }

    @Test
    @DisplayName("occurredAt(UTC)를 KST(+9)로 변환해 담는다")
    void toCommands_convertsOccurredAtToKst() {
        RecordSettlementSourceCommand command = message("order.paid", UUID.randomUUID(),
                List.of(product(UUID.randomUUID(), "9900"))).toCommands().get(0);

        assertThat(command.occurredAt()).isEqualTo(LocalDateTime.of(2026, 6, 15, 10, 0));
    }

    @Test
    @DisplayName("같은 입력은 항상 같은 합성 eventId를 만든다(멱등)")
    void toCommands_synthesizesDeterministicEventId() {
        UUID eventId = UUID.randomUUID();
        OrderSettlementMessage.OrderProduct line = product(UUID.randomUUID(), "9900");

        UUID first = message("order.paid", eventId, List.of(line)).toCommands().get(0).eventId();
        UUID second = message("order.paid", eventId, List.of(line)).toCommands().get(0).eventId();

        assertThat(first).isEqualTo(second);
    }

    @Test
    @DisplayName("주문상품·상태가 다르면 합성 eventId도 달라진다")
    void toCommands_eventIdDiffersByOrderProductAndState() {
        UUID eventId = UUID.randomUUID();
        OrderSettlementMessage.OrderProduct line = product(UUID.randomUUID(), "9900");
        OrderSettlementMessage.OrderProduct otherLine = product(UUID.randomUUID(), "9900");

        UUID paidId = message("order.paid", eventId, List.of(line)).toCommands().get(0).eventId();
        UUID refundId = message("order.refunded", eventId, List.of(line)).toCommands().get(0).eventId();
        UUID otherProductId = message("order.paid", eventId, List.of(otherLine)).toCommands().get(0).eventId();

        assertThat(paidId).isNotEqualTo(refundId);
        assertThat(paidId).isNotEqualTo(otherProductId);
    }

    @Test
    @DisplayName("지원하지 않는 eventType이면 예외를 던진다")
    void toCommands_unsupportedEventType_throws() {
        OrderSettlementMessage message = message("order.cancelled", UUID.randomUUID(),
                List.of(product(UUID.randomUUID(), "9900")));

        assertThatThrownBy(message::toCommands).isInstanceOf(SettlementException.class);
    }
}
