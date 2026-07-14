# Settlement ORDER_REFUNDED Envelope 호환성 요청

## 현재 호환 상태

Settlement Service는 이미 `ORDER_REFUNDED` 이벤트와 그 상품별 payload를 인식한다. 다만 배포 전 Settlement 이벤트 envelope의 최상위 `version` 필드는 Order Service 공통 `EventMessage`와 호환되어야 한다.

## 롤아웃 게이트

- Settlement의 `ORDER_REFUNDED` listener를 활성화한다.
- listener 활성화 전에 root `version`이 공통 Order `EventMessage` envelope와 호환되는지 확인한다.
- `ORDER_REFUNDED`의 상품 payload를 기존 Settlement 처리 방식으로 계속 소비할 수 있는지 확인한다.

위 조건이 모두 충족되기 전에는 Order Service의 부분 환불 성공 결과를 Settlement에 의존하는 롤아웃을 진행하지 않는다.
