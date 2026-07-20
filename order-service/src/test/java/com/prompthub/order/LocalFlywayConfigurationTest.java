package com.prompthub.order;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Properties;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.core.io.ClassPathResource;

class LocalFlywayConfigurationTest {

	@Test
	void localProfileBaselinesLegacySchemaAndLetsFlywayOwnSchema() {
		YamlPropertiesFactoryBean yaml = new YamlPropertiesFactoryBean();
		yaml.setResources(new ClassPathResource("application.yml"));

		Properties properties = yaml.getObject();

		assertThat(properties)
			.containsEntry("spring.flyway.baseline-on-migrate", true)
			.containsEntry("spring.flyway.baseline-version", 1)
			.containsEntry("spring.jpa.hibernate.ddl-auto", "validate");
	}
}
