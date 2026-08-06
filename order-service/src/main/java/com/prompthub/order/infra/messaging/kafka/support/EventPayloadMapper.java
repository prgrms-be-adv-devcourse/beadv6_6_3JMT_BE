package com.prompthub.order.infra.messaging.kafka.support;

import tools.jackson.core.JacksonException;
import com.prompthub.common.event.EventMessage;
import com.prompthub.order.global.exception.ErrorCode;
import com.prompthub.order.global.exception.OrderException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Component
@RequiredArgsConstructor
public class EventPayloadMapper {

	private final ObjectMapper objectMapper;

	public <T> T convert(EventMessage<JsonNode> message, Class<T> payloadType) {
		try {
			return objectMapper.treeToValue(message.payload(), payloadType);
		} catch (JacksonException e) {
			throw new OrderException(ErrorCode.EVENT_PAYLOAD_MAPPING_ERROR);
		}
	}
}