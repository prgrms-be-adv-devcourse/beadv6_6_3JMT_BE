package com.prompthub.paymentservice.application.usecase;

import com.prompthub.paymentservice.application.dto.command.RecordOrderSnapshotCommand;
import com.prompthub.paymentservice.domain.model.OrderSnapshot;

/**
 * 주문 스냅샷 기록(upsert-ignore). 이벤트 소비/gRPC 폴백 양쪽에서 사용한다.
 * 이미 존재하면 기존 스냅샷을 그대로 반환한다(스냅샷은 불변).
 */
public interface RecordOrderSnapshotUseCase {
    OrderSnapshot record(RecordOrderSnapshotCommand command);
}
