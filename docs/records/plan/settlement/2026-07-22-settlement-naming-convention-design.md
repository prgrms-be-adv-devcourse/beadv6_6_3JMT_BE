# 정산 서비스 배치 실행 경계 네이밍 정리 설계

## 배경

#463 (PR)의 실패 정산 배치 재시작 기능 리뷰에서 클래스 네이밍을 일관되게 정리할 필요가 있다는
의견이 있었다. settlement-service의 93개 main Java 타입을 확인한 결과, 문제는 도메인·영속성·표현
계층 전반이 아니라 application과 Spring Batch 실행 경계에 집중되어 있다.

- 같은 실행 흐름에서 `SettlementBatch`와 `SettlementJob` 용어가 혼용된다.
- `~UseCase`와 `~ApplicationService`의 어근과 단어 순서가 일관되지 않는다.
- `SettlementJobRestarter`와 `SettlementJobRestartAdapter`처럼 포트와 어댑터 이름이 정확히 대응하지 않는다.
- `infrastructure.batch.launcher`가 실행뿐 아니라 상태 조회, 재시작과 파라미터 생성도 담당한다.
- 현재 아키텍처 규칙이 이 구조를 명시하고 있어 코드와 규칙을 함께 정리해야 한다.

관련 작업은 #495 (이슈)에서 추적한다.

## 목표

- 업무 경계와 Spring Batch 기술 경계의 용어를 구분한다.
- 인바운드 포트와 구현체가 이름만으로 연결되게 한다.
- 비영속 아웃바운드 포트와 어댑터의 이름을 일관되게 한다.
- Spring Batch 실행 연동 패키지가 실제 책임을 드러내게 한다.
- 현재 코드와 아키텍처 규칙·다이어그램을 동기화한다.

## 제외 범위

- API URL, 요청 필드와 응답 JSON 변경
- 설정 프로퍼티 변경
- DB 스키마와 데이터 변경
- Spring Batch Job·Step 및 명시적 `@Bean` 이름 변경
- 정산 배치 상태 전이와 재시작 동작 변경
- Outbox 발행·재시도 동작 변경
- Domain·Persistence·Presentation 전반의 일괄 리네임
- 과거 `docs/superpowers/specs`와 `docs/superpowers/plans` 문서의 소급 수정

과거 설계·계획 문서는 당시 구현 기록으로 유지한다. 현재 규칙과 현재 구조를 설명하는 문서만 새 이름으로
갱신한다.

## 용어 경계

`SettlementBatch`는 정산 도메인의 실행 단위다. application과 presentation에서는 배치의 실행 요청,
재시작 요청과 상태 전이를 이 용어로 표현한다.

`SettlementJob`은 Spring Batch의 `Job`, `JobInstance`, `JobExecution`을 다루는 기술 경계다.
`JobOperator`와 `JobRepository`를 사용하는 아웃바운드 포트·어댑터와 실제 잡 실행 결과에서 사용한다.

따라서 실행 흐름은 다음 이름을 사용한다.

```text
RunSettlementBatchRequest
  -> RunSettlementBatchCommand
  -> RunSettlementBatchUseCase
  -> RunSettlementBatchApplicationService
  -> SettlementJobLauncher
  -> SettlementJobLauncherAdapter
  -> SettlementJobResult
```

`SettlementJobResult`와 `SettlementJobStatusResult`는 `jobExecutionId`, 잡 이름과 Spring Batch 상태를
반환하므로 유지한다.

## 인바운드 포트와 애플리케이션 서비스

애그리거트 중심 유스케이스는 `{Aggregate}UseCase`와 `{Aggregate}ApplicationService`를 사용한다.
배치 실행·원천 적재·계산처럼 독립 작업을 나타내는 유스케이스는 `{Verb}{Object}UseCase`와
`{Verb}{Object}ApplicationService`로 어근을 정확히 맞춘다.

| 인바운드 포트 | 구현체 |
| --- | --- |
| `CalculateSettlementUseCase` | `CalculateSettlementApplicationService` |
| `LoadSettlementSourceUseCase` | `LoadSettlementSourceApplicationService` |
| `RunSettlementBatchUseCase` | `RunSettlementBatchApplicationService` |
| `RestartSettlementBatchUseCase` | `RestartSettlementBatchApplicationService` |
| `GetSettlementJobStatusUseCase` | `GetSettlementJobStatusApplicationService` |
| `OutboxEventUseCase` | `OutboxEventApplicationService` |

인바운드 포트를 구현하지 않는 내부 트랜잭션 서비스는 구체 책임을 이름에 남긴다.

- `SettlementBatchRetryStateService`: 재시작 검증과 실패 상태 복원 트랜잭션
- `OutboxEventPublishService`: Outbox 이벤트 한 건의 발행 트랜잭션

