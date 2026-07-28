package com.prompthub.notification.domain.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.prompthub.notification.application.dto.NotificationSettingUpdateResponse;
import com.prompthub.notification.application.service.NotificationSettingService;
import com.prompthub.notification.domain.enums.NotificationCategory;
import com.prompthub.notification.domain.model.NotificationSetting;
import com.prompthub.notification.infra.persistence.config.QuerydslConfig;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@DataJpaTest(properties = {
    "spring.flyway.enabled=true",
    "spring.jpa.hibernate.ddl-auto=validate"
})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@Import({NotificationSettingService.class, QuerydslConfig.class})
@Testcontainers(disabledWithoutDocker = true)
class NotificationSettingRepositoryPostgresIntegrationTest {

    @Container
    private static final PostgreSQLContainer POSTGRES =
        new PostgreSQLContainer("postgres:18.4-alpine")
            .withDatabaseName("notification")
            .withUsername("notification")
            .withPassword("notification");

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.datasource.driver-class-name", POSTGRES::getDriverClassName);
    }

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private NotificationSettingRepository repository;

    @Autowired
    private NotificationSettingService service;

    @Test
    void V2_createsNotificationSettingTableAndUniqueConstraint() {
        Integer tableCount = jdbcTemplate.queryForObject("""
            select count(*)
            from information_schema.tables
            where table_schema = current_schema()
              and table_name = 'notification_setting'
            """, Integer.class);
        Integer constraintCount = jdbcTemplate.queryForObject("""
            select count(*)
            from information_schema.table_constraints
            where table_schema = current_schema()
              and table_name = 'notification_setting'
              and constraint_name = 'uk_notification_setting_recipient_category'
              and constraint_type = 'UNIQUE'
            """, Integer.class);

        assertThat(tableCount).isEqualTo(1);
        assertThat(constraintCount).isEqualTo(1);
    }

    @Test
    void upsert_identicalStatePreservesIdentityAndTimestampsAndReturnsOneAffectedRow() {
        UUID recipientId = UUID.randomUUID();
        UUID firstSettingId = UUID.randomUUID();
        Instant createdAt = Instant.parse("2026-07-28T01:00:00Z");
        Instant repeatedAt = Instant.parse("2026-07-28T02:00:00Z");

        assertThat(repository.upsert(
            firstSettingId, recipientId, "MARKETING", false, createdAt
        )).isEqualTo(1);
        assertThat(repository.upsert(
            UUID.randomUUID(), recipientId, "MARKETING", false, repeatedAt
        )).isEqualTo(1);

        NotificationSetting stored = repository
            .findByRecipientIdAndCategory(recipientId, NotificationCategory.MARKETING)
            .orElseThrow();

        assertThat(stored.getId()).isEqualTo(firstSettingId);
        assertThat(stored.isEnabled()).isFalse();
        assertThat(stored.getCreatedAt()).isEqualTo(createdAt);
        assertThat(stored.getUpdatedAt()).isEqualTo(createdAt);
        assertThat(repository.findAllByRecipientId(recipientId)).hasSize(1);
    }

    @Test
    void upsert_changedStatePreservesIdentityAndCreatedAtWhileAdvancingUpdatedAt() {
        UUID recipientId = UUID.randomUUID();
        UUID firstSettingId = UUID.randomUUID();
        Instant createdAt = Instant.parse("2026-07-28T01:00:00Z");
        Instant changedAt = Instant.parse("2026-07-28T02:00:00Z");

        repository.upsert(
            firstSettingId, recipientId, "MARKETING", false, createdAt
        );
        assertThat(repository.upsert(
            UUID.randomUUID(), recipientId, "MARKETING", true, changedAt
        )).isEqualTo(1);

        NotificationSetting stored = repository
            .findByRecipientIdAndCategory(recipientId, NotificationCategory.MARKETING)
            .orElseThrow();

        assertThat(stored.getId()).isEqualTo(firstSettingId);
        assertThat(stored.isEnabled()).isTrue();
        assertThat(stored.getCreatedAt()).isEqualTo(createdAt);
        assertThat(stored.getUpdatedAt()).isEqualTo(changedAt);
        assertThat(repository.findAllByRecipientId(recipientId)).hasSize(1);
    }

    @Test
    void service_repeatedIdenticalUpdateReturnsStableState() {
        UUID recipientId = UUID.randomUUID();

        NotificationSettingUpdateResponse first = service.updateSetting(
            recipientId,
            NotificationCategory.PRODUCT,
            false
        );
        NotificationSettingUpdateResponse repeated = service.updateSetting(
            recipientId,
            NotificationCategory.PRODUCT,
            false
        );

        assertThat(repeated).isEqualTo(first);
    }

    @Test
    void upsert_keepsDifferentUsersAndCategoriesIndependent() {
        UUID firstRecipient = UUID.randomUUID();
        UUID secondRecipient = UUID.randomUUID();
        Instant now = Instant.parse("2026-07-28T03:00:00Z");

        repository.upsert(UUID.randomUUID(), firstRecipient, "PRODUCT", false, now);
        repository.upsert(UUID.randomUUID(), firstRecipient, "MARKETING", true, now);
        repository.upsert(UUID.randomUUID(), secondRecipient, "PRODUCT", true, now);

        assertThat(repository.findAllByRecipientId(firstRecipient))
            .extracting(NotificationSetting::getCategory)
            .containsExactlyInAnyOrder(
                NotificationCategory.PRODUCT,
                NotificationCategory.MARKETING
            );
        assertThat(repository.findAllByRecipientId(secondRecipient)).hasSize(1);
    }
}
