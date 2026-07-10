# gRPC 계약 소유·공유 컨벤션

서비스 간 gRPC proto 계약을 **루트 `grpc/` 디렉토리에서 단일 관리**하는 규칙을 정의한다.
정산 도메인(정산 본체·셀러 정산·어드민 정산) 범위에서 먼저 적용한다.

> 관련 문서
> - 연동 계약 상세(proto 전문·필드표): `integration-catalog.md`
> - 현재 통신 상태 현황판: `settlement-internal-comm-topology.md`

## 1. 배경 — 왜 루트에서 공유하나

기존에는 같은 gRPC 계약을 **서버 모듈과 클라이언트 모듈이 각자 `.proto` 미러로** 들고 있었다.
(예: `CountBySeller` 가 product-service 원본 + user-service 미러로 양쪽 존재.) 한 계약을 두 곳에서
수정·관리하니 필드가 어긋날 위험이 있었다(드리프트).

이를 줄이기 위해 계약 `.proto` 를 **레포 루트 `grpc/` 한곳**에 두고, 필요한 모듈이 빌드 시 그 경로를
참조해 스텁을 생성한다. 계약의 단일 소스를 확보하는 게 목적이다.

> **Gradle 모듈로는 만들지 않는다.** `.proto` 소스만 공유하고(디렉토리 공유), 스텁은 각 모듈이 자기
> 빌드에서 생성한다. 계약 수가 적은 지금 단계에 별도 `:grpc` 모듈은 과하다고 판단했다. 중앙화할 계약이
> 여러 개로 늘고 모듈 간 공유가 잦아지면 공유 Gradle 모듈로 승격하는 것을 재검토한다.

## 2. 소유 규칙 — 응답하는 쪽(서버)이 계약을 소유한다

gRPC 는 `a`(클라이언트)가 `b`(서버)에게 요청하고 `b` 로부터 응답을 받는다. 이때 **그 계약은 응답하는
쪽 = 서버 `b` 가 소유**한다. 계약 파일은 `grpc/<b>/` 아래에 둔다.

```
a(클라이언트) ──요청──▶ b(서버)
a ◀──응답── b
                → 계약(.proto)은 grpc/<b>/ 에서 관리   (요청자 a 가 아니라 응답자 b)
```

- 소유 판단 기준은 "누가 서버(응답자)냐" 하나다. 요청하는 클라이언트가 여럿이어도 계약은 서버 하나가
  소유한다.
- 디렉토리 이름은 **서버 모듈명**을 따른다. (`grpc/user/`, `grpc/order/`, `grpc/product/` …)

### 2-1. 디렉토리·파일·서비스·메서드·메시지 이름

계약은 **디렉토리로 소유(서버 모듈)를, 파일·서비스로 도메인을** 나타낸다. "무엇을 조회하는지"는 메서드·메시지 목적어에서 드러낸다.

| 대상 | 규칙 | 예 |
| --- | --- | --- |
| 디렉토리 | `grpc/<소유모듈>/` | `grpc/user/`, `grpc/order/`, `grpc/product/` |
| 파일 | `<도메인>_query.proto` | `grpc/user/seller_query.proto`, `order_query.proto`, `product_query.proto` |
| 서비스 | `<도메인>QueryService` | `SellerQueryService`, `OrderQueryService`, `ProductQueryService` |
| 메서드(rpc) | `Get<목적어>` | `GetSellers`, `GetSettleableLines` |
| 요청 메시지 | `Get<목적어>Request` | `GetSellersRequest` |
| 응답 메시지 | `Get<목적어>Response` | `GetSellersResponse` |

- **디렉토리 = 누가 답하나(소유 모듈), 파일·서비스 = 무슨 도메인이냐, 메서드·메시지 = 무엇을 조회하나.**
  한 모듈이 여러 도메인을 답하면 도메인별로 파일을 나눈다(예: `grpc/user/seller_query.proto`). 단일 도메인
  모듈은 파일명이 모듈명과 같아질 수 있다(order → `order_query.proto`). 한 도메인 서비스가 여러 조회를
  답하면 그 `<도메인>QueryService` 안에 `Get~` 메서드를 여러 개 둔다.
- **여러 건(batch) 조회면 목적어를 복수로 쓴다.** (`GetSellers` / `GetSellersRequest` / `GetSellersResponse`)
- **요청/응답이 아닌 내부 항목 메시지는 예외 — 현행 유지.** 리스트 원소·payload(예: `SellerInfo`,
  `SettleableLine`)에는 이 규칙을 적용하지 않는다.
- **`package`·`java_package` 는 이 규칙 범위 밖이다.** gRPC wire 경로·import churn 이라 별도로 다룬다.

