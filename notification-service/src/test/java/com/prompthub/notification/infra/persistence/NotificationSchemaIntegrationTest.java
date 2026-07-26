package com.prompthub.notification.infra.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest
@ActiveProfiles("test")
class NotificationSchemaIntegrationTest {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void flywayCreatesNotificationDeliveryTables() {
        assertThat(tableExists("NOTIFICATION")).isTrue();
        assertThat(tableExists("PROCESSED_EVENT")).isTrue();
        assertThat(tableExists("NOTIFICATION_RECIPIENT_SEQUENCE")).isTrue();
    }

    private boolean tableExists(String tableName) {
        Integer count = jdbcTemplate.queryForObject(
            "select count(*) from information_schema.tables where table_name = ?",
            Integer.class,
            tableName
        );
        return count != null && count == 1;
    }
}
