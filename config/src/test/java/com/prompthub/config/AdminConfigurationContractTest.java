package com.prompthub.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Properties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.core.io.ClassPathResource;

class AdminConfigurationContractTest {

    @Test
    @DisplayName("Admin Service는 다른 서비스가 소유한 스키마의 Flyway를 실행하지 않는다")
    void adminService_disablesFlyway() {
        Properties admin = load("configs/admin-service.yml");

        assertThat(admin.getProperty("spring.flyway.enabled")).isEqualTo("false");
    }

    private Properties load(String path) {
        YamlPropertiesFactoryBean factory = new YamlPropertiesFactoryBean();
        factory.setResources(new ClassPathResource(path));
        return factory.getObject();
    }
}
