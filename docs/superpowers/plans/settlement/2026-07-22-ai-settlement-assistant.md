# 셀러 AI 정산 어시스턴트 Implementation Plan

> 설계 원본: `settlement-service/docs/superpowers/specs/2026-07-22-ai-settlement-assistant-design.md`

**Goal:** 셀러가 자신의 월·주 정산을 질문하면 `ai-service`가 Spring AI Tool Calling으로 User의
정산 집계를 조회하고, 진행 상태와 최종 답변을 SSE로 제공한다.

**Architecture:** Settlement CronJob은 V2 Kafka 이벤트에 `SettlementDetail`을 포함한다. User는 이를
셀러 전용 read model로 저장하고 네 개의 읽기 전용 gRPC RPC를 제공한다. 독립 `ai-service`는 OpenAI
agent loop, Redis DB 1의 24시간 대화·run 상태, Redis Pub/Sub과 SSE만 소유한다.

**Tech Stack:** Java 21, Spring Boot 4.1, Spring AI 2.0, OpenAI, gRPC, Kafka, PostgreSQL/Flyway,
Redis, Spring MVC `SseEmitter`, Kubernetes

## 구현 원칙

- 현재 사용자는 셀러뿐이며 actor는 Gateway가 전달한 `X-User-Id`에서만 얻는다. role 헤더와 admin
  분기는 만들지 않는다.
- Tool은 기간만 입력받고 seller ID, 원본 Detail, 주문 ID를 모델에 전달하지 않는다.
- 금융 계산과 데이터 범위 판단은 User가 담당하고 모델은 집계 결과를 설명만 한다.
- RAG, Vector DB, Embedding, 주문 조회와 write Tool은 범위 밖이다.
- TDD는 전체 입력 조합을 포괄하지 않는다. 금융 계산, 판매자 경계, 버전 분기, run fencing,
  timeout과 최종 응답 안전 정책을 대표 사례로 검증한다.
- CI에서는 실제 OpenAI를 호출하지 않으며 배포 후 승인된 smoke 한 건만 실제 모델을 사용한다.
- 기존 작업공간의 사용자 변경은 건드리지 않고 격리 worktree에서 구현한다.

## 작업 분할

### Task 1. Settlement V2 이벤트와 금융 의미

**주요 파일**

- `settlement-service/src/main/java/com/prompthub/settlement/application/event/SettlementCreatedEvent.java`
- `settlement-service/src/main/java/com/prompthub/settlement/application/event/SettlementDetailEvent.java`
- `settlement-service/src/main/java/com/prompthub/settlement/domain/model/Settlement.java`
- `settlement-service/src/main/resources/db/migration/V3__reset_settlement_data_for_v2_event.sql`

- [x] `payloadVersion=2`와 Detail 전체를 한 이벤트에 포함한다.
- [x] SALE 건수·매출, REFUND 절댓값, signed 수수료·지급액 의미를 적용한다.
- [x] 정산·outbox·Spring Batch metadata를 초기화하는 V3를 추가한다.
- [x] 계산·직렬화·Flyway 초기화의 핵심 대표 테스트를 통과시킨다.

### Task 2. User V2 read model, Flyway와 DLT

**주요 파일**

- `user-service/src/main/java/com/prompthub/user/sellersettlement/application/event/SettlementCreatedEventV1.java`
- `user-service/src/main/java/com/prompthub/user/sellersettlement/application/event/SettlementCreatedEventV2.java`
- `user-service/src/main/java/com/prompthub/user/sellersettlement/domain/model/SellerSettlementDetail.java`
- `user-service/src/main/java/com/prompthub/user/sellersettlement/infrastructure/messaging/kafka/consumer/settlement/SettlementEventConsumer.java`
- `user-service/src/main/java/com/prompthub/user/sellersettlement/infrastructure/messaging/kafka/consumer/settlement/dlt/`
- `user-service/src/main/resources/db/migration/V3__reset_seller_settlement_for_analysis.sql`