> **주의(wire):** 서비스명·메서드명·`package` 는 gRPC 호출 경로(`/package.Service/Method`)라, 바꾸면
> 서버·클라이언트가 **같이** 바뀌어야 통신된다. 반면 **파일명·메시지 타입명은 wire 가 아니라** 각 측이
> 따로 바꿔도 통신에 영향이 없다(메시지는 필드 번호로 직렬화되며 타입명은 전송되지 않는다).

## 3. 디렉토리 레이아웃

```
beadv6_6_3JMT_BE/
└── grpc/                      ← 루트 공유 gRPC 계약
    ├── user/                  ← user-service 가 서버인 계약
    │   └── seller_query.proto   ← SellerQueryService.GetSellers
    ├── order/                 ← order-service 가 서버인 계약(서버 미구현 — 정산 클라이언트가 유일본)
    │   └── order_query.proto    ← OrderQueryService.GetSettleableLines
    └── product/               ← product-service 가 서버인 계약(서버 원본은 product-service 에 잔존)
        └── product_query.proto  ← ProductQueryService.CountBySeller
```

기능이 늘면 서버 모듈명으로 하위 디렉토리를 추가한다(`grpc/order/`, `grpc/product/` …).

## 4. 모듈에서 참조하는 법

계약을 **제공(서버)하거나 소비(클라이언트)하는** 모듈은 자기 `build.gradle` 의 protobuf 소스에 해당
`grpc/<owner>/` 경로를 더한다. (protobuf 플러그인이 그 `.proto` 로 스텁을 자기 빌드에 생성한다.)

```gradle
sourceSets {
    main {
        proto {
            srcDir "${rootProject.projectDir}/grpc/user"
        }
    }
}
```

- **`java_package` 는 계약을 옮겨도 그대로 둔다.** 생성 클래스의 패키지가 유지되므로 서버·클라이언트
  코드의 import 가 바뀌지 않는다(코드 변경 0). 파일 위치만 이동한다.
- 계약을 `grpc/` 로 옮긴 뒤에는 **원본을 원래 모듈 `src/main/proto` 에서 삭제**한다. 남겨두면 같은
  클래스가 이중 생성되어 컴파일이 충돌한다.

## 5. 현재 정산 관련 계약 현황

| 계약(rpc) | 요청자(client) | 서버(owner) | 위치 | 비고 |
| --- | --- | --- | --- | --- |
| `GetSellers`(셀러 정보) | settlement | **user** | `grpc/user/seller_query.proto` | 루트 공유 이관 완료(서버=user 담당). settlement 클라이언트는 도입 예정 |
| `GetSettleableLines`(정산 원천) | settlement | **order** | `grpc/order/order_query.proto` | 루트 공유 이관 완료. order 서버 미구현이라 정산 클라이언트가 유일본 |
| `CountBySeller`(셀러 상품·판매수) | user(sellersettlement) | **product** | `grpc/product/product_query.proto` | 루트 공유 이관(소비자 user 미러). product 서버 원본은 product-service 에 잔존(범위 밖) |

### product 서버 원본이 잔존하는 이유 (예외)

세 계약을 모두 `grpc/` 로 이관했다. 다만 `CountBySeller` 는 서버 원본이
`product-service/src/main/proto/product_query.proto` 에 **살아있다.** product 는 정산 담당 범위 밖이라
그 원본을 제거하지 못한다. 그래서 `grpc/product/product_query.proto` 는 소비자(user) 미러를 옮긴 것이고,
product 원본과 같은 wire(`package settlement.product`·service·message·필드 번호)로 **이중 존재**한다.
product 팀이 공유 `grpc/` 에 참여하면 서버 원본을 이 파일로 통합해 이중 관리를 없앤다.

반면 `GetSettleableLines` 는 order 서버가 미구현이라 order-service 에 원본이 없어,
`grpc/order/order_query.proto` 가 유일본이다(이중 존재 아님). order 팀이 서버를 신설하면 이 계약을 그대로 참조한다.

### `java_package` 네이밍 — 도메인 중립

`java_package` 는 소유·소비 어느 모듈에도 묶지 않고 **도메인 중립 `com.prompthub.grpc.<도메인>`** 으로 통일한다.
루트 공유 계약이라 특정 모듈 네임스페이스(settlement·user)에 종속되지 않게 한다.

- `seller_query.proto` → `com.prompthub.grpc.seller`
- `order_query.proto` → `com.prompthub.grpc.order`
- `product_query.proto` → `com.prompthub.grpc.product`

`package`(wire, `settlement.<도메인>`)는 서버·클라이언트가 공유하는 호출 경로라 그대로 두고, `java_package`
(생성 자바 클래스 패키지)만 중립화했다. product 서버 원본(product-service)은 범위 밖이라 자기 `java_package`
(`com.prompthub.product.grpc`)를 유지하지만, wire(`package settlement.product`)가 같아 통신에는 영향이 없다.
