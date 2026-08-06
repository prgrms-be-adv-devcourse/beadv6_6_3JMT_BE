package com.prompthub.settlement;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest(properties = {
		"spring.cloud.config.enabled=false",
		"spring.cloud.config.fail-fast=false"
})
@ActiveProfiles("test")
class SettlementApplicationTests {

	@Autowired
	private ApplicationContext applicationContext;

	@Test
	void contextLoads_withoutManualApiFlag_doesNotCreateManualApiBeans() {
		assertThat(applicationContext.containsBean("settlementBatchController")).isFalse();
		assertThat(applicationContext.containsBean("settlementOpenAPI")).isFalse();
	}

}
