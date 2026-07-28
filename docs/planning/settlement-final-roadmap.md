# 정산 파이널 로드맵

세미 프로젝트에서 구현한 정산을 파이널에서 어떻게 고도화할지 모은 문서다. 항목마다
확정 정도가 다르다 — 설계까지 끝난 것, 하기로만 정한 것, 아직 아이디어인 것을 구분해
적고, 구체화되면 상세 문서를 파서 여기서 링크한다.

| 항목 | 상태 | 상세 |
| --- | --- | --- |
| 어드민 모듈 분리 | 설계 확정 | [architecture/admin-module-separation.md](architecture/admin-module-separation.md) |
| 운영 배치 실행 CronJob 전환 | 구현 완료 (#364) | §2 |
| AI 정산 어시스턴트 | 아이디어 | §3 |
| 정산 통계 · 대시보드 | 브레인스토밍 예정 | §4 |
| 지급 완료 Kafka 발행 | 확정 (설계 메모 있음) | [architecture/integration-catalog.md](architecture/integration-catalog.md) §3 |

## 1. 어드민 모듈 분리 — 설계 확정

어드민 페이지용 API(`SettlementController`)를 신설 admin-service 로 이관한다.
배치 계산 책임은 settlement-service에 남고, `SettlementBatchController`는
`settlement.manual-api.enabled=true`인 로컬 검증 환경에서만 생성한다. 운영 기본값은
비활성이며 운영 배치는 Kubernetes `CronJob/settlement-weekly`가 실행한다.
어드민은 gRPC로 호출하지 않고 운영 단일 진실인 `seller_settlement`(유저 DB)를 직접 조회·변경한다.

- 설계: [architecture/admin-module-separation.md](architecture/admin-module-separation.md)
- 운영 단일 진실(seller_settlement) 결정: [trade-offs/seller-settlement-separation.md](trade-offs/seller-settlement-separation.md)
- 접근 방식 결정(직접 DB vs gRPC): [trade-offs/admin-data-access.md](trade-offs/admin-data-access.md)

## 2. 운영 배치 실행 → Kubernetes CronJob — 구현 완료 (#364)

운영 정산 배치는 Kubernetes `CronJob/settlement-weekly`가 매주 월요일 00:00
`Asia/Seoul`에 settlement-service 배치 이미지를 실행한다. `SettlementCronJobRunner`가 실행일의
이전 주 월요일~일요일을 계산해 `settlementJob`을 한 번 실행하고, 완료 여부를 프로세스 종료 코드
`0`/`1`로 반환한다. 배치 계산과 Spring Batch Job 소유권은 settlement-service에 둔다.

- `SettlementBatchController`는 로컬 배치 검증용 기능 플래그가 켜진 환경에서만 사용한다.
- 운영에는 settlement-service Deployment·Service·Gateway 라우트가 없고, 애플리케이션 내부 스케줄도 없다.
- 정산과 order-service는 포함 날짜 `period_start`/`period_end`를 주고받고, order는
  `[periodStart 00:00, periodEnd + 1일 00:00)` 범위로 조회한다. `period(yyyy-MM)`는 이전 배포와의
  호환을 위한 order-service fallback만 유지한다.
- 완료된 과거 주차의 누락 보정과 운영자 일회성 Job 생성은 이번 CronJob 범위 밖이며 별도 설계한다.

## 3. AI 정산 어시스턴트 — 아이디어

정산 데이터를 자연어로 설명해 주는 챗봇형 에이전트(RAG + Tool Calling) 구상. Spring AI
기반으로 검토 중이다. 후보 기능 세 가지, 우선순위 순.

**공통 원칙: 계산·판정은 서버가, AI 는 설명만 한다.** 이상 여부 판단이나 금액 계산을
모델에 맡기지 않는다. 서버가 룰로 계산한 결과를 AI 가 자연어로 요약·설명하는 구조다.

### 3-1. 정산 이상 탐지 설명 어시스턴트 (1순위 — 어드민용)

배치 완료 후 서버가 룰 기반으로 이상 후보를 계산하고(전월 대비 급감, 환불 비율 급증,
주문 금액·정산 대상 금액 불일치, 수수료 계산 불일치, 지급 보류), AI 가 그 결과를
원인 후보·확인 대상·다음 액션으로 요약해 어드민에게 준다.

- 기술 포인트: Spring Batch 결과 분석, 룰 기반 이상 후보 탐지, Spring AI Structured
  Output(`SettlementAnomalyReport` 류 DTO 로 응답 수신), Tool Calling 으로 정산·주문·결제 조회.

### 3-2. 셀러 정산 문의 에이전트 (2순위 — 서비스 기능)

셀러가 마이페이지에서 "이번 달 정산 금액이 왜 이 금액이야?" 같은 질문을 하면, AI 가
Tool 을 골라 **로그인한 sellerId 기준으로만** 정산 데이터를 조회해 계산 흐름(판매액 −
환불 − 수수료 = 정산액)과 지급 상태를 설명한다.

- 기술 포인트: Spring AI Tool Calling 기반 에이전트, 사용자 기반 인가를 Tool 안에서
  강제, Chat Memory 연동 가능.

### 3-3. 정산 정책 변경 영향 분석 (후순위 — 어드민용)

"수수료율을 15% → 12% 로 낮추면 지난달 기준 지급액이 얼마나 늘어?" — 서버가 수수료율
변경 시뮬레이션을 계산하고 AI 가 셀러별 영향도를 설명한다.

## 4. 정산 통계 · 대시보드 — 브레인스토밍 예정

정산 내역 통계 테이블을 신설해 어드민 대시보드를 구성하는 안. 무엇을 집계할지, 배치가
쌓을지 조회 시 계산할지 등 별도 브레인스토밍으로 구체화한 뒤 이 문서에 반영한다.
