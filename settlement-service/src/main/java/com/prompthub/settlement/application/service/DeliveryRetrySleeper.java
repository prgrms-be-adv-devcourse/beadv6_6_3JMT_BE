package com.prompthub.settlement.application.service;

import java.time.Duration;

public interface DeliveryRetrySleeper {
    void sleep(Duration duration);
}
