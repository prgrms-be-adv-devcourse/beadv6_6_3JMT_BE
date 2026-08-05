package com.prompthub.settlement.application.usecase.delivery;

import java.time.Duration;

public interface DeliveryRetrySleeper {
    void sleep(Duration duration);
}
