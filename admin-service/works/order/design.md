# order-service 어드민 API → admin-service 이관 설계

## 배경

order-service의 `/api/v1/admin/orders/**` 3개 엔드포인트(어드민 전체 주문 목록, 이번 달 실거래액, 최근 7일 거래량)를 admin-service로 이관한다. settlement-service → admin-service 이관 시 확립된 패턴(admin-service가 다른 서비스 스키마를 own read-only JPA entity로 직접 읽고, 네트워크 호출은 두지 않음)을 그대로 따른다.

## 현재 상태 (order-service)

- `AdminOrderController` (`/api/v1/admin/orders`, `/month`, `/weekend`) → `AdminOrderUseCase` → `AdminOrderService` → `AdminOrderQueryService` → `AdminOrderQueryRepository`(QueryDSL, `QOrder`/`QOrderProduct` 직접 조회, join/집계).
- `AdminOrderService`는 seller 닉네임 enrichment를 위해 `SellerClient`(인터페이스) 호출 — 구현체는 `SellerGrpcClientAdapter`(gRPC, user-service의 `SellerQueryServiceGrpc` 호출, 기본) + `SellerRestFallbackClient`(Resilience4j 서킷브레이커 fallback).
- `order-service/AGENTS.md:131`: 어드민 경로 role/status 검증은 게이트웨이 책임, 컨트롤러는 role 헤더를 읽지 않음.
- `SellerClient`/`SellerGrpcClientAdapter`/`SellerRestFallbackClient`/`SellerGrpcClientConfig`는 `AdminOrderService`에서만 쓰인다(다른 곳 참조 없음 확인). `grpc/user` proto srcDir도 이 seller 클라이언트 외에는 order-service 어디서도 안 쓴다.
- resilience4j 의존성 자체는 product gRPC client(`ProductGrpcResilienceConfig` 등)가 별도로 쓰므로 `build.gradle`에서 제거하지 않는다.

## 현재 상태 (admin-service)

- 기존 도메인은 settlement 하나뿐: `com.prompthub.admin.settlement.{presentation, application, domain, infrastructure}`. `SettlementController` 주석에 "settlement-service 에서 이관"이라고 명시 — 이번 이관의 직접 전례.
- `config/admin-service.yml`의 datasource가 `currentSchema=user_service,product_service,order_service,payment_service,settlement_service`를 이미 전부 whitelist — order_service, user_service 스키마 접근에 별도 설정 변경 불필요. `ddl-auto: none` (스키마 생성/변경 금지).
- 인터서비스 네트워크 클라이언트(Feign/gRPC/WebClient) 전무 — 전부 own JPA entity로 다른 서비스 테이블을 직접 읽는다(`Settlement.java` → `seller_settlement` 테이블 매핑이 예시).
- QueryDSL 의존성 없음(order-service만 보유). Security 설정 없음(게이트웨이가 ADMIN role 강제, `RoutePolicyResolver` 기본값).

## 게이트웨이 라우팅

`apigateway/.../route/VersionedServiceRoute.java`의 `VersionedServiceRoute.ALL`이 서비스별 경로 접미사를 코드로 고정 관리한다. 현재 `/admin/orders`, `/admin/orders/**`는 order-service 항목(`order=2`)에 있고, admin-service 항목(`order=1`, 현재 `/admin/settlements/**`만 보유)이 더 먼저 매칭된다.

## 결정 사항 (Q&A로 확정)

