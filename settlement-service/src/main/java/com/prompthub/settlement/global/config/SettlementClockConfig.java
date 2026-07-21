package com.prompthub.settlement.global.config;

import java.time.Clock;
import java.time.ZoneId;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class SettlementClockConfig {

    public static final ZoneId SETTLEMENT_ZONE = ZoneId.of("Asia/Seoul");

    @Bean
    Clock settlementClock() {
        return Clock.system(SETTLEMENT_ZONE);
    }
}
