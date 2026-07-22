package com.prompthub.settlement;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.ConfigurableApplicationContext;

@SpringBootApplication
public class SettlementApplication {

	public static void main(String[] args) {
		ConfigurableApplicationContext context = SpringApplication.run(SettlementApplication.class, args);
		String executionMode = context.getEnvironment().getProperty("settlement.execution.mode");
		if (isOneShotMode(executionMode)) {
			System.exit(SpringApplication.exit(context));
		}
	}

	static boolean isOneShotMode(String executionMode) {
		return "cronjob".equalsIgnoreCase(executionMode)
			|| "restart".equalsIgnoreCase(executionMode);
	}
}
