# 클린아키텍처 컨벤션

서비스의 패키지 구조와 계층 규칙을 정의하는 팀 공용 표준이다.
헥사고날(포트 & 어댑터) 스타일을 기반으로 한다. 예시는 `Xxx`라는 가상 엔티티, 배치가 있는 경우
`XxxBatch`, gRPC로 호출하는 외부 서비스는 `Yyy`로 든다. 실제 코드에서는 서비스 도메인명으로
치환한다(예: `Xxx` → `Payment`·`Order`·`Settlement` 등). 예시 이름을 그대로 복사해 쓰지 않는다.
아래 패키지 트리·배치 규칙(§5)은 Spring Batch를 쓰는 서비스의 예시이며, 쓰지 않는 서비스는
§5를 적용하지 않는다.

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
  - **예외: 조회(읽기) 유스케이스, 그리고 상태 변경이 단순해 `~Result`가 불필요한 명령 흐름은 application
    서비스가 presentation `~Response`를 직접 만들어 반환할 수 있다.** 중간 `~Result` DTO를 따로 두지 않아
    중복 객체·변환을 줄이려는 실용적 타협이다. 이때만 application → presentation 의존을 허용한다.
    (단순 상태 변경 명령으로 이 예외를 쓰는 기준은 §7 참고)
- `presentation`·`infrastructure`는 바깥 계층이며, 안쪽(application·domain)에 의존한다.
- 바깥에서 안쪽으로의 호출은 **포트(인터페이스)**를 통한다.

이 방향이 깨지면(예: domain이 infrastructure를 import) 구조 위반으로 본다.
단, 위 응답 예외(조회·단순 상태 변경 명령에서 application 서비스가 `~Response` 반환)는 허용으로 본다.

## 2. 패키지 구조

서비스의 base package 아래에 4계층과 횡단 관심사를 둔다. 해당 서비스가 실제로 쓰는 기술만
하위 패키지로 둔다(배치·Kafka·gRPC 클라이언트를 안 쓰면 그 하위 패키지는 만들지 않는다).

```
com.prompthub.xxx   ← 예시. 서비스마다 자기 base package를 쓴다
├── presentation
│   ├── controller                   ← REST 진입점
│   └── dto
│       ├── request                  ← 요청 DTO
│       └── response                 ← 응답 DTO
├── application
│   ├── usecase                      ← 인바운드 포트(인터페이스)
│   ├── service                      ← 유스케이스 구현
│   ├── port                         ← 비영속 아웃바운드 포트(배치 실행·메시징·동기 조회)
│   ├── event                        ← 외부 이벤트 envelope·이벤트 상세 DTO
│   └── dto                          ← Command / Result / Query(조회 조건)
├── domain
│   ├── model                        ← 도메인 모델(= JPA 엔티티 겸용)
│   ├── exception                    ← 도메인 상태·규칙 예외
│   └── repository                   ← 영속성 아웃바운드 포트 + 조회결과 record
├── infrastructure
│   ├── persistence                  ← JpaRepository, RepositoryAdapter
│   ├── batch                        ← Spring Batch 어댑터(역할별 하위 패키지로 분리, 배치를 쓰는 경우)
│   │   ├── config                   ← Job / Step / Batch 인프라 설정
│   │   ├── execution                ← JobOperator·JobRepository 기반 시작·조회·재시작
│   │   ├── reader                   ← Reader
│   │   ├── processor                ← Processor
│   │   ├── writer                   ← Writer
│   │   ├── tasklet                  ← Tasklet
│   │   ├── listener                 ← Job/Step 리스너
│   │   ├── runner                   ← Kubernetes CronJob one-shot 실행 어댑터
│   │   └── model                    ← 배치 내부 전용 DTO
│   ├── messaging/kafka
│   │   ├── config                   ← Kafka producer 설정
│   │   └── producer                 ← Outbox 이벤트 발행 어댑터
│   └── client/<외부 서비스>          ← 다른 서비스 gRPC 동기 조회 어댑터(호출 대상마다 하위 패키지 분리)
└── global                           ← 기능 횡단(cross-cutting) 공통
    ├── exception                    ← 전역 예외 핸들러·ErrorCode 매핑
    ├── config                       ← 전역 횡단 설정(예: 도메인 기준 Clock)
    ├── common                       ← 공통 영속성 기반 타입
    └── web                          ← 인증 전달 헤더
```

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
| 아웃바운드(비영속: 배치·메시징 등) | `application/port` | `infrastructure/batch` · `infrastructure/messaging/kafka` |
| 아웃바운드(비영속: 타 서비스 동기 조회) | `application/port` | `infrastructure/client` |

