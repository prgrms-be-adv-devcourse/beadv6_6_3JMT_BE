# 정산 연동 미사용 코드 정리 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** user-service에 남은 호출자 없는 gRPC 서버 묶음을 제거하고 settlement 이벤트 타입을 Kafka 규칙에 맞게 실제 사용하면서 Kafka seed와 Order gRPC 동작을 보존한다.

**Architecture:** user-service의 마지막 gRPC 서버 어댑터·로컬 proto·서버 전용 설정을 하나의 삭제 단위로 걷어내고 REST Seller 조회 유스케이스는 유지한다. settlement-service에서는 `SettlementEventType.code()`를 Outbox 생성에 연결해 문자열 계약은 보존하고 현재 구조를 설명하는 모듈 내부 문서를 갱신한다. admin settlement는 네트워크 연동이 없으므로 변경하지 않고 회귀 테스트만 수행한다.

**Tech Stack:** Java 21, Spring Boot 4.1, Gradle, Spring Kafka, Spring gRPC, Protocol Buffers, JUnit 5, Mockito, Checkstyle

## Global Constraints

- 직접 수정 범위는 `settlement-service/`, `user-service/`, `admin-service/src/main/java/com/prompthub/admin/settlement/`, `admin-service/src/test/java/com/prompthub/admin/settlement/`로 제한한다.
- `config/`, `k8s/`, `docker-compose.yml`, product-service, order-service와 루트 `docs/`는 수정하지 않는다.
- Kafka listener 활성화 여부, 토픽, consumer group, 재시도와 DLT 정책을 변경하지 않는다.
- settlement Outbox 스키마·발행·재처리와 settlement → order gRPC 계약·채널·오류 처리를 변경하지 않는다.
- admin settlement의 JPA 직접 접근 방식과 소스는 변경하지 않는다.
- 이번 #500 설계·계획 문서는 PR 검증 결과를 반영하되, 그 밖의 과거 설계·계획과 user-service 날짜 기반 설계 문서는 소급 수정하지 않는다.
- 삭제 대상 밖에서 발견한 문제는 구현 범위를 넓히지 않고 별도 이슈 후보로 보고한다.
- 설계 기준은 `settlement-service/docs/superpowers/specs/2026-07-22-unused-settlement-integration-cleanup-design.md`다.

---

### Task 1: user-service의 고아 Product Seller gRPC 서버 묶음 제거

**Files:**
- Modify: `user-service/build.gradle`
- Modify: `user-service/src/main/resources/application-local.yml`
- Modify: `user-service/docs/settlement-convention-alignment-backlog.md`
- Delete: `user-service/src/main/java/com/prompthub/user/seller/presentation/grpc/ProductSellerQueryGrpcService.java`
- Delete: `user-service/src/main/java/com/prompthub/user/global/config/GrpcSecurityConfig.java`
- Delete: `user-service/src/main/proto/product_seller_query.proto`
- Delete: `user-service/src/test/java/com/prompthub/user/seller/presentation/grpc/ProductSettlementSellerQueryGrpcServiceTest.java`
- Delete: `user-service/http/grpc.http`

**Interfaces:**
- Consumes: 현재 `ProductSellerQueryGrpcService`가 사용하는 `SellerQueryUseCase.findSeller(String)`과 `SellerQueryUseCase.findSellers(List<String>)`; 삭제 전 마지막 gRPC 사용처 확인에만 사용한다.
- Produces: user-service에서 gRPC 서버와 protobuf 코드 생성이 사라진 빌드. `SellerController`의 REST Seller 조회와 `SettlementEventConsumer.consume(String, Acknowledgment)`는 기존 시그니처와 동작을 유지한다.

- [ ] **Step 1: 삭제 전 마지막 사용처와 보존 경로의 기준선을 확인한다**

Run:

```bash
rg -n 'ProductSellerQueryGrpcService|com\.prompthub\.product\.grpc\.seller|product_seller_query|GrpcSecurityConfig|spring-boot-starter-grpc-server|grpc-stub|grpc-protobuf|apply plugin.*protobuf|^protobuf \{' \
  user-service/src/main user-service/src/test user-service/build.gradle user-service/http
```

