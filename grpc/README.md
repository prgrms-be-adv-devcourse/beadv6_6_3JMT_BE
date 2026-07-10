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

- 여기로 옮긴 계약은 원래 모듈 `src/main/proto` 에서 삭제한다(이중 생성 충돌 방지). 단 서버가 담당 범위 밖이면
  그 서버의 원본은 남는다(예: `product_query.proto` 서버 원본은 product-service 에 잔존).
- `java_package` 는 그대로 유지해 생성 클래스·import 를 안 바꾼다.

## 현재 레이아웃

```
grpc/
├── user/seller_query.proto      ← SellerQueryService.GetSellers        (소유: user)
├── order/order_query.proto      ← OrderQueryService.GetSettleableLines (소유: order, 서버 미구현)
└── product/product_query.proto  ← ProductQueryService.GetSellerStats    (소유: product, 서버 원본 잔존)
```

전문 규칙과 정산 계약 현황은 `settlement-service/docs/architecture/grpc-contract-ownership.md` 를 본다.
