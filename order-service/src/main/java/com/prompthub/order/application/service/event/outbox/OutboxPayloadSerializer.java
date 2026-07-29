package com.prompthub.order.application.service.event.outbox;

import com.prompthub.common.event.EventMessage;

public interface OutboxPayloadSerializer {

    String serialize(EventMessage<?> message);
}
