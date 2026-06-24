package com.prompthub.settlement.infrastructure.event.message;

import com.prompthub.settlement.application.dto.RecordSettlementSourceCommand;
import com.prompthub.settlement.domain.model.enums.SettlementSourceEventType;
import com.prompthub.settlement.global.exception.SettlementErrorCode;
import com.prompthub.settlement.global.exception.SettlementException;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;

public record OrderSettlementMessage(
	String eventType,
	UUID eventId,
	UUID orderId,
	Instant occurredAt,
	List<OrderProduct> orderProducts
) {

	private static final ZoneId SETTLEMENT_ZONE = ZoneId.of("Asia/Seoul");
	private static final String EVENT_TYPE_PAID = "order.paid";
	private static final String EVENT_TYPE_REFUNDED = "order.refunded";

	public record OrderProduct(
		UUID orderProductId,
		UUID sellerId,
		UUID productId,
		BigDecimal amount
	) {
	}

	public List<RecordSettlementSourceCommand> toCommands() {
		SettlementSourceEventType type = resolveType();
		LocalDateTime occurredLocal = LocalDateTime.ofInstant(occurredAt, SETTLEMENT_ZONE);
		return orderProducts.stream()
			.map(line -> new RecordSettlementSourceCommand(
				lineEventId(line.orderProductId(), type),
				type,
				orderId,
				line.orderProductId(),
				line.sellerId(),
				line.amount(),
				occurredLocal
			))
			.toList();
	}

	private SettlementSourceEventType resolveType() {
		return switch (eventType) {
			case EVENT_TYPE_PAID -> SettlementSourceEventType.PAID;
			case EVENT_TYPE_REFUNDED -> SettlementSourceEventType.REFUND;
			default -> throw new SettlementException(
				SettlementErrorCode.SETTLEMENT_EVENT_DESERIALIZE_FAILED, "지원하지 않는 eventType: " + eventType);
		};
	}

	private UUID lineEventId(UUID orderProductId, SettlementSourceEventType type) {
		String seed = eventId + "|" + orderProductId + "|" + type;
		return UUID.nameUUIDFromBytes(seed.getBytes(StandardCharsets.UTF_8));
	}
}
