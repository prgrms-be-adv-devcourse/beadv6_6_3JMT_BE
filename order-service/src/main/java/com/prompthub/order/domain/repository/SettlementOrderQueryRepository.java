package com.prompthub.order.domain.repository;

import com.prompthub.order.application.dto.SettleableLineResult;

import java.time.LocalDateTime;
import java.util.List;

public interface SettlementOrderQueryRepository {

    List<SettleableLineResult> findSettleableLines(
        LocalDateTime startInclusive,
        LocalDateTime endExclusive
    );
}
