package com.prompthub.settlement.application.port;

import java.time.Duration;

public interface DeliveryRetrySleeper {
    void sleep(Duration duration);
}