## 비영속 아웃바운드 포트와 어댑터

`application/port` 패키지가 포트임을 나타내므로 인터페이스에는 `Port` 접미사를 반복하지 않고
기술 중립적인 능력 이름을 사용한다. 구현체는 같은 능력 어근을 유지하고 기술 또는 구현 역할을 이름에
추가한다.

| 포트 | 어댑터 |
| --- | --- |
| `OrderSettlementQuery` | `OrderSettlementQueryClient` |
| `OutboxEventAppender` | `JsonOutboxEventAppender` |
| `SettlementEventPublisher` | `KafkaSettlementEventPublisher` |
| `SettlementJobLauncher` | `SettlementJobLauncherAdapter` |
| `SettlementJobQuery` | `SettlementJobQueryAdapter` |
| `SettlementJobRestarter` | `SettlementJobRestarterAdapter` |

## Spring Batch 패키지와 역할 이름

`JobOperator`와 `JobRepository`로 잡을 시작·조회·재시작하는 클래스와 잡 파라미터 팩토리는
`infrastructure.batch.execution`에 둔다. `launcher`는 시작 동작만 의미해 현재 책임 전체를 설명하지
못하므로 사용하지 않는다.

```text
infrastructure/batch/execution
├── SettlementJobLauncherAdapter
├── SettlementJobQueryAdapter
├── SettlementJobRestarterAdapter
└── SettlementJobParametersFactory
```

추가로 다음 역할 이름을 정리한다.

| 현재 이름 | 변경 이름 | 근거 |
| --- | --- | --- |
| `SettlementBatchConfig` | `SpringBatchInfrastructureConfig` | JobRepository 스키마, JobRegistry와 JobOperator 인프라를 설정한다. |
| `SettlementBatchExecutionListener` | `SettlementBatchStateJobExecutionListener` | JobExecution 전후에 도메인 배치 상태를 동기화한다. |
| `SettlementItem` | `SettlementTarget` | 판매자·기간·배치 ID로 구성된 정산 처리 대상을 나타낸다. |

`SettlementCronJobRunner`와 `SettlementBatchRestartRunner`는 실행 모드와 역할을 이미 구분하므로 유지한다.

## 문서 반영

`.claude/rules/clean-architecture.md`를 현재 코드의 단일 규칙 문서로 갱신한다.

- 실제 `com.prompthub.settlement` 하위 패키지 구조
- `SettlementBatch`와 `SettlementJob`의 용어 경계
- UseCase와 ApplicationService의 이름 대응
- 비영속 포트와 어댑터의 명명 방식
- `infrastructure.batch.execution` 패키지 책임

`docs/architecture/settlement-spring-batch-flow.excalidraw`과 PNG에 표시된 현재 클래스명을 새 이름과
동기화한다. 두 산출물 중 하나만 갱신된 상태는 허용하지 않는다.

## 동작 보존과 검증

이 작업은 이름과 패키지만 변경한다. 클래스 리네임에 따라 Spring 기본 컴포넌트 빈 이름은 바뀔 수 있지만,
이 이름을 문자열·Qualifier로 참조하는 코드가 없음을 확인한다. 예외 처리, 트랜잭션 경계, 상태 전이와
데이터 흐름은 유지한다.
리네임 중 행동 변경이 필요한 문제가 발견되면 #495 (이슈)에 섞지 않고 별도 이슈 후보로 분리한다.

검증은 다음 순서로 수행한다.

1. `origin/develop` 기준 `./gradlew :settlement-service:test`로 기준선을 확인한다.
2. 이름·패키지를 책임 묶음별로 변경하고 관련 단위 테스트를 실행한다.
3. 실패 정산 배치 재시작 통합 테스트를 실행한다.
4. `./gradlew :settlement-service:clean :settlement-service:test :settlement-service:build`를 실행한다.
5. 현재 소스·테스트·규칙·아키텍처 문서에 이전 이름과 `batch.launcher` 참조가 남지 않았는지 검색한다.
6. API·설정·DB·Job·Step·명시적 `@Bean` 이름이 변경되지 않았는지 전체 diff를 확인한다.
7. Excalidraw 원본과 PNG를 렌더링해 텍스트와 레이아웃을 확인한다.

과거 설계·계획 문서는 이전 이름 검색 대상에서 제외한다.

## 작업 순서

1. 아키텍처 규칙과 현재 다이어그램을 새 네이밍 계약으로 갱신한다.
2. application DTO·포트·서비스와 관련 테스트를 리네임한다.
3. Spring Batch execution 패키지·설정·리스너·모델과 관련 테스트를 리네임한다.
4. 정적 검색, 관련 테스트, 통합 테스트와 전체 빌드로 동작 보존을 확인한다.
