package com.prompthub.order.support;

import java.io.IOException;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.Container;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;

@SpringBootTest(
	webEnvironment = SpringBootTest.WebEnvironment.NONE,
	properties = {
		"spring.cloud.config.enabled=false",
		"spring.cloud.discovery.enabled=false",
		"eureka.client.enabled=false",
		"prompthub.outbox-relay.enabled=false",
		"prompthub.order.enabled=false"
	}
)
@ActiveProfiles("test")
public abstract class RedisContainerSupport {

	private static final int REDIS_PORT = 6379;

	protected static final GenericContainer<?> REDIS;

	static {
		REDIS = new GenericContainer<>(DockerImageName.parse("redis:7.4-alpine"))
			.withExposedPorts(REDIS_PORT);
		REDIS.start();
	}

	@MockitoBean
	private KafkaListenerEndpointRegistry kafkaListenerEndpointRegistry;

	@DynamicPropertySource
	static void redisProperties(DynamicPropertyRegistry registry) {
		registry.add("spring.data.redis.host", REDIS::getHost);
		registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(REDIS_PORT));
	}

	@BeforeEach
	@AfterEach
	void cleanRedisData() {
		try {
			Container.ExecResult result = REDIS.execInContainer("redis-cli", "FLUSHDB");
			if (result.getExitCode() != 0) {
				throw new IllegalStateException("Redis FLUSHDB failed: " + result.getStderr());
			}
		} catch (InterruptedException exception) {
			Thread.currentThread().interrupt();
			throw new IllegalStateException("Redis cleanup was interrupted", exception);
		} catch (IOException exception) {
			throw new IllegalStateException("Redis cleanup failed", exception);
		}
	}
}
