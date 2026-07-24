# 시스템 아키텍처 개요

3JMT 프롬프트 마켓 백엔드 모노레포의 MSA 전체 구조. **2026-07-23 기준 실제 코드·설정에서 도출**했으며, 각 사실의 근거 파일을 병기한다. Spring Cloud 컴포넌트 상세 동작은 `spring-cloud.md`, 서비스 간 Kafka 이벤트 상세는 `event-flow.md`, 목표 Kubernetes 배포 구성은 [`kubernetes.md`](./kubernetes.md) 참조.

## 서비스 목록

| 모듈 | HTTP 포트 | gRPC 포트 | 역할 |
|---|---|---|---|
| `discovery` | 8761 | - | Eureka 서비스 레지스트리 |
| `config` | 8888 | - | Config Server (native, `config/src/main/resources/configs/` 제공) |
| `apigateway` | 8000 | - | 진입점. JWT 검증, 라우팅, `X-User-Id`/`X-User-Role` 주입 (WebFlux 기반) |
| `user-service` | 8081 | 9081 (서버) | 회원·인증·판매자·찜, 셀러 정산 읽기 모델과 AI용 정산 Query 제공 |
| `product-service` | 8082 | 9082 (서버) | 상품·카테고리·리뷰 |
| `order-service` | 8083 | 9083 (서버) | 주문·장바구니·Outbox Relay, 결제·정산용 조회 제공 |
| `payment-service` | 8084 | 9084 (서버), order 9083 (클라이언트) | 결제(Toss Payments 연동), 승인 시 Order 직접 조회 |
| `settlement-service` | 8085 | - | 주간 정산 CronJob, 정산·Detail 생성과 `SETTLEMENT_CREATED` V2 발행 |
| `admin-service` | 8086 | - | 어드민 조회·관리 API |
| `ai-service` | 8087 | user 9081 **클라이언트** | 셀러 정산 Tool Calling, Redis 대화 상태와 SSE 응답 |
| `common-module` | - | - | 공용 라이브러리 (`BusinessException`, `ErrorCode`, 공통 응답 래퍼). 루트 `settings.gradle`에 `include 'common-module'`로 서브프로젝트 포함 |

- 포트 근거: 각 모듈 `src/main/resources/application.yml`(또는 `.yaml`)의 `server.port`, `grpc.server.port`.
- 인프라: PostgreSQL, Kafka, Redis. Kubernetes에서 AI 대화 상태는 기존 Redis의 logical DB 1을 사용한다.
- 배포 구성에서 외부 진입은 host 80 → apigateway 8000이며, 서비스 포트는 loopback으로만 노출된다.

## 서비스 간 통신 흐름

```mermaid
flowchart LR
    Client([클라이언트]) -->|HTTP + JWT| GW[API Gateway :8000]
    GW -->|lb:// 라우팅| US[User :8081]
    GW --> PS[Product :8082]
    GW --> OS[Order :8083]
    GW --> PAY[Payment :8084]
    GW --> SS[Settlement :8085]
    GW --> AI[AI :8087]

    PS -.->|gRPC :9081 판매자| US
    OS -.->|gRPC :9082 상품| PS
    OS -.->|gRPC :9081 판매자| US
    OS -->|Feign /internal/products| PS
    SS -.->|gRPC :9083 정산 원천| OS
    AI -.->|gRPC :9081 셀러 정산 Query| US
    AI <--> R[(Redis DB 1)]
    AI -->|HTTPS| OAI[OpenAI]

    PAY -->|HTTPS| Toss[Toss Payments]
    PAY & OS & PS & SS -->|Kafka :9092| K[(Kafka)]
    K --> OS & PS & SS & US
```

### 1) 외부 → Gateway HTTP 라우팅

`apigateway/src/main/resources/application.yaml`의 라우트 정의 전체:

| 경로 패턴 | 대상 |
|---|---|
| `/api/v2/admin/settlements/batch/**` | `lb://SETTLEMENT-SERVICE` |
| `/api/v2/admin/settlements/**` | `lb://ADMIN-SERVICE` |
| `/api/v1/orders(/**)`, `/api/v1/cart(/**)`, `/api/v1/admin/orders(/**)`, `/api/v1/internal/orders/**` | `lb://ORDER-SERVICE` |
| `/api/v1/products(/**)`, `/api/v1/sellers/me/products(/**)`, `/api/v1/admin/products(/**)` | `lb://PRODUCT-SERVICE` |
| `/api/v1/payments/**` | `lb://PAYMENT-SERVICE` |
| `/api/v2/auth/**`, `/api/v2/users/**`, `/api/v2/seller(s)/**`, `/api/v2/wishlists/**`, `/api/v2/admin/**` | `lb://USER-SERVICE` |
| `/api/v2/ai/**` | `lb://AI-SERVICE` (`/api/v2/ai/settlement/**`는 Gateway에서 `SELLER` 정책 적용) |
| `/{service}/v3/api-docs` | 각 서비스 Swagger 문서 프록시 (RewritePath) |

`lb://`는 Eureka에 등록된 인스턴스를 조회해 로드밸런싱한다.

Wishlist 화면은 Client가 User `GET /wishlists`, Product `POST /products/wishlists`,
User `POST /sellers/wishlists`를 순차 호출해 조합한다. User 서비스는 Product gRPC를 호출하지 않는다.