- [x] V1 코드는 계약 이력으로 유지하고 정상 producer는 V2만 발행한다.
- [x] V2 부모와 Detail을 한 transaction으로 저장하고 원본 Detail UUID를 유지한다.
- [x] unknown version과 깨진 envelope는 재시도 뒤 DLT로 보낸다.
- [x] Slack webhook이 비어 있으면 DLT가 아니라 알림 전송만 비활성화한다.
- [x] 부모·Detail 저장, duplicate, rollback과 DLT의 핵심 대표 테스트를 통과시킨다.

### Task 3. User 소유 정산 분석 gRPC

**주요 파일**

- `grpc/user/seller_settlement_query.proto`
- `user-service/src/main/java/com/prompthub/user/sellersettlement/application/service/SellerSettlementAnalysisApplicationService.java`
- `user-service/src/main/java/com/prompthub/user/sellersettlement/infrastructure/persistence/SellerSettlementAnalysisQueryRepositoryAdapter.java`
- `user-service/src/main/java/com/prompthub/user/sellersettlement/infrastructure/grpc/`

- [x] Summary, Compare, Weekly Breakdown, Payout Status 네 RPC를 만든다.
- [x] 금액·비율은 protobuf `string`으로 전달하고 통화 필드는 두지 않는다.
- [x] internal token과 actor UUID metadata를 검증하고 모든 SQL을 actor 범위로 제한한다.
- [x] 완료된 주간 정산 경계와 요청 기간의 `dataThrough`를 분리한다.
- [x] 기간·증감률·경계 주차·인증·응답 ID 비노출의 대표 테스트를 통과시킨다.

### Task 4. AI 모듈과 Redis/SSE 기반 구조

**주요 파일**

- `ai-service/build.gradle`
- `ai-service/src/main/java/com/prompthub/ai/global/config/`
- `ai-service/src/main/java/com/prompthub/ai/settlement/domain/`
- `ai-service/src/main/java/com/prompthub/ai/settlement/infrastructure/redis/`
- `ai-service/src/main/java/com/prompthub/ai/settlement/presentation/SseEmitterRegistry.java`

- [x] JPA, Flyway, Kafka, RAG 없는 독립 `ai-service`를 등록한다.
- [x] Redis logical DB 1에 대화, active run과 24시간 TTL을 저장한다.
- [x] Lua CAS로 actor당 run 하나, terminal 불변성과 cancel fencing을 보장한다.
- [x] Pub/Sub subscriber, local emitter registry와 15초 heartbeat를 구현한다.
- [x] 상태 저장과 Pub/Sub의 핵심 통합 테스트만 둔다.

### Task 5. User gRPC Tool과 수동 Agent loop

**주요 파일**

- `ai-service/src/main/java/com/prompthub/ai/settlement/infrastructure/grpc/`
- `ai-service/src/main/java/com/prompthub/ai/settlement/infrastructure/openai/`
- `ai-service/src/main/java/com/prompthub/ai/settlement/application/service/HistoryTokenSelector.java`

- [x] User gRPC 호출에 actor metadata, internal token, 3초 deadline과 no retry를 적용한다.
- [x] 네 RPC를 seller ID를 인자로 받지 않는 네 Tool로 노출한다.
- [x] 최대 네 Tool round를 순차 실행하고 최근 완료 pair를 8,000 token 이내로 선택한다.
- [x] OpenAI transient 오류는 호출당 한 번만 재시도한다.
- [x] blocking Tool-selection 호출까지 남은 run deadline으로 강제 중단한다.
- [x] 최종 답변에서 질문형 후속 요청, UUID, raw Tool JSON과 내부 용어를 차단한다.
- [x] Tool 선택·재시도·timeout·최종 정책의 핵심 대표 테스트를 통과시킨다.

### Task 6. Chat API, 실행 오케스트레이션과 SSE

**예정 파일**

- `ai-service/src/main/java/com/prompthub/ai/settlement/application/service/SettlementChatApplicationService.java`
- `ai-service/src/main/java/com/prompthub/ai/settlement/application/service/RunFutureRegistry.java`
- `ai-service/src/main/java/com/prompthub/ai/settlement/presentation/`

