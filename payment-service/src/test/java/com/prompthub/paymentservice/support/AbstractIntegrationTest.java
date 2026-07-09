package com.prompthub.paymentservice.support;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.KafkaContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

// @Testcontainers + @Container 대신 싱글턴 패턴 사용.
// JUnit 5의 @Testcontainers는 각 테스트 클래스 afterAll에서 static @Container를 stop()한다.
// 여러 서브클래스가 같은 static 컨테이너를 공유하면, 첫 번째 클래스 완료 후 컨테이너가
// 재시작되어 Spring ApplicationContext 캐시와 포트 불일치가 발생한다.
// static 초기화 블록으로 JVM 기동 시 1회만 시작하고 JVM 종료 훅으로 정리한다.
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
public abstract class AbstractIntegrationTest {

    public static final PostgreSQLContainer<?> postgres;
    public static final KafkaContainer kafka;

    static {
        postgres = new PostgreSQLContainer<>("postgres:16-alpine");
        kafka = new KafkaContainer(DockerImageName.parse("confluentinc/cp-kafka:7.6.1"));
        postgres.start();
        kafka.start();
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "create");
        registry.add("spring.kafka.bootstrap-servers", kafka::getBootstrapServers);
    }
}
