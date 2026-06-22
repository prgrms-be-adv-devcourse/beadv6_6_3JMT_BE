# payment-service

`beadv6_6_3JMT_BE` 모노레포(팀 3JMT) 안의 결제 MSA 서비스. **Spring Boot 4.1 / Java 21**, 클린 아키텍처 기반.

## 언어 정책

- 문서·커밋 메시지·코드 주석·테스트 메서드명: **한국어**
- 클래스/필드/메서드 식별자, 커밋 타입 접두사(`feat:` 등): **영어**
- AI 응답: **한국어**

## 빌드 / 실행 / 테스트

```bash
./gradlew build                       # 빌드
./gradlew bootRun                      # 실행 (포트 8081)
./gradlew test                         # 전체 테스트
./gradlew test --tests "com.prompthub.paymentservice.PaymentServiceApplicationTests"  # 단일
docker-compose up -d                   # 로컬 PostgreSQL (호스트 5433)
```

- 실행 전 `.env` 필요: `DB_URL` / `DB_USERNAME` / `DB_PASSWORD` (gitignore 대상, 커밋 금지).
- 테스트는 Testcontainers + EmbeddedKafka를 쓰므로 compose DB 없이 동작한다.
- `common-module`은 composite build(`includeBuild '../common-module'`)로 `com.prompthub:common-module`로 해석된다. 단독 빌드는 모노레포 전체 체크아웃이 전제.
- Checkstyle은 공유 룰(`../style/checkstyle/prompthub-checkstyle-rules.xml`) 사용. 현재 `ignoreFailures = true`지만 위반 코드는 작성하지 않는다.

## 상세 규칙 (해당 작업 시 먼저 읽기)

아래 작업을 할 때는 반드시 해당 파일을 먼저 읽고 그 규칙을 따른다.

- **코드 배치·레이어·패키지 구조·의존 방향** → `.claude/rules/architecture.md`
- **REST 컨트롤러·예외 처리·API 응답 형식** → `.claude/rules/api-error-handling.md`
- **커밋·브랜치·PR** → `.claude/rules/git-conventions.md`

## 도메인 참조 문서

API 설계·DB·이벤트 관련 작업 시 아래 문서를 먼저 확인한다.

- **API 설계 (Swagger/OpenAPI)** → `.claude/docs/api-design.md`
- **DB 테이블 구조** → `.claude/docs/db-schema.md`
- **Kafka 이벤트 계약** → `.claude/docs/events.md`

## 테스트 정책

- 새 기능은 **테스트와 함께** 추가(가능하면 TDD: 실패 테스트 → 구현 → 통과).
- 통합/영속성 테스트는 **Testcontainers(PostgreSQL)**, Kafka는 **EmbeddedKafka**(`spring-kafka-test`). H2 등 인메모리 DB로 대체하지 않는다.
- 단언은 **AssertJ**(`assertThat`).
- 영속성 테스트는 엔티티 `create(...)` 팩토리로 객체를 만들어 라운드트립 + 감사 필드 검증(기존 `PaymentJpaRepositoryTest` 패턴).


## AI 작업 원칙

- 사용자 동의 없이 기존 코드/파일을 삭제·변경하지 않는다(테스트 목적이라도). 변경 전 의도를 먼저 공유.
- 추측으로 외부 이벤트 계약(Kafka 토픽/스키마)이나 미확정 구조를 임의 구현하지 않는다. 미확정 영역은 질문.
- 작업 후 테스트 실행 결과를 사실대로 보고(실패 시 출력 포함).
