# 클린아키텍처 컨벤션

정산 서비스(settlement-service)의 패키지 구조와 계층 규칙을 정의한다.
헥사고날(포트 & 어댑터) 스타일을 기반으로 한다.

> 관련 문서
> - 도메인 모델·Lombok 규칙: `domain-model.md`
> - Controller·예외 처리 규칙: `controller-exception.md`
> - 코드 스타일(네이밍 케이스·import 등): `code-style.md`

## 1. 의존성 규칙

의존성은 항상 안쪽(도메인)을 향한다.

```
presentation ──▶ application ──▶ domain ◀── infrastructure
```

- `domain`은 다른 어떤 계층도 import 하지 않는다. (단, 엔티티 겸용 정책상 JPA는 예외 — `domain-model.md` 참고)
- `application`은 `domain`만 의존한다. presentation·infrastructure를 모른다.
- `presentation`·`infrastructure`는 바깥 계층이며, 안쪽(application·domain)에 의존한다.
- 바깥에서 안쪽으로의 호출은 **포트(인터페이스)**를 통한다.

이 방향이 깨지면(예: domain이 infrastructure를 import) 구조 위반으로 본다.

## 2. 패키지 구조

기능(도메인) 단위로 패키지를 나누고, 그 안에 4계층 + config를 둔다.

```
com.prompthub.settlement
└── settlement                      ← 기능(도메인) 패키지
    ├── presentation
    │   ├── controller              ← REST 진입점
    │   └── dto
    │       ├── request             ← 요청 DTO
    │       └── response            ← 응답 DTO
    ├── application
    │   ├── usecase                 ← 인바운드 포트(인터페이스)
    │   ├── service                 ← 유스케이스 구현
    │   ├── port                    ← 아웃바운드 포트(비영속: 배치 실행·메시징 등)
    │   └── dto                     ← Command / Result
    ├── domain
    │   ├── model                   ← 도메인 모델(= JPA 엔티티 겸용)
    │   └── repository              ← 아웃바운드 포트(인터페이스)
    ├── infrastructure
    │   ├── persistence             ← JpaRepository, RepositoryAdapter
    │   ├── batch                   ← Job / Step / Reader / Processor / Writer
    │   └── event                   ← 메시징·이벤트 어댑터
    └── config                      ← 해당 기능 전용 설정

com.prompthub.settlement.global       ← 기능 횡단(cross-cutting) 공통
└── exception                         ← 전역 예외 핸들러·ErrorCode 매핑
```

기능이 늘어나면 `settlement`와 같은 레벨에 새 기능 패키지를 추가한다.

전역 예외 처리는 특정 기능에 속하지 않는 횡단 관심사이므로 `global/exception`에 둔다.
`@RestControllerAdvice` 전역 핸들러와 `ErrorCode`(HttpStatus 매핑) 구현을 이곳에 모은다.
`ErrorCode`는 `HttpStatus`(웹 프레임워크)에 의존하므로 **domain 에 두지 않는다.**
공통 응답·예외 베이스(`ApiResponse`·`ErrorResponse`·`BusinessException`·`ErrorCode`)는 common-module 을 사용한다.

## 3. 계층별 책임

| 계층 | 담는 것 | 하면 안 되는 것 |
| --- | --- | --- |
| `presentation` | HTTP 요청/응답 처리, request·response DTO 변환 | 비즈니스 로직, 도메인 모델 직접 노출 |
| `application` | 유스케이스 조율, 트랜잭션 경계, Command·Result 변환 | HTTP·JPA 등 기술 세부사항 직접 다루기 |
| `domain` | 핵심 비즈니스 규칙, 도메인 모델, 포트 정의 | 다른 계층 의존(JPA 제외), 프레임워크 로직 |
| `infrastructure` | 포트 구현(어댑터), DB·배치·메시징 등 기술 연동 | 비즈니스 규칙 |

핵심:
- 컨트롤러는 얇게. 받고 → usecase 호출 → 응답으로 변환만 한다.
- 비즈니스 규칙은 `domain`에, 흐름 조율은 `application`에 둔다.

## 4. 포트 & 어댑터 규칙

