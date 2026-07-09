# 정산 파이널 로드맵

세미 프로젝트에서 구현한 정산을 파이널에서 어떻게 고도화할지 모은 문서다. 항목마다
확정 정도가 다르다 — 설계까지 끝난 것, 하기로만 정한 것, 아직 아이디어인 것을 구분해
적고, 구체화되면 상세 문서를 파서 여기서 링크한다.

| 항목 | 상태 | 상세 |
| --- | --- | --- |
| 어드민 모듈 분리 | 설계 확정 | [architecture/admin-module-separation.md](architecture/admin-module-separation.md) |
| 수동 정산 → 정산 예약 | 방식 확정 — 예약 테이블 + 폴링 (상세 설계 예정) | §2 |
| AI 정산 어시스턴트 | 아이디어 | §3 |
| 정산 통계 · 대시보드 | 브레인스토밍 예정 | §4 |
| 지급 완료 Kafka 발행 | 확정 (설계 메모 있음) | [architecture/integration-catalog.md](architecture/integration-catalog.md) §3 |

## 1. 어드민 모듈 분리 — 설계 확정

어드민 페이지용 API(`SettlementController`)를 신설 admin-service 로 이관한다.
배치·스케줄러는 정산에 남고, 배치 수동 실행 REST(`SettlementBatchController`)도 배치
테스트용으로 잔류한다(판매자 API·운영 상태는 유저 모듈 seller_settlement 로 이관 — #236).
어드민은 gRPC 로 호출하지 않고 **DB 를 직접 바라본다** — 운영 조회·상태변경은 운영 단일 진실인
`seller_settlement`(유저 DB) 직접 SELECT/UPDATE, 배치 실행은 정산 DB 의 예약 테이블(§2)로.

- 설계: [architecture/admin-module-separation.md](architecture/admin-module-separation.md)
- 운영 단일 진실(seller_settlement) 결정: [trade-offs/seller-settlement-separation.md](trade-offs/seller-settlement-separation.md)
- 접근 방식 결정(직접 DB vs gRPC): [trade-offs/admin-data-access.md](trade-offs/admin-data-access.md)

## 2. 수동 정산 → 정산 예약 — 방식 확정 (상세 설계 예정)

지금 수동 정산은 어드민이 실행하는 **그 시점에** 배치가 돈다. 이를 **시간을 지정해
예약**하는 방식으로 바꾼다 — 어드민이 실행 시각을 지정하면 그 시각에 배치가 돈다.

- **방식은 예약 테이블 + 폴링으로 확정했다.** admin-service 가 정산 DB 의 배치 예약
  테이블에 예약을 INSERT 하고, 정산의 폴링 스케줄러(@Scheduled)가 도래한 예약을 집어
  잡을 실행한 뒤 상태를 갱신한다. 어드민은 상태를 SELECT 로 확인한다.
  (구조·중복 실행 방지는 [architecture/admin-module-separation.md](architecture/admin-module-separation.md) 의 "배치 예약 실행" 절)
- 즉시 실행은 예약 실행의 특수형(지금 시각 예약)이다. 기존 즉시 실행 REST 는 배치
  테스트용으로 남는다.
- 예약 테이블 상세 스키마·폴링 주기·중복 예약 정책은 설계 시 정한다.

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
