package com.prompthub.order.application.usecase;

import com.prompthub.order.application.dto.SettleableLineResult;

import java.time.LocalDate;
import java.util.List;

public interface SettlementOrderQueryUseCase {

    List<SettleableLineResult> getSettleableLines(LocalDate periodStart, LocalDate periodEnd);
}