| 구분 | 포트(인터페이스) 위치 | 구현(어댑터) 위치 |
| --- | --- | --- |
| 인바운드(유스케이스) | `application/usecase` | `application/service` |
| 아웃바운드(영속성) | `domain/repository` | `infrastructure/persistence` |
| 아웃바운드(비영속: 배치·메시징 등) | `application/port` | `infrastructure/batch` · `infrastructure/event` |

- 인바운드: `SettlementUseCase`(포트) ← `SettlementApplicationService`(구현)
- 아웃바운드(영속성): `SettlementRepository`(포트, domain) ← `SettlementRepositoryAdapter`(구현, infrastructure)
- 아웃바운드(비영속): `SettlementJobLauncher`(포트, application) ← `SettlementJobLauncherAdapter`(구현, infrastructure)
- 어댑터는 내부에서 `SettlementJpaRepository`(Spring Data) 를 호출한다.

> **비영속 아웃바운드 포트는 `application/port`에 둔다.** 잡 실행·메시지 발행처럼 '영속성'이 아닌
> 외부 연동은 도메인이 알 필요가 없으므로 `domain/repository`에 두지 않는다. application 이 필요로 하는
> 인터페이스를 application 이 소유하고, 바깥 계층(infrastructure)이 구현한다(§1 의존 방향 부합).

```
application/service/SettlementApplicationService   implements   application/usecase/SettlementUseCase
                                │ 호출
                                ▼
domain/repository/SettlementRepository  ◀ implements ◀  infrastructure/persistence/SettlementRepositoryAdapter
                                                                            │ 호출
                                                                            ▼
                                                        infrastructure/persistence/SettlementJpaRepository
```

## 5. 배치 규칙

Spring Batch 구성은 기술 세부사항으로 보고 `infrastructure/batch`에 둔다.

- Job / Step / Reader / Processor / Writer 설정은 모두 `infrastructure/batch`.
- **배치는 흐름 제어만 한다.** 실제 정산 로직은 `application`의 유스케이스를 호출해 수행한다.
- Reader/Writer가 도메인 모델을 직접 다루더라도, 비즈니스 규칙은 도메인·유스케이스에 위임한다.

```
infrastructure/batch/SettlementJobConfig
infrastructure/batch/SettlementStepConfig
infrastructure/batch/reader/...
infrastructure/batch/processor/...   ──▶ application/usecase 호출
infrastructure/batch/writer/...
```

## 6. 계층 네이밍 규칙

계층·역할별 클래스 접미사를 통일한다. (일반 Java 네이밍 케이스 규칙은 `code-style.md` 참고)

| 계층/역할 | 접미사 | 예시 |
| --- | --- | --- |
| 컨트롤러 | `~Controller` | `SettlementController` |
| 요청 DTO | `~Request` | `CreateSettlementRequest` |
| 응답 DTO | `~Response` | `SettlementResponse` |
| 인바운드 포트 | `~UseCase` | `SettlementUseCase` |
| 유스케이스 구현 | `~ApplicationService` | `SettlementApplicationService` |
| 입력 Command | `~Command` | `CreateSettlementCommand` |
| 출력 Result | `~Result` | `SettlementResult` |
| 아웃바운드 포트 | `~Repository` | `SettlementRepository` |
| 영속성 어댑터 | `~RepositoryAdapter` | `SettlementRepositoryAdapter` |
| Spring Data | `~JpaRepository` | `SettlementJpaRepository` |
| 배치 잡 설정 | `~JobConfig` | `SettlementJobConfig` |
| 설정 | `~Config` | `SettlementBatchConfig` |
| 도메인 예외 | `~Exception` | `SettlementAlreadyCompletedException` |

## 7. DTO 변환 규칙

계층 경계를 넘을 때마다 DTO를 변환해, 안쪽 모델이 바깥으로 새지 않게 한다.

```
Request ──▶ Command ──▶ (domain) ──▶ Result ──▶ Response
 표현         애플리케이션      도메인        애플리케이션    표현
```

- 도메인 모델(`@Entity`)을 컨트롤러 응답으로 직접 반환하지 않는다. 반드시 `~Response`로 변환한다.
- 변환 코드는 각 DTO의 정적 팩토리 메서드(`from`, `of`)에 둔다.
  (예: `SettlementResponse.from(settlementResult)`, `request.toCommand()`)
- 변환 로직이 복잡해지면 표현/애플리케이션 계층에 전용 매퍼를 둘 수 있다.
