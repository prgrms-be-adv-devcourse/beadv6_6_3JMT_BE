package com.prompthub.ai.settlement.application.usecase;

import com.prompthub.ai.settlement.domain.event.RunEvent;

public interface SettlementRunEventBroadcaster {

    void broadcast(RunEvent event);
}
