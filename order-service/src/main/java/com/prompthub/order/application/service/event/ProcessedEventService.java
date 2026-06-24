package com.prompthub.order.application.service.event;

import com.prompthub.order.infra.persistence.event.ProcessedEvent;
import com.prompthub.order.infra.persistence.event.ProcessedEventRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
public class ProcessedEventService {

    private final ProcessedEventRepository processedEventRepository;

    /**
     * 이벤트를 처리됨 상태로 저장합니다.
     * 이미 처리된 이벤트(eventId + consumerGroup 중복)라면 false를 반환합니다.
     * 
     * @return 저장 성공(최초 처리) 시 true, 중복 이벤트인 경우 false
     */
    @Transactional
    public boolean processEvent(String eventId, String eventType, String consumerGroup) {
        if (processedEventRepository.existsByEventIdAndConsumerGroup(eventId, consumerGroup)) {
            log.warn("이미 처리된 이벤트입니다. eventId: {}, consumerGroup: {}", eventId, consumerGroup);
            return false;
        }

        try {
            ProcessedEvent processedEvent = ProcessedEvent.builder()
                .eventId(eventId)
                .eventType(eventType)
                .consumerGroup(consumerGroup)
                .build();
            processedEventRepository.saveAndFlush(processedEvent);
            return true;
        } catch (DataIntegrityViolationException e) {
            log.warn("이벤트 중복 처리 방어(Unique Constraint): eventId={}, consumerGroup={}", eventId, consumerGroup);
            return false;
        }
    }
}
