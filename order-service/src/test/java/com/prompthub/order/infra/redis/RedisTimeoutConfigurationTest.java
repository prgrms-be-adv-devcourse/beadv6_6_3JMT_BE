package com.prompthub.order.infra.redis;

import java.util.Properties;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.core.io.ClassPathResource;

import static org.assertj.core.api.Assertions.assertThat;

class RedisTimeoutConfigurationTest {

	@Test
	void applicationYamlDefinesBoundedRedisTimeouts() {
		YamlPropertiesFactoryBean yaml = new YamlPropertiesFactoryBean();
		yaml.setResources(new ClassPathResource("application.yml"));

		Properties properties = yaml.getObject();

		assertThat(properties)
			.containsEntry(
				"spring.data.redis.connect-timeout",
				"${REDIS_CONNECT_TIMEOUT:1s}"
			)
			.containsEntry(
				"spring.data.redis.timeout",
				"${REDIS_COMMAND_TIMEOUT:2s}"
			);
	}
}
