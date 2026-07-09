package com.prompthub.paymentservice.application.service;

import com.prompthub.paymentservice.application.dto.command.RecordOrderSnapshotCommand;
import com.prompthub.paymentservice.application.usecase.RecordOrderSnapshotUseCase;
import com.prompthub.paymentservice.domain.model.OrderSnapshot;
import com.prompthub.paymentservice.domain.repository.OrderSnapshotRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
public class RecordOrderSnapshotService implements RecordOrderSnapshotUseCase {

    private final OrderSnapshotRepository orderSnapshotRepository;

    /**
     * 독립 트랜잭션(REQUIRES_NEW)으로 스냅샷을 확정한다.
     * confirm의 gRPC 폴백에서 호출될 때, 여기서 발생하는 유니크 충돌(DataIntegrityViolationException)이
     * 호출자(confirm TX1)를 rollback-only로 오염시키지 않도록 트랜잭션을 분리한다.
     */
    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public OrderSnapshot record(RecordOrderSnapshotCommand command) {
        return orderSnapshotRepository.findByOrderId(command.orderId())
            .orElseGet(() -> {
                log.debug("주문 스냅샷 신규 기록 — orderId={}, source={}", command.orderId(), command.source());
                return orderSnapshotRepository.save(OrderSnapshot.create(
                    command.orderId(),
                    command.buyerId(),
                    command.totalAmount(),
                    command.source(),
                    command.orderCreatedAt()
                ));
            });
    }
}