- 인바운드: `XxxUseCase`(포트) ← `XxxApplicationService`(구현)
- 아웃바운드(영속성): `XxxRepository`(포트, domain) ← `XxxRepositoryAdapter`(구현, infrastructure)
- 아웃바운드(비영속): `XxxJobLauncher`(포트, application) ← `XxxJobLauncherAdapter`(구현, infrastructure)
- 아웃바운드(타 서비스 동기 조회): `YyyQuery`(포트, application) ←
  `YyyQueryClient`(구현, `infrastructure/client/yyy`). `Yyy`는 호출 대상 서비스를 뜻한다.
  다른 서비스의 gRPC 서버를 블로킹 스텁으로 호출한다. (메시징이 아니므로 `messaging/kafka` 와 분리)
  - **호출 대상 서비스별로 하위 패키지를 나눈다.** `client/<서비스A>`, `client/<서비스B>`처럼
    서비스마다 어댑터(`~QueryClient`)와 그 서비스 전용 채널·스텁 빈 설정(`config/~Config`)을 함께 둔다.
  - 채널·블로킹 스텁 빈은 서비스별 `config` 패키지의 `@Configuration`(예: `YyyGrpcClientConfig`)에서
    각자 생성한다. 단일 공용 config로 묶지 않고 서비스 단위로 분리해 주소·옵션을 서비스별로 독립 관리한다.
  - 채널 주소는 yml(`grpc.client.<service>.address`)로 주입한다. proto/생성 스텁 패키지
    (`com.prompthub.xxx.grpc.*`)는 `src/main/proto` 기준이며 이 client 패키지 분리와 무관하다.
- 어댑터는 내부에서 `XxxJpaRepository`(Spring Data) 를 호출한다.
- **포트가 반환하는 조회결과 record(예: 페이징 묶음·집계 결과)는 `domain/repository`에 둘 수 있다.**
  도메인 포트의 반환 타입이라 도메인에 있어야 하고(application/dto로 빼면 domain→application 역의존), 포트
  계약의 일부다. 포트 인터페이스 안의 중첩 record(예: `XxxListQueryRepository.XxxPage`)나
  같은 패키지의 단독 record(예: `XxxStatusAggregate`) 둘 다 허용한다. `domain/repository`가
  "인터페이스만" 담는다는 제약의 예외다.

> **비영속 아웃바운드 포트는 `application/port`에 둔다.** 잡 실행·메시지 발행·타 서비스 동기 조회처럼
> '영속성'이 아닌 외부 연동은 도메인이 알 필요가 없으므로 `domain/repository`에 두지 않는다. application 이
> 필요로 하는 인터페이스를 application 이 소유하고, 바깥 계층(infrastructure)이 구현한다(§1 의존 방향 부합).

`application/port` 패키지 자체가 포트임을 나타내므로 비영속 포트 이름에는 `Port` 접미사를 반복하지
않는다. 기술 중립적인 능력 이름을 사용하고, 어댑터는 같은 능력 어근에 기술 또는 구현 역할을 붙인다.

```text
YyyQuery           ← YyyQueryClient
XxxJobLauncher      ← XxxJobLauncherAdapter
XxxJobQuery         ← XxxJobQueryAdapter
XxxJobRestarter     ← XxxJobRestarterAdapter
```

```
application/service/XxxApplicationService   implements   application/usecase/XxxUseCase
                                │ 호출
                                ▼
domain/repository/XxxRepository  ◀ implements ◀  infrastructure/persistence/XxxRepositoryAdapter
                                                                    │ 호출
                                                                    ▼
                                                infrastructure/persistence/XxxJpaRepository
```

### 4-1. 유스케이스 분리 기준 — 애그리거트(엔티티) 단위로 묶는다

인바운드 포트(`~UseCase`)는 **행위(화면)마다 1-메서드 인터페이스로 잘게 쪼개지 않는다.**
묶음의 기준은 **도메인 애그리거트(엔티티)**다. 한 애그리거트의 조회/명령 연산을 그 엔티티 이름의
한 포트(`Xxx~UseCase`)에 모은다. 네이밍도 화면·행위가 아니라 엔티티를 따른다.