Expected: 삭제 대상 Java·proto·test·Gradle·HTTP 파일에서만 결과가 출력된다. `SellerController`나 Kafka consumer에는 결과가 없어야 한다.

Run:

```bash
./gradlew :user-service:test \
  --tests '*ProductSettlementSellerQueryGrpcServiceTest' \
  --tests '*SellerQueryApplicationServiceTest' \
  --tests '*SellerControllerTest' \
  --tests '*SettlementEventConsumerTest'
```

Expected: `BUILD SUCCESSFUL`. 삭제할 gRPC 테스트와 보존할 REST·Kafka 테스트가 모두 현재 기준선에서 통과한다.

- [ ] **Step 2: user-service에서 protobuf와 gRPC 서버 전용 빌드 설정을 제거한다**

`user-service/build.gradle`에서 다음 블록과 줄을 삭제한다.

```diff
-apply plugin: 'com.google.protobuf'
-
 dependencies {
     // JWT (RSA 서명/검증 — NimbusJwtEncoder 포함)
     implementation 'org.springframework.boot:spring-boot-starter-oauth2-resource-server'
-
-    // gRPC 서버 (Seller 조회 제공)
-    implementation 'org.springframework.boot:spring-boot-starter-grpc-server'
-
-    // protobuf로 생성되는 gRPC 서버 코드 컴파일
-    implementation 'io.grpc:grpc-stub'
-    implementation 'io.grpc:grpc-protobuf'
```

다음 protobuf 코드 생성 블록도 전체 삭제한다.

```gradle
protobuf {
    protoc {
        artifact = "com.google.protobuf:protoc:${protobufVersion}"
    }
    plugins {
        grpc {
            artifact = "io.grpc:protoc-gen-grpc-java:${grpcVersion}"
        }
    }
    generateProtoTasks {
        all().each { task ->
            task.plugins {
                grpc {}
            }
        }
    }
}
```

나머지 JWT, Swagger, Kafka, Redis와 test 의존성 및 `tasks.named('test')`, `bootRun` 블록은 그대로 둔다.

- [ ] **Step 3: 고아 gRPC 구현·계약·테스트·보안·수동 호출 파일을 삭제한다**

다음 파일은 일부를 남기지 않고 전체 삭제한다.

```text
user-service/src/main/java/com/prompthub/user/seller/presentation/grpc/ProductSellerQueryGrpcService.java
user-service/src/main/java/com/prompthub/user/global/config/GrpcSecurityConfig.java
user-service/src/main/proto/product_seller_query.proto
user-service/src/test/java/com/prompthub/user/seller/presentation/grpc/ProductSettlementSellerQueryGrpcServiceTest.java
user-service/http/grpc.http
```

`SellerQueryUseCase`, `SellerQueryApplicationService`, `SellerController`와 그 테스트는 삭제하거나 수정하지 않는다.

- [ ] **Step 4: user-service 로컬 gRPC 서버 설정을 제거한다**

`user-service/src/main/resources/application-local.yml`에서 다음 블록만 삭제한다.

```yaml
grpc:
  server:
    port: 9081
```

Kafka의 `spring.kafka`와 `user.kafka.consumer.settlement`, `user.kafka.listener.settlement` 블록은 그대로 유지한다.

- [ ] **Step 5: user-service 현재 백로그에서 이미 사라진 gRPC 항목을 제거한다**

`user-service/docs/settlement-convention-alignment-backlog.md`의 A 섹션을 다음 상태로 만든다.

```markdown
## A. `seller` 패키지 정렬 (정산 외 셀러 등록·조회) — 우선 진행 대상

`seller` 패키지는 셀러 등록/조회를 담당하며, `sellersettlement`와 같은 이탈이 남아 있다.

| # | 현재 | settlement 기준 | 대상 |
| --- | --- | --- | --- |
| A-1 | `seller/presentation/controller/dto/{request,response}/` | `seller/presentation/dto/{request,response}/` | `SellerRegisterRequest`, `SellerRegisterResponse` |
| A-2 | 컨트롤러 반환 타입이 모듈 내에서 혼재(`ResponseEntity<ApiResult<T>>`) | `ApiResult<T>` 직접 반환 | `SellerController` |
```

