package com.prompthub.order.infra.messaging.kafka.producer;

import com.prompthub.common.event.EventMessage;
import com.prompthub.order.application.service.event.outbox.OutboxPayloadSerializer;
import com.prompthub.order.global.exception.ErrorCode;
import com.prompthub.order.global.exception.OrderException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

@Component
@RequiredArgsConstructor
public class JacksonOutboxPayloadSerializer implements OutboxPayloadSerializer {

    private final ObjectMapper objectMapper;

    @Override
    public String serialize(EventMessage<?> message) {
        try {
            return objectMapper.writeValueAsString(message);
        } catch (JacksonException exception) {
            throw new OrderException(ErrorCode.INTERNAL_SERVER_ERROR);
        }
    }
}
