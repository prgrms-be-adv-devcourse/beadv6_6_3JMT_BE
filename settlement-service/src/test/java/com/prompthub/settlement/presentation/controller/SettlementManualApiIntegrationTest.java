package com.prompthub.settlement.presentation.controller;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest(properties = {
	"spring.cloud.config.enabled=false",
	"spring.cloud.config.fail-fast=false",
	"settlement.manual-api.enabled=true"
})
@ActiveProfiles("test")
class SettlementManualApiIntegrationTest {

	@Autowired
	private ApplicationContext applicationContext;

	@Test
	void manualApiEnabled_createsControllerAndOpenApiBeans() {
		assertThat(applicationContext.containsBean("settlementBatchController")).isTrue();
		assertThat(applicationContext.containsBean("settlementOpenAPI")).isTrue();
	}
}