### 2) 내부 동기 통신 (gRPC)

| 호출자 → 대상 | 포트 | 용도 | 근거 |
|---|---|---|---|
| product → user | 9081 | 판매자 정보 조회 | `product-service` `application.yml` `grpc.client.user-service` |
| order → product | 9082 | 상품 정보 조회 | `order-service/.../infra/grpc/client/product/ProductGrpcClientConfig.java` |
| order → user | 9081 | 판매자 정보 조회 | `order-service/.../infra/grpc/client/seller/SellerGrpcClientConfig.java` |
| settlement → order | 9083 | 주간 정산 원천 조회 | `settlement-service/.../infrastructure/client/order/config/OrderGrpcClientConfig.java` |
| payment → order | 9083 | 결제 승인마다 주문 금액·구매자 조회 | `payment-service/.../infrastructure/external/grpc/OrderGrpcClientConfig.java` |
| ai → user | 9081 | 셀러 월·주 정산 요약, 기간 비교, 주차별 분석, 지급 상태 조회 | `grpc/user/seller_settlement_query.proto` |

### 3) 내부 동기 통신 (HTTP)

- order → product: FeignClient, `path = /internal/products` (상품 스냅샷 조회). `order-service/.../infra/rest/client/ProductFeignClient.java`

### 4) 비동기 통신 (Kafka)

주요 토픽은 `payment-events`, `order-events`, `product-events`, `settlement-events`다. Settlement CronJob은
정산 한 건과 Detail 전체를 `SETTLEMENT_CREATED` V2로 발행하고, User가 이를 셀러용 읽기 모델로 저장한다.
발행/소비 매트릭스·시나리오 시퀀스는 **`event-flow.md`** 참조.

### 5) 외부 연동

- payment → Toss Payments (`https://api.tosspayments.com/v1`, RestClient). `payment-service/.../infrastructure/external/toss/TossPaymentGateway.java`
- ai → OpenAI API. 모델 호출에는 User gRPC가 반환한 셀러 본인 집계 결과만 전달한다.

## 기동 순서

루트 `docker-compose.yml`의 `depends_on` 체인 기준:

```
postgres(5432) + kafka(9092)
  → discovery(8761)            # healthcheck 통과 대기
  → config(8888)               # discovery 의존
  → user/product/order/payment/settlement(8081~8085)
                               # postgres·kafka(healthy) + discovery·config 의존
  → apigateway(8000)           # 모든 서비스 이후
```

- 각 서비스의 Config Server 연결은 `optional:configserver:` 이므로 Config Server 없이도 기동은 된다(중앙 설정만 비활성).
- Eureka가 없으면 `lb://` 라우팅이 동작하지 않으므로 gateway 경유 호출이 불가하다.
- 참고: `spring-cloud.md`는 Config → Eureka 순서로 설명하지만, 실제 compose는 discovery → config 순서다(각 서비스가 optional import라 기동에는 영향 없음).

## 인증 / 인가 흐름

1. **JWT 발급**: user-service가 로그인 시 RS256 개인키로 발급 (`user-service` `application.yml`의 `jwt.private-key`, access 1시간 / refresh 30일).
2. **JWT 검증**: apigateway가 OAuth2 Resource Server(`ReactiveJwtDecoder`)로 공개키 검증. `apigateway/.../config/SecurityConfig.java`, `JwtConfig.java`
3. **인증 제외 경로** (`WhitelistPathResolver`, `gateway.api-versions` 설정 기반 동적 생성): user-service는 `[v1, v2]` 병행 활성이라 `/api/v1/auth/signup`·`/api/v2/auth/signup`, `/api/v1/auth/login`·`/api/v2/auth/login`, `/api/v1/auth/oauth/**`·`/api/v2/auth/oauth/**`, `/api/v1/auth/token/refresh`·`/api/v2/auth/token/refresh`가 모두 화이트리스트에 오르지만, 실제로 서빙되는 건 auth 컨트롤러가 매핑된 v2뿐이다(v1 auth 경로는 화이트리스트 통과 후 다운스트림에서 404). `/actuator/**`, Swagger 경로도 제외 대상. 추가로 `GET /api/v1/products(/**)`는 비로그인 허용.
4. **헤더 주입** (`apigateway/.../filter/UserHeaderFilter.java`):
   - JWT `sub` → `X-User-Id`, `roles` claim → `X-User-Role`(콤마 조인, `BUYER`/`SELLER`/`ADMIN`)
   - `status` claim이 `ACTIVE`가 아니면 **403 즉시 반환**
   - 다운스트림 전달 전 `Authorization` 헤더는 제거
5. **다운스트림 소비**: 각 서비스 Controller가 `@RequestHeader("X-User-Id")` 등으로 수신. 역할 검증 방식은 서비스별 규칙을 따른다. AI 정산 API는 Gateway의 명시적 `SELLER_OR_ADMIN` 정책으로 두 role을 허용한다. 현재 `ai-service`는 `X-User-Id`만 사용하고 role을 prompt나 gRPC 계약으로 전달하지 않아 두 role에 동일한 본인 범위 답변 정책을 적용한다. 다른 `SELLER` 정책 경로는 기존처럼 ADMIN을 허용하지 않는다.
6. 헤더 이름(`X-User-Id`, `X-User-Role`)은 **서비스 간 계약이므로 임의 변경 금지** (apigateway CLAUDE.md).
