package com.prompthub.settlement;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest(properties = {
		"spring.cloud.config.enabled=false",
		"spring.cloud.config.fail-fast=false"
})
@ActiveProfiles("test")
class SettlementApplicationTests {

	@Test
	void contextLoads() {
	}

}
