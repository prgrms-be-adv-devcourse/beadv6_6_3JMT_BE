package com.prompthub.settlement.global.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;

class ApiVersionConfigurationTest {

	@Test
	@DisplayName("공개 API 기본 경로는 v2이다")
	void usesV2ApiPrefix() throws IOException {
		Resource resource = new ClassPathResource("application.yml");
		PropertySource<?> propertySource = new YamlPropertySourceLoader()
			.load("application", resource)
			.getFirst();

		assertThat(propertySource.getProperty("api.init")).isEqualTo("/api/v2");
	}
}
