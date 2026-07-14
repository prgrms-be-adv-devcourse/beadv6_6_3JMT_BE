package com.prompthub.order.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.core.io.ClassPathResource;

import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;

class ApplicationJpaConfigTest {

    @Test
    @DisplayName("로컬 JPA 스키마 관리는 create-only를 사용한다")
    void mainApplicationYaml_usesHibernateCreateOnly() {
        YamlPropertiesFactoryBean yaml = new YamlPropertiesFactoryBean();
        yaml.setResources(new ClassPathResource("application.yml"));

        Properties properties = yaml.getObject();

        assertThat(properties)
            .isNotNull()
            .containsEntry("spring.jpa.hibernate.ddl-auto", "create-only");
    }
}
