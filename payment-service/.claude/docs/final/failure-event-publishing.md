# (해체됨) 결제·환불 실패 이벤트 발행

> **2026-07-05 해체 (00-execution-order.md D2·D5)**: 이 문서의 내용은 아래 두 문서로 흡수되어 더 이상 독립 작업이 아니다.
>
> - **결제 실패(`payment.failed`) 발행 + order 소비(FAILED 전이·재결제 복귀 D1)** → [order-payment-flow-redesign.md](order-payment-flow-redesign.md) — payment-service 변경 §6, order-service 변경 §3 (작업 2)
> - **환불 실패(`payment.refund-failed`) 발행 + order 소비(상품 단위 PAID 복구)** → [partial-refund-api.md](partial-refund-api.md) — 신 환불 모델 기준 (작업 3)
>
> 흡수 이유: 결제 실패 이벤트는 flow-redesign이 재작성하는 confirm 트랜잭션 구조(`noRollbackFor`)와 재결제 정책(D1)의 일부이고, 환불 실패 이벤트는 부분 환불 실패 경로의 필수 구성요소라서(누락 시 OrderProduct가 REFUND_REQUESTED에 고착) 각 작업과 분리 배포가 불가능하다.
>
> 이 파일은 링크 안정성을 위해 남겨 둔 스텁이며, 팀 확인 후 삭제해도 된다.
