package com.prompthub.admin.home.config;

import java.time.Clock;
import java.time.ZoneId;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class HomeTimeConfig {

	@Bean("homeClock")
	Clock homeClock() {
		return Clock.systemUTC();
	}

	@Bean("homeZoneId")
	ZoneId homeZoneId() {
		return ZoneId.of("Asia/Seoul");
	}
}
