package com.prompthub.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;

class TimeZoneConfigurationTest {

    private static final List<String> TIME_ZONE_SERVICES = List.of(
            "admin-service",
            "order-service",
            "payment-service",
            "product-service",
            "settlement-service",
            "user-service");

    private final YamlPropertySourceLoader loader = new YamlPropertySourceLoader();

    @Test
    @DisplayName("모든 서비스의 Jackson 시간대는 Asia/Seoul이다")
    void allServicesJacksonTimeZone_isAsiaSeoul() throws IOException {
        for (String service : TIME_ZONE_SERVICES) {
            assertThat(property(
                    "configs/" + service + ".yml",
                    "spring.jackson.time-zone"))
                    .as(service)
                    .isEqualTo("Asia/Seoul");
        }
    }

    @Test
    @DisplayName("모든 JPA 서비스의 JDBC 시간대는 Asia/Seoul이다")
    void allJpaServicesJdbcTimeZone_isAsiaSeoul() throws IOException {
        for (String service : TIME_ZONE_SERVICES) {
            assertThat(property(
                    "configs/" + service + ".yml",
                    "spring.jpa.properties.hibernate.jdbc.time_zone"))
                    .as(service)
                    .isEqualTo("Asia/Seoul");
        }
    }

    private Object property(String path, String key) throws IOException {
        List<PropertySource<?>> propertySources = loader.load(
                path,
                new ClassPathResource(path));
        return propertySources.getFirst().getProperty(key);
    }
}