기존 A-3 행과 바로 아래 gRPC 서버 어댑터 배치 설명은 삭제한다. B 섹션 이후는 변경하지 않는다.

Run:

```bash
rg -n 'SettlementSellerQueryGrpcService|seller/presentation/grpc' \
  user-service/docs/settlement-convention-alignment-backlog.md
```

Expected: 출력 없음, 종료 코드 1.

- [ ] **Step 6: protobuf 생성물 없이 user-service가 컴파일되는지 확인한다**

Run:

```bash
./gradlew :user-service:clean :user-service:compileJava :user-service:compileTestJava
```

Expected: `BUILD SUCCESSFUL`. `user-service/build/generated/source/proto`의 과거 산출물에 의존하지 않고 main과 test가 컴파일된다.

Run:

```bash
rg -n 'ProductSellerQueryGrpcService|com\.prompthub\.product\.grpc\.seller|product_seller_query|GrpcSecurityConfig|spring-boot-starter-grpc-server|grpc-stub|grpc-protobuf|apply plugin.*protobuf|^protobuf \{' \
  user-service/src/main user-service/src/test user-service/build.gradle user-service/http
```

Expected: 출력 없음, 종료 코드 1.

- [ ] **Step 7: 보존한 REST Seller 조회와 Kafka consumer 회귀 테스트를 실행한다**

Run:

```bash
./gradlew :user-service:test \
  --tests '*SellerQueryApplicationServiceTest' \
  --tests '*SellerControllerTest' \
  --tests '*SettlementEventConsumerTest'
```

Expected: `BUILD SUCCESSFUL`. REST Seller 조회와 `SETTLEMENT_CREATED` 소비 테스트가 모두 통과한다.

Run:

```bash
./gradlew :user-service:test :user-service:check
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 8: user-service 삭제 묶음을 커밋한다**

```bash
git add user-service/build.gradle
git add user-service/src/main/resources/application-local.yml
git add user-service/docs/settlement-convention-alignment-backlog.md
git add -u user-service/src/main/java/com/prompthub/user/seller/presentation/grpc
git add -u user-service/src/main/java/com/prompthub/user/global/config/GrpcSecurityConfig.java
git add -u user-service/src/main/proto/product_seller_query.proto
git add -u user-service/src/test/java/com/prompthub/user/seller/presentation/grpc
git add -u user-service/http/grpc.http
git commit -m "refactor: 유저 서비스 미사용 Seller gRPC 서버 제거"
```

Expected: user-service 파일만 포함한 커밋이 생성된다.

---

### Task 2: settlement-service 이벤트 타입 규칙 정렬과 현재 통신 문서 정리

**Files:**
- Modify: `settlement-service/src/main/java/com/prompthub/settlement/application/event/SettlementEventType.java`
- Modify: `settlement-service/src/main/java/com/prompthub/settlement/infrastructure/persistence/outbox/JsonOutboxEventAppender.java`
- Create: `settlement-service/src/test/java/com/prompthub/settlement/application/event/SettlementEventTypeTest.java`
- Modify: `settlement-service/docs/architecture/kafka-messaging-design.md`
- Modify: `settlement-service/docs/architecture/settlement-internal-comm-topology.md`
- Modify: `settlement-service/docs/architecture/integration-catalog.md`

**Interfaces:**
- Consumes: `SettlementEventType.SETTLEMENT_CREATED.code()`와 `JsonOutboxEventAppender.appendSettlementCreated(UUID, SettlementCreatedEvent)`의 `EventMessage` 계약.
- Produces: enum의 `code()`로 동일한 `SETTLEMENT_CREATED` Outbox payload를 발행하는 settlement-service. `OrderSettlementQueryClient`와 `SettlementEventPublisher.publish(String, UUID, String)`는 변경하지 않는다.

- [ ] **Step 1: 이벤트 타입의 미사용 상태와 보존할 Kafka·Order gRPC 경로의 기준선을 확인한다**

Run:

```bash
rg -n -w 'SettlementEventType' settlement-service/src/main settlement-service/src/test settlement-service/docs \
  -g '!**/superpowers/specs/**' -g '!**/superpowers/plans/**'
