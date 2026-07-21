# 서비스 간 동기 호출 방식 — REST vs gRPC

정산 도메인은 정산 대상을 계산할 때 order의 결제·환불 라인이 필요하다. 이 원천 데이터는 order가
소유하므로 settlement-service가 배치 실행 중 동기로 조회한다. 이 문서는 정산 정확성에 필요한 내부
동기 조회를 REST로 구현할지 gRPC로 구현할지 다룬다.

판매자 화면의 등록 상품 수·판매건수는 #452에서 정산 백엔드 조합 대상에서 제외됐다. Product 공개
API와 Seller Settlement summary를 프론트가 각각 호출하므로 이 문서의 내부 전송 선택 대상이 아니다.

> 비동기 전송(Kafka)으로 데이터를 미리 받아둘지, 동기로 그때그때 당길지의 선택은
> `order-data-sourcing.md`에서 다룬다. 이 문서는 **동기로 당기기로 한 뒤** 그 전송을
> REST로 할지 gRPC로 할지에 한정한다.

## 지금 구조

정산 도메인에서 현재 유지하는 내부 동기 호출은 settlement-service가 order-service에서 정산 원천
라인을 가져오는 경로다.

- **settlement-service(정산 본체)** — `OrderSettlementQueryClient` → order 서비스:
  배치 시점에 기간의 정산 라인(`GetSettleableLines`)을 bulk로 가져와
  `settlement_source_line`에 적재한다.

```text
settlement-service ─gRPC(blocking)─▶ order-service (정산 원천 라인 pull, #260)
```

판매자 정산 요약의 상품 수·판매건수는 #452에서 내부 동기 호출 대상에서 제외됐다. Product가 공개
API를 제공하고 프론트가 Seller Settlement summary와 별도로 호출한다.

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

정산 금액과 대상을 결정하는 order 원천 라인은 백엔드 서비스 간 내부 계약이며 기간 단위 bulk 조회다.
따라서 `.proto`로 타입을 강제하고 한 요청에 여러 라인을 전달하는 gRPC를 유지한다. 전송 세부사항은
`OrderSettlementQueryPort` 뒤에 두어 애플리케이션 계층이 gRPC에 직접 의존하지 않게 한다.

반대로 화면 조합용 Product 통계는 브라우저가 소비하는 공개 데이터다. Product가 REST API를 소유하고
프론트가 직접 호출하는 편이 서비스 경계와 장애 범위를 더 분명하게 만든다. Seller Settlement는 자기
저장소의 금액만 응답한다.

## 실패를 어떻게 다루나

order 정산 라인 조회는 정산 금액과 대상에 직접 영향을 준다. 실패를 빈 결과로 바꾸면 조용한 0건
정산이 발생할 수 있으므로 `OrderSettlementQueryClient`는
`SettlementException(SETTLEMENT_SOURCE_QUERY_FAILED)`로 배치를 중단한다.

Product 상품 통계 실패는 정산 백엔드의 실패 정책 대상이 아니다. 프론트가 Product와 Seller
Settlement 요청 상태를 분리해 한쪽 실패가 다른 쪽 정상 값을 가리지 않게 처리한다.

## 정리

- 정산 정확성에 필요한 order 원천 라인은 내부 gRPC로 조회하고 실패 시 배치를 중단한다.
- 화면 조합용 Product 통계는 Product 공개 REST API가 소유하고 프론트가 직접 호출한다.
- Seller Settlement summary는 자기 저장소의 금액만 반환해 Product 가용성과 분리한다.
