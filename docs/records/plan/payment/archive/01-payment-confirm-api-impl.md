# 결제 승인 API 구현 결과

`01-payment-confirm-api.md` 계획 대비 실제 구현 중 달라진 결정과 그 배경을 기록한다.
설계 의도·트레이드오프는 01번 파일을 참조한다.

---

## 계획 대비 변경 결정

### 1. `@WebMvcTest` → `MockMvcBuilders.standaloneSetup()`

**원래 계획**: `@WebMvcTest`로 컨트롤러 단위 테스트  
**실제 구현**: `MockMvcBuilders.standaloneSetup()` + `@ExtendWith(MockitoExtension.class)`

**이유**: Spring Boot 4.1에서 `@WebMvcTest`가 지원되지 않아 standaloneSetup으로 대체.
`PaymentExceptionHandler`를 `setControllerAdvice()`로 수동 등록,
`LocalValidatorFactoryBean`을 `setValidator()`로 직접 설정해 `@Valid` 검증 활성화.

---

### 2. `EmbeddedKafka` → Testcontainers `KafkaContainer`

**원래 계획**: `EmbeddedKafka`로 통합 테스트용 Kafka 브로커 구동  
**실제 구현**: `testcontainers-kafka` + `confluentinc/cp-kafka:7.6.1`

**이유**: macOS KRaft 브로커에서 `Exit.halt(1, null)` 충돌 발생.
`EmbeddedKafka`(= `spring-kafka-test`) 자체를 사용하지 않기로 확정.

---

### 3. `TestRestTemplate` → `RestTemplate` + 수동 오류 처리

**원래 계획**: `TestRestTemplate`로 통합 테스트 HTTP 요청  
**실제 구현**: `RestTemplate` + `DefaultResponseErrorHandler` 오버라이드

**이유**: Spring Boot 4.1에서 `TestRestTemplate` 빈이 제공되지 않음.
`setErrorHandler(new DefaultResponseErrorHandler() { ... })`로 4xx/5xx 응답을
예외 없이 수신하도록 오버라이드해 직접 상태 코드 검증.

---

### 4. Kafka 소비: `consumer.subscribe()` → `consumer.assign()` + `consumer.seekToBeginning()`

**원래 계획**: 그룹 기반 `subscribe()`로 메시지 소비  
**실제 구현**: `consumer.assign(partitions)` + `consumer.seekToBeginning(partitions)`

**이유**: `subscribe()` 사용 시 그룹 리밸런싱 지연으로 테스트 실행 중 메시지를 놓치는
타이밍 이슈 발생. `assign()` + `seekToBeginning()`으로 파티션을 직접 지정해
리밸런싱 없이 오프셋 0부터 읽도록 변경.

---

### 5. `PaymentApprovedMessage.approvedAt`: `OffsetDateTime` → `String`

**원래 계획**: `approvedAt` 필드를 `OffsetDateTime` 타입으로 선언  
**실제 구현**: `String` 타입(ISO 8601 문자열), publisher에서 `.toString()` 변환 후 전달

**이유**: Spring Kafka 4.x 내부 `JsonSerializer`가 Jackson 2.x를 사용하는데,
`jackson-datatype-jsr310` 모듈이 Spring Boot 4.1 BOM에 포함되지 않아
`OffsetDateTime` 직렬화 실패. 소비자 측에서 `OffsetDateTime.parse()`로 역변환 가능.

---

### 6. Kafka 발행 방식 구현 이력 — 직접 호출 임시 전환 후 원래 방식 복원

**원래 계획**: `ApplicationEventPublisher.publishEvent()` → `@TransactionalEventListener(AFTER_COMMIT)`  
**임시 변경**: `PaymentEventPublisher` 직접 호출 방식으로 전환  
**최종 구현**: 원래 계획대로 `ApplicationEventPublisher` 방식으로 복원

**이유**:
- `AFTER_COMMIT` 단계에서 `OffsetDateTime` 직렬화 실패가 HTTP 응답에 노출되지 않아
  오류 원인 파악이 어려웠음.
- 직접 호출 방식으로 임시 전환해 오류를 가시화한 뒤 근본 원인(`OffsetDateTime` → `String` 변환) 확인.
- `PaymentApprovedMessage.approvedAt`을 `String`으로 수정한 후 원래 설계로 복원.

---

## 검증 완료 항목

| 테스트 | 방식 | 결과 |
|---|---|---|
| `PaymentTest` | 단위 (JUnit 5) | ✅ |
| `PaymentJpaRepositoryTest` | Testcontainers PostgreSQL | ✅ |
| `ConfirmPaymentServiceTest` (구 Interactor) | Mockito 단위 | ✅ |
| `PaymentControllerTest` | MockMvc standaloneSetup | ✅ |
| `ConfirmPaymentIntegrationTest` | Testcontainers PostgreSQL + Kafka | ✅ |