```

Expected: enum 선언 파일과 `kafka-messaging-design.md` 구현 트리만 출력된다. 이 상태를 Kafka 규칙 위반 기준선으로 기록한다.

Run:

```bash
./gradlew :settlement-service:test \
  --tests '*JsonOutboxEventAppenderTest' \
  --tests '*KafkaSettlementEventPublisherTest' \
  --tests '*OutboxRelayIntegrationTest' \
  --tests '*OrderSettlementQueryClientTest'
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 2: SettlementEventType 계약 테스트를 RED로 확인한다**

`SettlementEventTypeTest`에서 `SETTLEMENT_CREATED.code()`가 `SETTLEMENT_CREATED`를 반환하는 계약을
검증한다. enum이 삭제된 상태에서 targeted test를 실행해 타입 부재로 실패하는지 확인한다.

- [ ] **Step 3: SettlementEventType을 Outbox 생성에 연결한다**

`SettlementEventType`을 복원하고 `JsonOutboxEventAppender`의 문자열 상수를 제거한다. EventMessage와
Outbox 엔티티에는 하나의 지역 변수로 얻은 `SettlementEventType.SETTLEMENT_CREATED.code()`를 함께 사용한다.

- [ ] **Step 4: Kafka 구현 트리에 enum과 실제 역할을 유지한다**

`settlement-service/docs/architecture/kafka-messaging-design.md`의 `application/event` 트리를 다음처럼 갱신한다.

```text
  event/
    SettlementCreatedEvent.java            ← SETTLEMENT_CREATED 이벤트 상세 DTO     ✅
    SettlementEventType.java               ← 도메인 EventType enum(code()=name())   ✅
    PayoutCompletedEvent.java              ← settlement.payout.completed 이벤트 상세 DTO (추후)
```

Outbox와 producer 설명은 변경하지 않는다.

같은 구현 트리의 `KafkaConfig` 설명에서 이미 제거된 consumer DLT 표현을 걷어낸다.

```text
  config/
    KafkaConfig.java                       ← Outbox String producer 설정             ✅
```

- [ ] **Step 5: 정산 내부통신 토폴로지를 현재 코드에 맞춘다**

`settlement-service/docs/architecture/settlement-internal-comm-topology.md`의 user `sellersettlement` 행에서 gRPC 서버 설명을 `❌ 없음`으로 바꾼다.

```markdown
| **user `sellersettlement`** | ❌ 없음 | ✅ 구현(기본 OFF) — `settlement-events` | ❌ 없음 | ❌ 없음 |
```

3-1의 settlement gRPC 서버 설명은 다음으로 교체한다.

```markdown
**gRPC 서버** — 없음. settlement-service에는 gRPC server starter, 비즈니스 서비스와 서버 proto가 없다.
루트 인프라에 남은 settlement gRPC 포트 선언은 이번 모듈 내부 코드 정리 범위 밖이다.
```

바로 아래 미사용 채널 안내의 절 참조는 새 4절에 맞게 `§4 참고`로 바꾼다.

3-2의 gRPC 서버 설명은 다음으로 교체한다.

```markdown
**gRPC 서버** — 없음. Product용 `ProductSellerQueryGrpcService`와 정산용
`SettlementSellerQueryGrpcService`는 호출자가 사라져 제거됐다. 판매자 정보 화면 조합은
user-service가 소유한 REST Seller 조회 API를 사용한다.
```

4절은 실제 대기 중 통신이 아니라 범위 밖 설정 잔재를 설명하도록 다음 내용으로 교체한다.

```markdown
## 4. 범위 밖 설정 잔재

루트 Config Server 설정에는 settlement의 `grpc.client.user-service`와
`grpc.client.product-service` 채널 선언이 남아 있다. settlement-service에는 두 채널을 사용하는
클라이언트가 없으며, 이번 #500 작업은 모듈 내부 코드 정리만 수행하므로 루트 설정은 변경하지 않는다.

- 판매자 정보 화면 조합은 user-service REST API를 사용한다.
- 상품 통계는 #452 이후 프론트가 Product 공개 API를 직접 호출한다.
- settlement-service에 남는 동기 통신은 order 원천 조회용 gRPC뿐이다.
```

