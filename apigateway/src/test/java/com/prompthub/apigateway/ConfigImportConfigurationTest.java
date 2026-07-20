package com.prompthub.apigateway;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Properties;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.core.io.ClassPathResource;
import org.springframework.util.PropertyPlaceholderHelper;

class ConfigImportConfigurationTest {

    @Test
    void usesOptionalConfigServerImportWhenConfigImportEnvironmentVariableIsMissing() {
        YamlPropertiesFactoryBean yaml = new YamlPropertiesFactoryBean();
        yaml.setResources(new ClassPathResource("application.yml"));

        Properties properties = yaml.getObject();
        String configImport = new PropertyPlaceholderHelper("${", "}", ":", null, true)
            .replacePlaceholders(properties.getProperty("spring.config.import"), placeholderName -> null);

        assertThat(configImport).isEqualTo("optional:configserver:");
    }
}
