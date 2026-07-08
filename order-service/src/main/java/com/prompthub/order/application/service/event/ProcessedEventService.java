package com.prompthub.order.application.service.event;

import com.prompthub.order.domain.model.ProcessedEvent;
import com.prompthub.order.domain.repository.ProcessedEventRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class ProcessedEventService {

    private final ProcessedEventRepository processedEventRepository;

    @Transactional(readOnly = true)
    public boolean isProcessed(UUID eventId, String consumerGroup) {
        return processedEventRepository.existsByEventIdAndConsumerGroup(eventId, consumerGroup);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void markProcessed(UUID eventId, String consumerGroup, String eventType, LocalDateTime occurredAt) {
        ProcessedEvent processedEvent = ProcessedEvent.create(eventId, consumerGroup, eventType, occurredAt);
        processedEventRepository.save(processedEvent);
    }
}