- [ ] **Step 6: 연동 카탈로그의 Seller gRPC 계약을 REST 화면 조합 경계로 바꾼다**

`settlement-service/docs/architecture/integration-catalog.md` 상단의 두 번째 분류를 다음처럼 바꾼다.

```markdown
- **화면 조합 경계** — 판매자 정보와 상품 통계는 정산 백엔드가 gRPC로 조합하지 않는다.
  프론트가 각 소유 서비스의 REST API를 호출한다. → §2
```

같은 상단 안내에서 “이관 후 §2를 sellersettlement가 수행한다”는 설명을 제거하고 다음으로 교체한다.

```markdown
> **현재 실제 통신 상태(어느 모듈이 무엇을 구현/비활성/대기 중인지)는
> `settlement-internal-comm-topology.md`의 현황 매트릭스를 본다.** §1은 정산 원천 gRPC 계약,
> §2는 프론트 화면 조합 경계, §3은 정산 이벤트 발행 계약을 설명한다.
```

§2의 Seller 설명과 §2-1을 다음 내용으로 교체한다.

```markdown
## 2. 참고 데이터와 화면 조합 경계

정산 금액과 대상을 결정하는 order 원천 라인은 settlement-service가 내부 gRPC로 조회한다(§1).
판매자 화면의 부가 정보는 정산 백엔드가 합치지 않고 프론트가 소유 서비스의 REST API를 별도로 호출한다.

- 판매자 단건·다건 정보는 user-service의 REST Seller 조회 API가 제공한다(§2-1).
- 등록 프롬프트 수와 누적 판매건수는 Product 공개 API가 제공한다(§2-2).
- 판매자 정산 계좌 정보는 아직 범위가 아니다. 지급 실행을 붙일 때 별도로 정한다.

### 2-1. User — 판매자 정보 조회 REST API

Product와 구매상품 등 화면별 판매자 정보 조합은 user-service가 소유한 REST API를 사용한다.
user-service의 Product용 `ProductSellerQueryGrpcService`와 정산용
`SettlementSellerQueryGrpcService`는 호출자가 없어 제거됐으며 settlement-service에는 Seller 조회
클라이언트가 없다.

- 상품 화면: `GET /api/v2/sellers/product`, `POST /api/v2/sellers/products`
- 구매상품 화면: `POST /api/v2/users/order-products`
- Wishlist 화면: `POST /api/v2/sellers/wishlists`
```

§6 구현 현황의 판매자 이름 행은 다음으로 바꾼다.

```markdown
| 판매자 이름(다건/단건) | **정산 백엔드 통신 없음.** user-service REST Seller 조회 API를 프론트가 화면별로 호출하며 Product·Settlement용 Seller gRPC 서버는 제거됨 |
```

order gRPC, `SETTLEMENT_CREATED`, 어드민 DB 직접 접근 행은 변경하지 않는다.

- [ ] **Step 7: enum 연결 후 Kafka Outbox와 Order gRPC 회귀를 검증한다**

Run:

```bash
rg -n -w 'SettlementEventType' settlement-service/src/main settlement-service/src/test settlement-service/docs \
  -g '!**/superpowers/specs/**' -g '!**/superpowers/plans/**'
```

Expected: enum 선언, `JsonOutboxEventAppender` 사용, 계약 테스트와 Kafka 구현 트리만 출력된다.

Run:

```bash
./gradlew :settlement-service:test \
  --tests '*JsonOutboxEventAppenderTest' \
  --tests '*KafkaSettlementEventPublisherTest' \
  --tests '*OutboxRelayIntegrationTest' \
  --tests '*OrderSettlementQueryClientTest'
```

Expected: `BUILD SUCCESSFUL`.

Run:

