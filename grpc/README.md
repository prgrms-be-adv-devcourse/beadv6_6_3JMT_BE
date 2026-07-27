# grpc — 서비스 간 gRPC 계약 공유 디렉토리

서비스 간 gRPC proto 계약을 여기서 **단일 관리**한다. 계약이 모듈마다 미러로 중복되어 두 곳에서
수정하던 문제를 없애기 위함이다.

## 규칙 (요약)

- **응답하는 쪽(서버)이 계약을 소유한다.** `a` 가 `b` 에게 요청/응답받으면 계약은 `grpc/<b>/` 에 둔다.
- 하위 디렉토리 이름은 **서버 모듈명**을 따른다. (`grpc/user/`, `grpc/order/` …)
- **디렉토리는 응답자, 파일·서비스는 도메인 기준:** 파일 `<도메인>_query.proto`, 서비스
  `<도메인>QueryService`, 메서드·메시지는 `Get<목적어>` / `Get<목적어>Request` /
  `Get<목적어>Response`. 예를 들어 user-service가 셀러 정산 데이터를 응답하면 계약 위치는
  `grpc/user/seller_settlement_query.proto`, 서비스는 `SellerSettlementQueryService`다. 내부 항목
  메시지(`SellerInfo` 등)는 예외다.
- 제공·소비 모듈은 `build.gradle` 의 protobuf `srcDir` 로 이 경로를 참조한다.

  ```gradle
  sourceSets { main { proto { srcDir "${rootProject.projectDir}/grpc/user" } } }
  ```

- 여기로 옮긴 계약은 원래 모듈 `src/main/proto` 에서 삭제한다(이중 생성 충돌 방지). 제공·소비 모듈은
  루트의 같은 계약을 참조하고 별도 미러를 유지하지 않는다.
- **wire `package` 는 `prompthub.<도메인>`** (프로젝트 접두어 + 소유 도메인). 통합 계약은 여러 호출자가
  공유하므로 특정 소비자명(`settlement` 등)을 쓰지 않는다. gRPC 호출 경로
  (`/prompthub.<도메인>.<서비스>/<메서드>`)라, 바꾸면 서버·클라이언트가 함께 바뀌어야 통신된다.
- **`java_package` 도 소유자(서버) 기준** — `com.prompthub.<서버모듈>.grpc[.<도메인>]`. 서버 모듈명과
  도메인이 같으면 도메인을 생략한다(`com.prompthub.order.grpc`), 다르면 뒤에 붙인다
  (`com.prompthub.user.grpc.seller`). wire `package` 와는 별개다.

## 현재 레이아웃

```
grpc/
├── order/order_query.proto        ← OrderQueryService.GetSettleableLines/GetOrder (소유: order, 서버 구현)
└── product/product_query.proto    ← ProductQueryService.GetOrderSnapshots/GetCartSnapshots/GetProductContent
                                     (소유: product, 서버 구현)
```

전문 규칙과 정산 계약 현황은 `docs/grpc-contract-ownership.md`를 본다.