- [x] `GET current`, `POST questions`, `GET events`, `DELETE conversation`을 구현한다.
- [x] feature flag가 false면 MVC 인자 변환과 Redis/OpenAI/gRPC 접근 전에 네 API를 차단한다.
- [x] 질문은 trim 후 1~2,000자로 제한하고 Pod당 동시 실행 4, queue 0을 적용한다.
- [x] terminal Redis commit 뒤에만 검증된 표시용 chunk와 terminal Pub/Sub을 발행하고 publish 실패가 terminal을 덮지 않게 한다.
- [x] cancel을 Redis fencing, cancelled publish, local Future interrupt 순으로 수행한다.
- [x] 첫 SSE 연결은 검증된 표시용 chunk를 받고 재연결은 상태·terminal만 복구한다.
- [x] 질문·답변·actor·payload를 metric tag나 로그에 남기지 않는다.
- [x] 위 상태 순서와 장애 경로의 대표 서비스·MVC 테스트를 통과시킨다.

### Task 7. Gateway, Config와 CI/CD 계약

**주요 파일**

- `apigateway/src/main/java/com/prompthub/apigateway/route/VersionedServiceRoute.java`
- `apigateway/src/main/resources/application.yml`
- `config/src/main/resources/configs/ai-service.yml`
- `.github/workflows/ci.yml`
- `.github/workflows/ci-main.yml`
- `.github/workflows/cd-selfhosted-kubernetes.yml`
- `scripts/smoke-ai-settlement.sh`

- [x] `/api/{version}/ai/**`를 AI로 라우팅하고 현재 Gateway 정책은 SELLER로 고정한다.
- [x] Luna low, 90초, 재시도 1회, 8,000 token, 동시 실행 4와 기능 flag 설정을 추가한다.
- [x] AI와 User가 공유하는 production gRPC target을 Config에 명시한다.
- [x] CI 변경 감지와 AI build job, CD release order와 smoke 문서를 추가한다.
- [x] 전체 Config/Gateway/워크플로 계약 검증을 다시 통과시킨다.

### Task 8. Kubernetes 패키지와 실제 image digest

**예정 파일**

- `k8s/base/services/ai/deployment.yaml`
- `k8s/base/services/ai/service.yaml`
- `k8s/base/services/ai/kustomization.yaml`
- `k8s/base/services/user/deployment.yaml`
- `k8s/base/services/kustomization.yaml`
- `k8s/templates/runtime-values.example.yaml`

- [x] 먼저 검증된 코드 commit의 AI image를 GHCR에 push한다.
- [x] registry에서 조회한 실제 64자리 SHA-256 digest만 base Deployment에 기록한다.
- [x] AI Deployment 1 replica, HTTP 18087/Service 8087, 기능 flag `true`를 설정한다.
- [x] init dependency는 Config, Discovery와 Redis만 두고 User gRPC를 hard dependency로 두지 않는다.
- [x] User와 AI에 같은 `AI_USER_GRPC_TOKEN` secret을 주입하고 User listener를 활성화한다.
- [x] Kustomize와 CD 정적 검증을 통과시킨다.

### Task 9. 최종 검증과 인계

- [x] `./gradlew :settlement-service:test`
- [x] `./gradlew :user-service:test`
- [x] `./gradlew :ai-service:test :ai-service:compileJava`
- [x] Config, Gateway, workflow와 Kustomize validator 실행
- [x] secret/role/header/raw payload 비노출 정적 검색
- [x] diff 자체 검토와 별도 코드 리뷰 반영
- [x] 구현 문서 상태와 체크박스를 실제 결과에 맞게 갱신
- [ ] 사용자가 원하는 방식으로 commit, push와 PR을 인계

## 운영 적용 순서

운영 적용과 backfill은 코드 구현과 별개다. retained Kafka record와 consumer offset을 먼저 읽기 전용으로
확인한 뒤 User V3, Settlement V3 성공 Job, 승인된 V2 backfill, User listener, User gRPC, AI, Gateway
순으로 진행한다. 과거 event 삭제나 offset 이동은 별도 승인 없이 실행하지 않는다. 상세 절차는
`ai-service/docs/settlement-assistant-rollout.md`를 따른다.
