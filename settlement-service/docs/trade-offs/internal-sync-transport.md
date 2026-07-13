# 서비스 간 동기 호출 방식 — REST vs gRPC

정산 도메인은 자기 데이터만으로 끝나지 않는다. 정산 대상을 추릴 때 order 의 결제·환불 라인이
필요하고, 판매자 정산 요약을 보여줄 때 상품 수·판매건수가 필요하다. 이 데이터는 다른 서비스가
소유하므로, 실행 중에 그 서비스를 동기로 호출해 당겨온다. 이때 무엇으로 호출하느냐 —
REST(HTTP/JSON)냐 gRPC냐 — 가 이 문서의 주제다.

> 비동기 전송(Kafka)으로 데이터를 미리 받아둘지, 동기로 그때그때 당길지의 선택은
> `order-data-sourcing.md`에서 다룬다. 이 문서는 **동기로 당기기로 한 뒤** 그 전송을
> REST로 할지 gRPC로 할지에 한정한다.

## 지금 구조

정산 도메인의 동기 호출은 모두 gRPC 블로킹 스텁이다. 셀러 정산이 user-service 로 이관되며
(`seller-settlement-separation.md`) 정산 본체와 셀러 정산이 각기 다른 대상을 호출한다.

- **settlement-service(정산 본체)** — `OrderSettlementQueryClient` → order 서비스:
  배치 시점에 그 기간의 정산 라인(`GetSettleableLines`)을 bulk 로 당겨 `settlement_source_line` 에 적재.
- **user-service `sellersettlement`(셀러 정산)** — `ProductStatsGrpcClient` → product 서비스:
  셀러 등록 상품 수·판매건수(`GetSellerStats`)를 요약 조회 시 한 응답으로 조회.

```
settlement-service    ─gRPC(blocking)─▶ order-service    (정산 원천 라인 pull, #260)
user sellersettlement ─gRPC(blocking)─▶ product-service  (상품 수·판매건수)
```

- 계약은 루트 `grpc/<소유서버>/`에 두고 각 모듈이 build.gradle 의 proto `srcDir`로 참조한다
  (`grpc/order/order_query.proto`, `grpc/product/product_query.proto`). 스텁은 빌드 시 생성된다.
- 채널·스텁 빈은 호출 대상 서비스별 config에서 따로 만든다
  (`OrderGrpcClientConfig`, `ProductStatsGrpcClientConfig`). 주소는 yml로 주입한다
  (`grpc.client.<service>.address`).
- 어댑터는 `application/port`의 포트(`OrderSettlementQueryPort` 등)를 구현한다. 안쪽 계층은
  전송이 gRPC인지 모른다 — 포트 뒤에 가려져 있다.
- 판매자명 조회(`GetSellers`) 서버는 user-service `seller` 패키지에 live 이나, 이를 부르는
  정산측 클라이언트는 아직 없다. 실제 구현/대기 현황은 `../architecture/settlement-internal-comm-topology.md`.

## 무엇을 견주는가

둘 다 "동기 요청/응답"이라는 점은 같다. 갈리는 건 **계약을 어떻게 적고, 무엇으로
직렬화하며, 코드를 어떻게 얻느냐**다.

| | REST (HTTP/JSON) | gRPC (HTTP/2 + protobuf) |
| --- | --- | --- |
| 계약 | OpenAPI 문서(또는 합의), 강제력 약함 | `.proto`가 곧 계약, 타입까지 강제 |
| 직렬화 | JSON 텍스트 | protobuf 바이너리 |
| 클라이언트 코드 | 직접 작성하거나 코드젠 | `.proto`에서 스텁 자동 생성 |
| 스트리밍 | 기본은 단건 응답 | 양방향 스트리밍 네이티브 |
| 사람이 읽기 | curl·브라우저로 바로 확인 | 별도 툴(grpcurl 등) 필요 |
| 진입 장벽 | 누구나 아는 기본기 | proto·빌드 플러그인 학습 필요 |

## REST가 나은 경우

- **외부에 노출하거나, 사람이 직접 호출**한다. 브라우저·curl·프론트엔드가 바로 쓸 수
  있어야 하면 REST다. (정산의 외부 API가 REST인 이유다 — `settlement-api-for-frontend.md`)
- **호출 빈도가 낮고 페이로드가 작다.** JSON 오버헤드가 문제될 일이 없으면 굳이 proto를
  들일 이유가 약하다.
- **계약을 자주 바꾸고 느슨하게 두고 싶다.** 필드 추가가 잦고 엄격한 타입 강제가 부담이면
  JSON의 유연함이 편하다.