- **묶는다(크게):** 같은 애그리거트를 다루는 연산은 한 `~UseCase`에 모은다. 구현 세부(집계 쿼리 vs
  페이징 쿼리)가 달라도, **같은 엔티티의 조회면 한 포트**로 본다. 구현체(`~ApplicationService`)도
  하나로 두고 내부에서 필요한 리포지토리(집계용·페이징용)를 각각 주입해 처리한다.
- **쪼갠다(분리):** 애그리거트 자체 연산과 결이 다른 관심사(잡 실행·배치 상태 조회 등 '작업/잡'
  성격)는 별도 `~UseCase`로 둔다. 이들은 `Xxx` 엔티티 조회가 아니라 배치 잡을 다룬다.
  작업형 유스케이스는 `{Verb}{Object}UseCase`와 `{Verb}{Object}ApplicationService`로 어근과 단어
  순서를 정확히 맞춘다.

> 한 문장 규칙: **"같은 엔티티(애그리거트)의 연산이면 한 `~UseCase`(`Xxx~UseCase`)에, 잡/배치처럼
> 결이 다른 관심사면 별도 `~UseCase`에."**

예시 — 조회는 요약·목록 모두 `Xxx` 엔티티 조회이므로 한 포트에 모은다.

```
XxxUseCase  ← XxxApplicationService   getSummary()  (집계 리포지토리에 의존)
                                       getList()     (페이징 리포지토리에 의존)
```

→ 화면이 요약/목록으로 나뉘어도 `Xxx` 엔티티 조회이므로 `XxxUseCase` 하나에
`getSummary()`·`getList()`를 함께 둔다. 컨트롤러도 `XxxController` 하나가 받는다. 반면 배치
잡 실행·상태 조회(`RunXxxBatchUseCase`·`GetXxxJobStatusUseCase`)는 엔티티 조회가
아닌 잡 관심사라 분리 유지한다.

## 5. 배치 규칙

Spring Batch 를 쓰는 서비스는 그 구성을 기술 세부사항으로 보고 `infrastructure/batch`에 둔다.

- Job / Step / Reader / Processor / Writer / Tasklet / Listener 구성은 모두 `infrastructure/batch` 아래,
  **역할별 하위 패키지로 분리**한다. (`config` · `execution` · `reader` · `processor` · `writer` ·
  `tasklet` · `listener` · `runner` · `model`)
- **배치는 흐름 제어만 한다.** 실제 비즈니스 로직은 `application`의 유스케이스를 호출해 수행한다.
- Reader/Writer가 도메인 모델을 직접 다루더라도, 비즈니스 규칙은 도메인·유스케이스에 위임한다.
- 잡 시작·상태 조회·재시작처럼 `JobOperator`·`JobRepository`를 직접 다루는 어댑터와 잡 파라미터
  팩토리는 `execution`에 둔다.
- 운영 스케줄은 Kubernetes `CronJob`이 소유한다. 애플리케이션의 `XxxCronJobRunner`는 스케줄
  트리거 시점에만 잡을 한 번 실행하고, Spring Batch 결과를 프로세스 종료 코드로 바꾸는 역할만
  `runner`에 둔다. 배치 주기·기준일 계산 같은 도메인 고유 로직은 이 문서의 범위가 아니다.
- 배치 단계 사이에서만 쓰는 내부 DTO(예: `XxxBatchTarget`)는 `model`에 둔다. 도메인 모델과 섞지 않는다.

```
infrastructure/batch/config/XxxJobConfig
infrastructure/batch/config/XxxStepConfig
infrastructure/batch/reader/...
infrastructure/batch/processor/...   ──▶ application/usecase 호출
infrastructure/batch/writer/...
infrastructure/batch/tasklet/...
infrastructure/batch/listener/...
infrastructure/batch/execution/...   ──▶ JobOperator / JobRepository 시작·조회·재시작
infrastructure/batch/runner/...      ──▶ CronJob one-shot 실행·종료 코드 변환
infrastructure/batch/model/...       ← 배치 내부 전용 DTO
```

## 6. 계층 네이밍 규칙

계층·역할별 클래스 접미사를 통일한다. (일반 Java 네이밍 케이스 규칙은 `code-style.md` 참고)

