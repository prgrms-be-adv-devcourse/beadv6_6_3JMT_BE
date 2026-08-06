package com.prompthub.order.domain.repository;

import com.prompthub.order.domain.model.OutboxRedriveHistory;

public interface OutboxRedriveHistoryRepository {

    OutboxRedriveHistory saveAndFlush(OutboxRedriveHistory history);
}
