# grpc — 서비스 간 gRPC 계약 공유 디렉토리

서비스 간 gRPC proto 계약을 여기서 **단일 관리**한다. 계약이 모듈마다 미러로 중복되어 두 곳에서
수정하던 문제를 없애기 위함이다.

## 규칙 (요약)

- **응답하는 쪽(서버)이 계약을 소유한다.** `a` 가 `b` 에게 요청/응답받으면 계약은 `grpc/<b>/` 에 둔다.
- 하위 디렉토리 이름은 **서버 모듈명**을 따른다. (`grpc/user/`, `grpc/order/` …)
- **이름도 응답자 기준:** 파일 `<응답자>_query.proto`, 서비스 `<응답자>QueryService`, 메서드·메시지는
  `Get<목적어>` / `Get<목적어>Request` / `Get<목적어>Response`. 내부 항목 메시지(`SellerInfo` 등)는 예외.
- 제공·소비 모듈은 `build.gradle` 의 protobuf `srcDir` 로 이 경로를 참조한다.

  ```gradle
  sourceSets { main { proto { srcDir "${rootProject.projectDir}/grpc/user" } } }
  ```

- 여기로 옮긴 계약은 원래 모듈 `src/main/proto` 에서 삭제한다(이중 생성 충돌 방지). 단 client 로만 소비하는
  미러(예: product 가 user 를 호출하는 `seller_query.proto`)는 소비 모듈에 잔존할 수 있다.
- **wire `package` 는 `prompthub.<도메인>`** (프로젝트 접두어 + 소유 도메인). 통합 계약은 여러 호출자가
  공유하므로 특정 소비자명(`settlement` 등)을 쓰지 않는다. gRPC 호출 경로
  (`/prompthub.<도메인>.<서비스>/<메서드>`)라, 바꾸면 서버·클라이언트가 함께 바뀌어야 통신된다.
- **`java_package` 도 소유자(서버) 기준** — `com.prompthub.<서버모듈>.grpc[.<도메인>]`. 서버 모듈명과
  도메인이 같으면 도메인을 생략한다(`com.prompthub.order.grpc`), 다르면 뒤에 붙인다
  (`com.prompthub.user.grpc.seller`). wire `package` 와는 별개다.

## 현재 레이아웃

```
grpc/
├── user/seller_query.proto        ← SellerQueryService.GetSellers        (소유: user)
├── order/order_payment.proto      ← OrderPaymentService.GetOrderForPayment (소유: order)
├── order/order_query.proto        ← OrderQueryService.GetSettleableLines (소유: order, 서버 미구현)
├── product/product_query.proto    ← ProductQueryService.Get* (셀러통계·스냅샷·콘텐츠·상품조회, 소유: product)
└── payment/payment_query.proto    ← PaymentQueryService.GetRefund        (소유: payment)
```

`order/order_payment.proto` 는 기존 클라이언트와의 wire 호환성을 위해 레거시 package
`payment.order` 와 Java package `com.prompthub.grpc.order.v1` 을 그대로 유지한다.

전문 규칙과 정산 계약 현황은 `docs/grpc-contract-ownership.md`를 본다.
