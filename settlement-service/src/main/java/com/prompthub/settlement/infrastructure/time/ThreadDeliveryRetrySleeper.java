package com.prompthub.settlement.infrastructure.time;

import com.prompthub.settlement.application.service.DeliveryRetrySleeper;
import java.time.Duration;
import org.springframework.stereotype.Component;

@Component
public class ThreadDeliveryRetrySleeper implements DeliveryRetrySleeper {
    @Override
    public void sleep(Duration duration) {
        try {
            Thread.sleep(duration.toMillis());
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("정산 전달 재시도 대기가 중단되었습니다.", exception);
        }
    }
}
