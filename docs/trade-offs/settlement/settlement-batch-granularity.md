# 정산 배치 처리 단위

정산 잡은 판매자 단위로 집계하는 파이프라인이다(원천 적재 step 뒤에 3-step 집계가 붙는다).
주문을 한 건씩 흘려보내는 단일 chunk step이 아니다.

## 지금 구조

- 잡은 네 step을 순서대로 돈다. 맨 앞 원천 적재 step은 #260에서 order gRPC pull로 붙었다
  (배경·계약은 `order-data-sourcing.md`). 이 문서의 관심사인 "집계 단위"는 그다음 `settlementStep`이다.
  - `loadSettlementSourceStep` (tasklet) — 그 기간의 결제·환불 라인을 order에서 gRPC pull로 당겨
    `settlement_source_line`에 멱등 적재한다.
  - `createSettlementBatchStep` (tasklet) — 해당 기간의 `SettlementBatch` 레코드를 연다.
  - `settlementStep` (chunk) — 실제 정산 작업.
  - `completeSettlementBatchStep` (tasklet) — 배치를 완료로 표시한다.
- chunk step의 item은 주문 하나가 아니라 판매자 하나다: `<SettlementTarget, Settlement>`.
  - Reader가 기간 내 정산 대상 판매자 id를 하나씩 넘긴다.
  - Processor가 `CalculateSettlementUseCase`를 호출해 판매자마다 `Settlement` 하나(그에 딸린
    `SettlementDetail` 라인 포함)를 반환한다. 상품이 없는 판매자는 버린다(`null`).
  - Writer가 `Settlement` 애그리거트를 저장한다.
- job 레벨 리스너가 실행이 깨지면 `SettlementBatch`를 실패 처리한다.

```java
new JobBuilder(SETTLEMENT_JOB_NAME, jobRepository)
        .listener(settlementBatchFailureListener)
        .start(loadSettlementSourceStep)
        .next(createSettlementBatchStep)
        .next(settlementStep)
        .next(completeSettlementBatchStep)
        .build();
```

이전 버전은 `Order`를 대상으로 한 단일 chunk step이었다(`<Order, SettlementItem>`).
`JpaPagingItemReader`를 썼고, 배치 레코드도 둘러싼 step도 없었다 — 주문을 읽고, 매핑하고, 쓴다.

## 무엇을 견주는가

item 단위가 사실상 나머지 전부를 결정한다.

주문 단위는 chunk 배칭에 자연스럽게 맞는다. 페이징 reader가 행을 흘려보내고, 상태는 페이지마다
저장되며, 재시작하면 멈춘 자리에서 잇는다. 하지만 주문은 누구도 "정산하는" 단위가 아니다.
정산은 "이 판매자, 이번 달"이라서, 주문 단위 잡은 결국 어딘가에서 주문을 다시 판매자로 묶어야
하고, 써내는 결과가 도메인이 신경 쓰는 대상과 어긋난다.

판매자 단위는 도메인과 맞는다. item 하나가 들어가면 `Settlement` 하나가 나온다. 대가는 reader다.
정산 대상 판매자 id를 전부 미리 메모리에 올려 페이징 없이 순회하므로, step 중간 재시작이 안 되고
아주 큰 판매자 집합이 한꺼번에 메모리에 앉는다. 한 달치 우리 판매자 수라면 치를 만한 값이다.
판매자가 크게 늘면 reader가 가장 먼저 손볼 곳이다(id를 페이징하거나, 판매자 id 범위로 chunk를
나눈다).

## 선택지

| 선택지 | 장점 | 단점 |
| --- | --- | --- |
| 판매자 단위, 3-step (선택) | item이 도메인 단위와 맞는다, 배치 생명주기를 기록하고 실패 처리할 수 있다, 계산이 use case에 산다 | reader가 판매자 id를 전부 메모리에 올린다, step 중간 재시작이 안 된다 |
| 주문 단위, 단일 chunk step | 페이징 reader가 행을 흘려보낸다, 재시작 가능, 메모리 최소 | 주문은 정산 단위가 아니다 — 재묶음이 필요하다, 배치 레코드가 없다, 매핑 로직이 인프라로 샌다 |
| 판매자 단위지만 id를 페이징 | 도메인 단위 + 제한된 메모리 | reader 코드가 늘어난다, 파생된 id 목록을 페이징하는 게 까다롭다 |

## 정산에 맞는 선택

- 정산은 판매자별·기간별로 보고하고 지급한다. 그걸 배치 item으로 삼으면, 잡이 우리가 보관하는
  레코드를 정확히 그대로 만들어낸다. 재묶음 step을 덧붙일 필요가 없다.
- `SettlementBatch` 레코드는 실행마다 한 행을 줘서 상태와 실패를 추적하게 한다. 단일 step
  버전은 이걸 둘 데가 없었다.
- 계산을 `CalculateSettlementUseCase`에 두면 배치는 흐름 제어만 맡는다. 그래서 같은 규칙을
  나중에 배치 밖에서도 로직 복사 없이 돌릴 수 있다.
- 메모리 비용은 한 달치 실행에서 판매자 수로 묶이는데, 지금은 충분히 작다. 그렇지 않을 때를
  대비한 페이징 대안은 위에 적어 뒀다.
