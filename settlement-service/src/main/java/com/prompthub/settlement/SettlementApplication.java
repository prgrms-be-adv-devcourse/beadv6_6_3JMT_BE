package com.prompthub.settlement;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.ConfigurableApplicationContext;

@SpringBootApplication
public class SettlementApplication {

	public static void main(String[] args) {
		ConfigurableApplicationContext context = SpringApplication.run(SettlementApplication.class, args);
		if ("cronjob".equalsIgnoreCase(
			context.getEnvironment().getProperty("settlement.execution.mode"))) {
			System.exit(SpringApplication.exit(context));
		}
	}

}
