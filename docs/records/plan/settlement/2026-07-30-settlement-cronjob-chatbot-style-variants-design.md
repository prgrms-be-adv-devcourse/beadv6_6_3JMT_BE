# 정산 CronJob 챗봇 스타일 발표 자료 디자인

## 목적

기존 정산 CronJob 파이프라인을 AI 정산 챗봇 아키텍처와 같은 시각 체계로 다시 제작한다.
현재 발표 구조를 유지한 버전과 실제 코드의 배치 완료·전달 경계를 강조한 버전을 함께 제공한다.

## 코드에서 확인한 실행 계약

- Kubernetes `settlement-weekly` CronJob은 매주 월요일 00:00 `Asia/Seoul`에 실행된다.
- `concurrencyPolicy: Forbid`로 동시 실행을 막고 one-shot 프로세스로 종료한다.
- `SettlementCronJobRunner`는 직전 월요일부터 일요일까지를 정산 기간으로 계산한다.
- Spring Batch 정상 순서는 다음과 같다.
  1. `createSettlementBatchStep`
  2. `loadSettlementSourceStep`
  3. `reconcileSettlementSourceStep`
  4. `settlementStep`
  5. `reconcileSettlementCalculationStep`
  6. `completeSettlementBatchStep`
  7. `deliverSellerSettlementsStep`
- 원천 적재와 원천 대사에서 Order Service의 `GetSettleableLines`를 각각 호출한다.
- 원천 대사가 실패하면 `RECONCILIATION_FAILED`로 Job을 중단한다.
- 계산 대사가 실패하면 불일치 정산과 Delivery를 정리한 뒤 Job을 실패시킨다.
- 계산 대사가 성공하면 배치를 `COMPLETED`로 만든 후 User Service에 전달한다.
- User gRPC 오류와 Snapshot 불일치는 예외로 Job을 중단하지 않고 각 Delivery를
  `DELIVERY_FAILED` 또는 `MISMATCH`로 기록한다.
- 요청값과 User 저장 Snapshot이 일치하면 Delivery를 `RECONCILED`로 기록한다.
- 재시도 가능한 User gRPC 오류는 1초, 3초 backoff로 최대 3회 시도한다.

## 공통 시각 규칙

- 1600×900, 16:9 가로형 PNG로 제작한다.
- AI 정산 챗봇 다이어그램과 같은 흰색 배경, 옅은 회색 섹션, 둥근 카드 체계를 사용한다.
- 제목, 부제, 읽는 순서 안내, 상단 공통 흐름, 하단 상세 흐름, 하단 핵심 메시지 순서로 배치한다.
- 단계 번호는 파란색 배지, 정상 공통 흐름은 파란색 화살표로 표현한다.
- 정상 결과는 초록색, 불일치는 빨간색, 전달 실패는 주황색으로 구분한다.
- 카드 사이 화살표는 카드 세로 중앙에 맞추고 텍스트나 다른 선과 겹치지 않게 한다.
- 역할 중심 한글 제목을 크게 쓰고 클래스·RPC 이름은 작은 보조 문구로 둔다.
- 한 카드의 설명은 최대 세 줄, 한 줄은 짧은 구문으로 제한한다.
- 기존 `settlement-cronjob-pipeline.png`는 덮어쓰지 않는다.

## A안: 현재 발표 구조 스타일 통일본

### 출력 파일

`settlement-service/docs/architecture/settlement-cronjob-pipeline-chatbot-style.png`

### 상단 공통 흐름

1. **주간 실행 시작**
   - Kubernetes
   - 매주 월요일 00:00 KST
   - 직전 월요일~일요일
2. **배치 생성·원천 적재**
   - Settlement + Order gRPC
   - `PROCESSING`
   - `GetSettleableLines`
3. **원천 완전성 대사**
   - Order 재조회와 로컬 원천 비교
   - PAID·REFUND 건수·금액 검증
4. **정산 계산·계산 대사**
   - 판매자별 chunk 100
   - Settlement·Detail·Delivery 저장
   - 합계·Detail·원천 연결 검증
5. **배치 완료·User 전달**
   - `COMPLETED`
   - `RegisterSellerSettlement`
   - User 저장 Snapshot 반환

### 하단 전달 결과

- `RECONCILED` — 요청값과 저장 Snapshot 일치
- `MISMATCH` — User 저장 Snapshot 값 불일치
- `DELIVERY_FAILED` — 재시도 후에도 gRPC 전달 실패

### 용도

기존 발표 대본과 단계 번호를 그대로 유지하면서 챗봇 자료와 시각 스타일만 통일할 때 사용한다.

## B안: 코드 경계 강조본

### 출력 파일

`settlement-service/docs/architecture/settlement-cronjob-pipeline-code-boundary.png`

### 상단 배치 흐름

1. **주간 실행 시작**
   - Kubernetes CronJob
2. **배치 생성·원천 적재**
   - `PROCESSING`
   - Order gRPC 첫 조회
3. **원천 완전성 대사**
   - Order gRPC 재조회
   - 원천 불일치 시 Job 실패
4. **정산 계산·계산 대사**
   - 계산 결과와 원천 연결 재검증
   - 불일치 데이터 정리 후 Job 실패
5. **배치 완료**
   - `completeSettlementBatchStep`
   - `settlement_batch=COMPLETED`

### 하단 전달 흐름 확대

상단 05에서 수직 화살표로 하단 영역을 연결한다.

1. **User gRPC 전달**
   - `deliverSellerSettlementsStep`
   - `RegisterSellerSettlement`
   - `deliveryRequestId` 멱등 저장
2. **저장 Snapshot 대사**
   - 요청 Settlement·Detail과 User 저장값 비교
3. **결과 분기**
   - `RECONCILED`
   - `MISMATCH`
   - `DELIVERY_FAILED`

하단 제목에 `배치 완료 이후 Delivery 별도 추적`을 명시한다.
User 전달 실패가 상단의 정산 계산과 배치 완료를 되돌리지 않는다는 문구를 하단 핵심 메시지에 포함한다.

### 용도

기술 발표에서 배치 성공과 외부 전달 성공을 분리해 설명할 때 사용한다.
실제 `completeSettlementBatchStep → deliverSellerSettlementsStep` 순서와 실패 격리를 가장 정확하게 보여준다.

## 발표 핵심 메시지

### A안

1. 원천을 두 번 조회해 적재 결과의 완전성을 검증한다.
2. 정산 계산 후 합계·Detail·원천 연결을 다시 검증한다.
3. User 저장 결과까지 비교해 최종 전달 상태를 남긴다.

### B안

1. 원천·계산 대사가 성공해야 배치를 `COMPLETED`로 만든다.
2. User 전달은 배치 완료 이후 실행되며 Settlement별 Delivery로 추적한다.
3. 전달 실패나 Snapshot 불일치는 계산 결과를 취소하지 않는다.

## 검증 기준

- 두 PNG 모두 1600×900이다.
- 챗봇 PNG와 제목·카드·색상·화살표의 시각 체계가 일치한다.
- A안의 단계와 문구가 기존 발표 자료의 의미를 유지한다.
- B안에서 배치 완료가 User 전달보다 먼저 표시된다.
- B안에서 `RECONCILED`, `MISMATCH`, `DELIVERY_FAILED`가 Delivery 상태임이 명확하다.
- Order gRPC가 원천 적재와 원천 대사에서 두 번 호출되는 점이 드러난다.
- 한글과 RPC·상태명이 깨지거나 잘리지 않는다.
- 화살표가 카드 텍스트, 섹션 제목, 다른 화살표와 겹치지 않는다.
