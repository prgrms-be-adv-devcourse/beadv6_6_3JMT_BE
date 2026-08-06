package com.prompthub.order;

import java.util.Properties;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.core.io.ClassPathResource;

import static org.assertj.core.api.Assertions.assertThat;

class OutboxConfigurationTest {

	@Test
	void applicationYamlProvidesApprovedOutboxRelayAndMonitorDefaults() {
		YamlPropertiesFactoryBean yaml = new YamlPropertiesFactoryBean();
		yaml.setResources(new ClassPathResource("application.yml"));

		Properties properties = yaml.getObject();

		assertThat(properties)
			.containsEntry("prompthub.outbox-relay.publish-timeout-ms", 10_000)
			.containsEntry("prompthub.outbox-relay.lease-duration-ms", 30_000)
			.containsEntry("prompthub.outbox-relay.retry.initial-delay-ms", 5_000)
			.containsEntry("prompthub.outbox-relay.retry.max-delay-ms", 300_000)
			.containsEntry("prompthub.outbox-relay.retry.max-attempts", 10)
			.containsEntry("prompthub.outbox-monitor.enabled", true)
			.containsEntry("prompthub.outbox-monitor.fixed-delay-ms", 60_000)
			.containsEntry("prompthub.outbox-monitor.failed-count-threshold", 1)
			.containsEntry("prompthub.outbox-monitor.oldest-unpublished-threshold-ms", 300_000);
	}
}