## gRPC가 나은 경우

- **서비스 간 내부 호출**이다. 양쪽 다 우리가 통제하는 백엔드라, 사람이 직접 들여다볼 일이
  드물고 grpcurl 같은 툴로 충분하다.
- **계약을 코드로 강제하고 싶다.** `.proto` 한 벌이 양쪽의 단일 출처가 된다. 필드 타입이
  어긋나면 컴파일에서 걸리지, 런타임 JSON 파싱에서 터지지 않는다.
- **한 요청으로 여러 건을 당기는** 배치성 호출이다. period 하나로 그 기간의 결제·환불 라인을
  `repeated` 로 받는 `GetSettleableLines` 가 이 모양이고, protobuf 바이너리가 JSON보다 가볍다.
- **호출량이 늘 여지가 있다.** HTTP/2 멀티플렉싱과 바이너리 직렬화는 호출이 잦아질수록
  REST 대비 이득이 커진다.

## 정산에 맞는 선택

정산의 동기 호출은 전부 **내부 서비스 대 내부 서비스**다. 프론트엔드가 user-service의
gRPC를 직접 부를 일은 없다 — 그건 정산이 자기 응답을 만들면서 뒤에서 채우는 데이터다.
그래서 외부 노출이라는 REST의 강점은 여기서 안 쓰인다.

대신 gRPC의 강점이 그대로 들어맞는다.

- **계약이 팀 경계를 넘는다.** 정산 라인·상품 통계의 모양을 order·product 팀과 합의해야
  하는데, `.proto`가 그 합의를 코드로 박아준다. 문서로만 합의하면 한쪽이 필드를 바꿔도
  조용히 어긋나지만, proto는 빌드에서 잡힌다.
- **조회가 배치성이다.** period 하나로 그 기간의 정산 라인을 한 번에 당기는 패턴이라, 바이너리
  직렬화의 가벼움이 누적으로 이득이다.
- **포트 뒤에 숨길 수 있다.** 전송이 무엇이든 `OrderSettlementQueryPort`는 그대로다. 나중에
  REST로 바꾸거나 Kafka 사본으로 갈아타도 안쪽 계층은 건드리지 않는다 — 바뀌는 건
  `infrastructure/client`의 어댑터뿐이다.

그래서 **내부 동기 호출은 gRPC, 프론트엔드용 외부 API는 REST**로 나눈다. 같은 서비스가
두 전송을 쓰지만 역할이 다르다 — 안으로는 gRPC, 밖으로는 REST.

## 실패를 어떻게 다루나

동기 호출은 상대 서비스가 떠 있어야 한다는 결합을 안고 간다. 이 결합이 정산 전체를
무너뜨리지 않게, 호출의 성격에 따라 실패를 다르게 처리한다.

- **부가 정보 조회 — 죽지 않고 비운다.** 셀러 상품 수·판매건수는 정산 요약을 보기 좋게 하는
  참고 데이터지 정산 금액을 좌우하지 않는다. 그래서 `ProductStatsGrpcClient`는 gRPC가 실패하면
  예외를 위로 던지지 않고 **0으로 폴백**한다(`StatusRuntimeException`을 잡아
  `SellerProductStats.empty()` 반환, 로그만 남김). 그 값이 0으로 보일지언정 요약 조회 자체는 살아 있다.
- **정산 정합성에 관여하는 조회 — 막는다.** order 정산 라인 조회(`OrderSettlementQueryClient`)는
  정산 금액·대상에 직접 영향을 준다. 여기서 조용히 비우면 과소·과대 정산(조용한 0건 정산)이 된다.
  그래서 이 호출은 실패를 삼키지 않고 `SettlementException(SETTLEMENT_SOURCE_QUERY_FAILED)`로
  **배치를 멈춘다.** 부가 정보와 정합성 데이터는 실패 정책이 달라야 한다.

> 한 줄 규칙: **장식용 조회는 실패해도 비우고 지나가고, 돈에 영향 주는 조회는 실패하면
> 멈춘다.** 어느 쪽인지 호출마다 정해 둔다.

## 정리

- 내부 서비스 간 동기 호출은 gRPC. 계약을 `.proto`로 강제하고, 배치성 조회에 가볍다.
- 프론트엔드·외부용은 REST. 사람이 바로 쓰고, 계약을 느슨히 둘 수 있다.
- 전송은 `application/port` 뒤에 가려 둔다. 바꾸더라도 어댑터만 바뀐다.
- 동기 호출의 결합 비용은 실패 정책으로 갚는다 — 장식은 비우고, 돈은 멈춘다.