```bash
./gradlew :settlement-service:clean :settlement-service:test :settlement-service:check
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 8: settlement-service 이벤트 타입 정렬과 문서 현행화를 커밋한다**

```bash
git add settlement-service/src/main/java/com/prompthub/settlement/application/event/SettlementEventType.java
git add settlement-service/src/main/java/com/prompthub/settlement/infrastructure/persistence/outbox/JsonOutboxEventAppender.java
git add settlement-service/src/test/java/com/prompthub/settlement/application/event/SettlementEventTypeTest.java
git add settlement-service/docs/architecture/kafka-messaging-design.md
git add settlement-service/docs/architecture/settlement-internal-comm-topology.md
git add settlement-service/docs/architecture/integration-catalog.md
git commit -m "fix: 정산 이벤트 타입 규칙 적용"
```

Expected: settlement-service 이벤트 타입 코드·테스트와 현재 아키텍처 문서만 포함한 커밋이 생성된다.

---

### Task 3: admin settlement와 전체 범위 최종 검증

**Files:**
- Verify only: `admin-service/src/main/java/com/prompthub/admin/settlement/`
- Verify only: `admin-service/src/test/java/com/prompthub/admin/settlement/`
- Verify only: `user-service/`
- Verify only: `settlement-service/`

**Interfaces:**
- Consumes: Task 1의 gRPC 없는 user-service 빌드와 Task 2의 enum 기반 eventType을 사용하는 settlement-service 빌드.
- Produces: admin JPA 정산 회귀가 없고, Kafka seed와 Order gRPC가 보존되며, 수정 경로가 승인 범위를 벗어나지 않았다는 검증 결과.

- [ ] **Step 1: admin settlement의 JPA 직접 접근 회귀 테스트를 실행한다**

Run:

```bash
./gradlew :admin-service:test --tests 'com.prompthub.admin.settlement.*'
```

Expected: `BUILD SUCCESSFUL`. 애플리케이션 서비스, 컨트롤러, repository adapter와 상태 전이 테스트가 모두 통과한다.

- [ ] **Step 2: user-service 전체 검증을 clean부터 다시 실행한다**

Run:

```bash
./gradlew :user-service:clean :user-service:test :user-service:check
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 3: settlement-service 전체 검증을 clean부터 다시 실행한다**

Run:

```bash
./gradlew :settlement-service:clean :settlement-service:test :settlement-service:check
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 4: 제거 대상이 사라지고 보존 대상이 남았는지 정적 검색한다**

Run:

```bash
rg -n 'ProductSellerQueryGrpcService|com\.prompthub\.product\.grpc\.seller|product_seller_query|GrpcSecurityConfig' \
  user-service/src/main user-service/src/test user-service/build.gradle user-service/http
rg -n -w 'SettlementEventType' settlement-service/src/main settlement-service/src/test settlement-service/docs \
  -g '!**/superpowers/specs/**' -g '!**/superpowers/plans/**'
```

Expected: user-service 제거 대상 검색은 출력 없이 종료 코드 1이다. SettlementEventType 검색은 enum 선언,
`JsonOutboxEventAppender` 사용, 계약 테스트와 Kafka 구현 트리만 출력한다.

Run:

```bash
rg -n 'class SettlementEventConsumer|class KafkaSettlementEventPublisher|class OrderSettlementQueryClient' \
  user-service/src/main settlement-service/src/main
```

Expected: 세 클래스가 각각 한 번씩 출력된다.

- [ ] **Step 5: 승인 범위 밖 파일과 admin settlement 코드가 바뀌지 않았는지 확인한다**

Run:

```bash
git diff --name-only origin/develop...HEAD
git diff --exit-code origin/develop...HEAD -- \
  admin-service/src/main/java/com/prompthub/admin/settlement \
  admin-service/src/test/java/com/prompthub/admin/settlement
```

Expected: 첫 명령에는 `user-service/`와 `settlement-service/` 경로만 출력된다. 두 번째 명령은 출력 없이 종료 코드 0이다.

Run:

```bash
git diff --check origin/develop...HEAD
git status --short
```

Expected: 두 명령 모두 출력 없음. 별도 수정이나 최종 검증용 커밋은 만들지 않는다.

## 범위 밖 잔여 참조

구현 완료 후에도 다음 경로는 #500의 승인 범위 밖이므로 남을 수 있다. 이번 브랜치에서 수정하지 않고 완료 보고에 기록한다.

```text
config/
k8s/
docker-compose.yml
product-service/
order-service/
docs/                       # 저장소 루트 문서
```

특히 user gRPC 포트, settlement의 미사용 user/product 채널과 과거 서비스 시작 의존 설명이 남아 있더라도 Task 1~3의 수정 범위에 추가하지 않는다.