`XxxBatch`는 presentation/application에서 사용하는 업무 실행 단위다. `XxxJob`은 Spring Batch의
`Job`·`JobInstance`·`JobExecution`을 다루는 기술 경계에서만 사용한다(배치를 쓰는 서비스에 한한다).

```text
RunXxxBatchRequest
  -> RunXxxBatchCommand
  -> RunXxxBatchUseCase
  -> RunXxxBatchApplicationService
  -> XxxJobLauncher
  -> XxxJobLauncherAdapter
  -> XxxJobResult
```

| 계층/역할 | 접미사 | 예시 |
| --- | --- | --- |
| 컨트롤러 | `~Controller` | `XxxController` |
| 요청 DTO | `~Request` | `CreateXxxRequest` |
| 응답 DTO | `~Response` | `XxxResponse` |
| 인바운드 포트 | `~UseCase` | `RunXxxBatchUseCase` |
| 유스케이스 구현 | `~ApplicationService` | `RunXxxBatchApplicationService` |
| 입력 Command | `~Command` | `CreateXxxCommand` |
| 출력 Result | `~Result` | `XxxResult` |
| 조회 조건(읽기) | `~Query` | `XxxListQuery` |
| 영속성 아웃바운드 포트 | `~Repository` | `XxxRepository` |
| 비영속 아웃바운드 포트 | 능력 명사 | `YyyQuery` |
| 비영속 어댑터 | 같은 능력 어근 + 구현 역할 | `YyyQueryClient` |
| 영속성 어댑터 | `~RepositoryAdapter` | `XxxRepositoryAdapter` |
| Spring Data | `~JpaRepository` | `XxxJpaRepository` |
| 배치 잡 설정 | `~JobConfig` | `XxxJobConfig` |
| 설정 | `~Config` | `SpringBatchInfrastructureConfig` |
| Job 실행 리스너 | `~JobExecutionListener` | `XxxBatchStateJobExecutionListener` |
| 도메인 예외 | `~Exception` | `XxxAlreadyCompletedException` |

## 7. DTO 변환 규칙

계층 경계를 넘을 때마다 DTO를 변환해, 안쪽 모델이 바깥으로 새지 않게 한다.

**명령(상태 변경) 흐름** — 계층마다 DTO를 변환한다.

```
Request ──▶ Command ──▶ (domain) ──▶ Result ──▶ Response
 표현         애플리케이션      도메인        애플리케이션    표현
```

다만 **상태 변경이 단순한 명령**(입력이 식별자뿐이라 `~Command`가 불필요하고, 산출도 변경된 단일
엔티티 상태뿐)은 중간 `~Result`를 생략하고 조회 흐름처럼 application 서비스가 `~Response`를 직접
만들어 반환할 수 있다. 컨트롤러는 변환 없이 받아 내려준다.
(예: 상태 변경 PATCH — `xxxId`만 받아 상태만 전이시키고 변경된 상태를 반환)

```
PathVariable(식별자) ──▶ (domain 상태 전이) ──▶ Response
 표현                       도메인                  표현(application 서비스가 생성)
```

**조회(읽기) 흐름** — 중복 객체를 줄이려 중간 `~Result`를 생략하고, application 서비스가
`~Response`를 직접 만들어 반환한다. 컨트롤러는 변환 없이 그대로 받아 내려준다.

```
Request(파라미터) ──▶ Query ──▶ (domain 조회) ──▶ Response
 표현                  애플리케이션    도메인            표현(application 서비스가 생성)
```

- 도메인 모델(`@Entity`)을 컨트롤러 응답으로 직접 반환하지 않는다. 반드시 `~Response`로 변환한다.
- 변환 코드는 각 DTO의 정적 팩토리 메서드(`from`, `of`)에 둔다.
  (예: `XxxResponse.from(xxxResult)`, `XxxListResponse.from(xxxs)`, `request.toCommand()`)
- **조회 응답, 그리고 위 단순 상태 변경 명령 응답은 `~Result`를 따로 두지 않고 서비스가 `~Response`를
  반환할 수 있다.** 이때 application 이 presentation `~Response`에 의존하는 것을 허용한다(§1 예외). `@Schema` 등 문서화 애너테이션은 여전히
  presentation 의 `~Response` 에만 둔다(application 코드에 Swagger 애너테이션을 넣지 않는다).
- 변환 로직이 복잡해지면 표현/애플리케이션 계층에 전용 매퍼를 둘 수 있다.
