package com.prompthub.user.sellersettlement.infrastructure.config;

import java.time.Clock;
import java.time.ZoneId;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class SellerSettlementAnalysisClockConfig {

    @Bean
    Clock sellerSettlementAnalysisClock() {
        return Clock.system(ZoneId.of("Asia/Seoul"));
    }
}
