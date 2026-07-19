package com.prompthub.order.application.usecase;

import com.prompthub.order.application.dto.SettleableLineResult;

import java.time.YearMonth;
import java.util.List;

public interface SettlementOrderQueryUseCase {

    List<SettleableLineResult> getSettleableLines(YearMonth period);
}