1. **이관 방식**: settlement 전례 그대로 — admin-service가 order_service/user_service 스키마를 own read-only entity로 직접 읽는다. REST/gRPC 프록시 두지 않음.
2. **Seller 닉네임 조회**: admin-service가 seller 닉네임 테이블(user-service `SellerQueryServiceGrpc`가 읽는 것과 같은 테이블)을 own read-only entity로 직접 읽는다. gRPC/REST 클라이언트 신규 도입 안 함.
3. **쿼리 구현**: QueryDSL 그대로 이관. `admin-service/build.gradle`에 order-service와 동일한 QueryDSL 의존성 4줄 추가.
4. **컷오버 방식**: Hard cutover, 한 PR. order-service 코드 삭제 + admin-service 신규 코드 + 게이트웨이 라우트 변경을 동시에 적용. 전환기간(듀얼 라우팅) 두지 않음.
5. **클래스명 컨벤션**: "Admin" 접두사 제거(`OrderController`, `OrderService`, `OrderQueryService`, `OrderSearchCondition` 등) — settlement 컨벤션과 통일.
6. **API 경로 버전**: 새 `OrderController`는 order-service처럼 `/api/v1/admin/orders` 하드코딩이 아니라, settlement와 동일하게 `${api.init}` 프로퍼티(admin-service 기준 `/api/v2`)를 사용한다. 즉 외부 경로가 `/api/v1/admin/orders` → `/api/v2/admin/orders`로 바뀐다.
   - user-service 등 다른 도메인은 이미 이슈 #305로 전체 공개 API를 `/api/v1` → `/api/v2`로 전환 완료했다(`docs/adr/config-management.md` §10) — v2로 가는 것이 프로젝트 전반의 현재 방향과 일치한다.
   - **파급 효과**: 게이트웨이는 경로 그대로 프록시(rewrite 없음)하므로, 컷오버 이후 `/api/v1/admin/orders/**`로 오는 요청은 admin-service에 라우팅되어도 매핑이 없어 404가 된다. 기존에 v1로 이 API를 호출하던 프론트/어드민 클라이언트는 v2로 호출부를 함께 바꿔야 한다 — 이 변경은 이번 이관과 별도로 프론트 쪽 조율이 필요하다.

## 아키텍처

```
[Gateway] --/api/v1/admin/orders/**--> [admin-service]
                                          OrderController
                                            → OrderUseCase
                                              → OrderService ─┬→ OrderQueryService → OrderQueryRepository (QueryDSL)
                                                               │      reads: order_service.orders, order_service.order_products (read-only entity)
                                                               └→ SellerNicknameRepository (Spring Data JPA findByIdIn)
                                                                      reads: user_service.<seller nickname table> (read-only entity)
```

order-service는 이 3개 엔드포인트와 관련 코드를 전부 잃는다. order-service의 일반 주문 API(`/orders/**`, `/cart/**`)와 `Order`/`OrderProduct` 엔티티(도메인 소유)는 그대로 남는다 — admin-service는 같은 테이블을 읽기 전용으로 별도 매핑할 뿐, order-service 엔티티를 공유하지 않는다.

## 컴포넌트 (admin-service, `com.prompthub.admin.order` 패키지)

기존 `com.prompthub.admin.settlement` 계층 구조를 그대로 따른다.

| 계층 | 클래스 | 비고 |
|---|---|---|
| presentation.controller | `OrderController` | `GET /api/v1/admin/orders`, `GET /api/v1/admin/orders/month`, `GET /api/v1/admin/orders/weekend`. 시그니처/응답 타입은 원본과 동일 |
| presentation.dto.request | `OrderSearchCondition` | 원본 `AdminOrderSearchCondition`에서 접두사만 제거 |
| presentation.dto.response | `OrderListResponse`, `MonthlyTradeAmountResponse`, `WeeklyTransactionResponse`, `DailyTransactionResponse`, `TransactionPeriodResponse` | 동일 |
| application.usecase | `OrderUseCase` | 인터페이스, 원본과 동일 3개 메서드 |
| application.service | `OrderService` | seller 닉네임 조회만 `SellerClient.getSellerNicknames` 호출에서 `SellerNicknameRepository.findByIdIn` 호출로 교체. 나머지 로직(월간/주간 집계, enrichment) 동일 |
| application.service | `OrderQueryService` | `@Transactional(readOnly = true)` 위임 wrapper, 원본과 동일 |
| application.dto | `OrderListProjection`, `DailyTransactionProjection` | 동일 |
| domain.model | `Order`, `OrderProduct` (read-only 신규 엔티티) | order-service `Order`/`OrderProduct`와 같은 테이블/컬럼 매핑. `QOrder`/`QOrderProduct` 생성 대상 |
| domain.model | `SellerNickname` (read-only 신규 엔티티, 최소 컬럼: id + nickname) | user-service `SellerQueryServiceGrpc`가 조회하는 테이블과 동일 테이블 매핑 |
| domain.repository | `OrderQueryRepository` (포트) | 원본 `AdminOrderQueryRepository`와 동일 시그니처 |
| infrastructure.persistence | `OrderQueryRepositoryImpl` | `AdminOrderQueryRepositoryImpl`의 QueryDSL 로직(페이징+상태 필터, 월간 순거래액, 일별 집계) 1:1 이식 |
| infrastructure.persistence | `SellerNicknameRepository` | Spring Data JPA, `findByIdIn(Collection<UUID>)` 하나만 필요 |

