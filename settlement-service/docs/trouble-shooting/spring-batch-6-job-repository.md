# 정산 배치 재실행 시 batch_no 충돌 (Spring Batch 6)

## 환경

Spring Boot 4.0.6 / Spring Batch 6.0.3 / PostgreSQL

## 증상

같은 정산 기간으로 배치를 두 번째 실행하면 잡이 FAILED 된다.

```
ERROR: duplicate key value violates unique constraint "..."
Detail: Key (batch_no)=(SETTLE-202606-MANUAL-1) already exists.
```

실행할 때마다 jobExecutionId가 1로 고정돼 batch_no가 항상 같았다.

## 원인

Spring Batch 6부터 기본 JobRepository가 `ResourcelessJobRepository`(in-memory)로 바뀌었다.
DataSource가 있어도 잡 메타데이터를 DB에 저장하지 않는다. 그 결과:

- 잡 실행 이력이 프로세스 메모리에만 남아 jobExecutionId가 매번 1부터 시작한다.
- batch_no는 `SETTLE-{기간}-{트리거}-{jobExecutionId}` 로 만드는데, jobExecutionId가 늘 1이라 값이 고정된다.
- `settlement_batch.batch_no` UNIQUE 제약 때문에 2회차 INSERT부터 충돌한다.

batch_no가 충돌이 드러나는 지점이지만, 근본 원인은 JobRepository가 영속이 아니라는 것이다.

## 해결

JDBC 영속 JobRepository로 전환한다. 이때 세 가지를 같이 처리해야 한다.

### 1. JDBC JobRepository 적용

`@EnableBatchProcessing` + `@EnableJdbcJobRepository` 를 붙인다.
Spring Boot 4에는 resourceless ↔ JDBC를 고르는 프로퍼티가 없어서 애너테이션으로 명시해야 한다.

```java
@Configuration
@EnableBatchProcessing
@EnableJdbcJobRepository(dataSourceRef = "dataSource", transactionManagerRef = "transactionManager")
public class SettlementBatchConfig {
    ...
}
```

### 2. JobRegistry 빈 등록

`@EnableBatchProcessing` 은 JobRegistry를 자동 등록하지 않는다.
`TaskExecutorJobOperator` 가 JobLocator로 JobRegistry를 요구하므로(없으면 `JobLocator must be provided`)
직접 등록한다.

```java
@Bean
public JobRegistry jobRegistry() {
    return new MapJobRegistry();
}
```

### 3. batch 메타 스키마 생성

`@EnableBatchProcessing` 을 붙이면 Boot의 batch auto-config가 backoff되고,
그 안에 있던 메타 스키마 자동 생성(`spring.batch.jdbc.initialize-schema`)도 같이 꺼진다.
게다가 Boot 4에서는 `BatchDataSourceScriptDatabaseInitializer` 가 제거돼 그 빈을 되살릴 수도 없다.

Spring 표준 `DataSourceInitializer` 로 batch 스키마 스크립트(jar에 내장)를 직접 실행한다.

```java
@Bean
public DataSourceInitializer batchSchemaInitializer(DataSource dataSource) {
    ResourceDatabasePopulator populator = new ResourceDatabasePopulator();
    populator.addScript(new ClassPathResource("org/springframework/batch/core/schema-postgresql.sql"));
    populator.setContinueOnError(true); // 메타 테이블이 이미 있으면 무시
    DataSourceInitializer initializer = new DataSourceInitializer();
    initializer.setDataSource(dataSource);
    initializer.setDatabasePopulator(populator);
    return initializer;
}
```

## 결과

- 앱 기동 시 `batch_job_*`, `batch_step_*` 6개 메타 테이블이 자동 생성된다(이미 있으면 무시).
- jobExecutionId가 영속 증가하고 batch_no가 유니크해져, 재실행·증분 정산이 정상 동작한다.

## 참고

- 무의미해진 `spring.batch.jdbc.initialize-schema` 프로퍼티는 제거했다.
- 검증할 때 메타 테이블을 비우려면 jar의 `schema-drop-postgresql.sql` 을 쓰면 된다.
