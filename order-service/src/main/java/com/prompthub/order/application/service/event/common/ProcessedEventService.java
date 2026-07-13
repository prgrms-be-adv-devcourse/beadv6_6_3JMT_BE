package com.prompthub.order.application.service.event.common;

import com.prompthub.order.domain.model.OrderProcessedEvent;
import com.prompthub.order.domain.repository.ProcessedEventRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class ProcessedEventService {

    private static final String CONSUMER_GROUP = "order-service";

    private final ProcessedEventRepository processedEventRepository;

    @Transactional
    public boolean executeOnce(ConsumedEventContext context, Runnable action) {
        if (processedEventRepository.existsByEventIdAndConsumerGroup(context.eventId(), CONSUMER_GROUP)) {
            return false;
        }

        action.run();
        markProcessed(context);
        return true;
    }

    private void markProcessed(ConsumedEventContext context) {
        OrderProcessedEvent orderProcessedEvent = OrderProcessedEvent.create(
            context.eventId(),
            CONSUMER_GROUP,
            context.eventType(),
            context.occurredAt()
        );
        processedEventRepository.save(orderProcessedEvent);
    }
}
