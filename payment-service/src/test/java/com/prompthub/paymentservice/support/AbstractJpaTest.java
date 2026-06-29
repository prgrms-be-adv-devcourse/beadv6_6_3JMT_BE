package com.prompthub.paymentservice.support;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.PostgreSQLContainer;

// AbstractIntegrationTest와 동일한 이유로 싱글턴 패턴 사용.
// @Testcontainers + @Container 조합은 각 서브클래스 afterAll에서 static 컨테이너를
// 재시작시켜 Spring ApplicationContext 캐시와 포트 불일치를 야기한다.
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.NONE,
    properties = {
        "spring.autoconfigure.exclude=org.springframework.boot.kafka.autoconfigure.KafkaAutoConfiguration"
    }
)
@ActiveProfiles("test")
public abstract class AbstractJpaTest {

    @MockitoBean
    @SuppressWarnings("rawtypes")
    KafkaTemplate kafkaTemplate;

    static final PostgreSQLContainer<?> postgres;

    static {
        postgres = new PostgreSQLContainer<>("postgres:16-alpine");
        postgres.start();
    }

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "create");
    }
}