## 에러 처리

- gRPC/REST 이원화 및 Resilience4j 서킷브레이커 fallback 로직 자체가 없어진다 — DB 조회 하나로 대체되어 실패 시나리오가 단순해진다.
- 닉네임을 찾지 못한 seller는 기존과 동일하게 `"알 수 없음"` 기본값 유지(원인만 "gRPC 실패"에서 "테이블에 row 없음"으로 바뀔 뿐, 사용자 관점 동작 변화 없음).
- 신규 실패 모드 없음(네트워크 호출+fallback을 단일 DB 호출로 대체 — 엄격히 더 단순해짐).

## 빌드/설정 변경

- `admin-service/build.gradle`: QueryDSL 의존성 추가
  ```gradle
  implementation 'com.querydsl:querydsl-jpa:5.1.0:jakarta'
  annotationProcessor 'com.querydsl:querydsl-apt:5.1.0:jakarta'
  annotationProcessor 'jakarta.annotation:jakarta.annotation-api'
  annotationProcessor 'jakarta.persistence:jakarta.persistence-api'
  ```
- `apigateway/.../route/VersionedServiceRoute.java`: `"/admin/orders", "/admin/orders/**"`를 order-service 항목의 `pathSuffixes`에서 제거하고 admin-service 항목의 `pathSuffixes`에 추가. `order` 값은 admin-service=1 그대로 유지(order-service 잔여 경로와 충돌 없음).
- `config/admin-service.yml`: 변경 없음(order_service, user_service 스키마 이미 whitelist).
- order-service에서 삭제:
  - `presentation/AdminOrderController.java`
  - `application/usecase/AdminOrderUseCase.java`
  - `application/service/admin/{AdminOrderService, AdminOrderQueryService}.java`
  - `application/dto/{AdminOrderListProjection, AdminDailyTransactionProjection}.java`
  - `presentation/dto/request/AdminOrderSearchCondition.java`
  - `presentation/dto/response/{AdminOrderListResponse, AdminMonthlyTradeAmountResponse, AdminWeeklyTransactionResponse, AdminDailyTransactionResponse, AdminTransactionPeriodResponse}.java`
  - `domain/repository/AdminOrderQueryRepository.java`, `infra/persistence/order/AdminOrderQueryRepositoryImpl.java`
  - `application/client/SellerClient.java`, `infra/grpc/client/seller/{SellerGrpcClientAdapter, SellerGrpcClientConfig}.java`, `infra/rest/client/SellerRestFallbackClient.java`
  - 대응 테스트 전부(`AdminOrderControllerTest`, `AdminOrderServiceTest`, `AdminOrderQueryRepositoryImplTest`)
- order-service `build.gradle`: `sourceSets.main.proto`에서 `srcDir "${rootProject.projectDir}/grpc/user"` 제거(더 이상 참조하는 코드 없음).

## 테스트

- 원본 `AdminOrderControllerTest`/`AdminOrderServiceTest`/`AdminOrderQueryRepositoryImplTest`를 admin-service로 이식(클래스명 `Admin` 접두사 제거). `AdminOrderServiceTest`의 mock 대상만 `SellerClient` → `SellerNicknameRepository`로 교체.
- `OrderQueryRepositoryImpl` 테스트는 order-service가 쓰던 것과 같은 방식(Testcontainers 또는 H2)으로 order/orderProduct/seller-nickname 픽스처 테이블을 구성해 검증한다.
- 원본 테스트는 삭제.

## 범위 밖

- order-service의 일반(비어드민) 주문 API, `Order`/`OrderProduct` 엔티티, 게이트웨이 나머지 라우팅 룰 — 변경 없음.
- admin-service의 기존 settlement 기능 — 변경 없음.
- 전환기간/듀얼 라우팅 — 채택하지 않음(위 결정 사항 4 참조).
