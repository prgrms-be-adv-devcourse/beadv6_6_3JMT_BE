# 정산 원장 역할

## 목적

정산 계산 결과와 승인·지급 업무 상태의 기준 테이블을 구분한다. 같은 의미의 상태를 여러 테이블에 저장해 값이 어긋나는 문제를 방지하는 것이 목적이다.

## 원장별 책임

### `settlement`

`settlement-service`가 관리하는 계산 원장이다.

- 정산 대상 기간과 판매자
- 판매·환불 건수 및 금액
- 수수료와 최종 정산액
- 계산 시각과 배치 정보
- 계산 결과에 포함된 상세 내역

계산이 끝난 결과를 보존하며 승인, 보류, 지급 요청, 지급 완료 같은 운영 상태는 관리하지 않는다. 계산 결과를 수정해야 할 때는 기존 원장의 값을 변경하는 대신 기존 정산을 취소하고 대상 원천 데이터를 해제한 후 새 정산을 생성한다.

### `seller_settlement`

`user-service`가 관리하는 승인·지급 운영 원장이다.

- 승인 대기와 승인 보류
- 승인 완료
- 지급 요청과 지급 보류
- 지급 완료
- 정산 취소

관리자 정산 기능도 `seller_settlement`을 기준으로 상태를 조회하고 변경한다. 따라서 승인·지급 업무 상태의 기준은 `seller_settlement.status`다.

## 서비스 간 연결

정산 배치가 완료되면 `settlement-service`는 계산 결과를 정산 생성 이벤트로 전달한다. `user-service`는 이벤트의 합계와 상세를 검증하고 `seller_settlement`을 생성한다.

이벤트는 계산 결과를 전달하는 경계이며 운영 상태를 양방향으로 동기화하지 않는다. 이후 상태 변경은 `seller_settlement`에서만 처리한다.

## 취소 처리

정산을 취소하면 다음과 같이 처리한다.

1. `seller_settlement`의 상태를 `CANCELLED`로 변경한다.
2. 해당 `settlement_source_line`의 `settlement_id`를 제거해 다음 정산 배치 대상에 포함될 수 있게 한다.
3. 기존 `settlement`와 `settlement_detail`은 당시 계산 결과를 확인할 수 있도록 유지한다.
4. 다음 배치가 실행되면 해제된 원천 데이터를 기준으로 새 `settlement`를 생성한다.

## 스키마 정리

`settlement.payout_status`와 `settlement.settlement_status`는 운영 상태의 기준으로 사용되지 않았다. `V5__remove_operational_statuses_from_settlement.sql`에서 두 컬럼을 제거하며 관련 도메인 필드와 enum도 함께 제거한다.

이번 변경에는 별도의 송금 원장이나 원장 간 대사 결과 테이블 추가는 포함하지 않는다.
