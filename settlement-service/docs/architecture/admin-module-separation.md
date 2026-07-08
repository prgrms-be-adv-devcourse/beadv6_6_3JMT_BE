# 어드민 모듈 분리 설계 (파이널)

정산 서비스 한 프로세스에 지금 세 가지가 섞여 있다 — 정산 배치(스케줄러 포함), 어드민
페이지용 API, 판매자용 API. 파이널에서 어드민 페이지 전용 모듈(admin-service)이 생기며,
이 중 **어드민 API 만 admin-service 로 이관**한다. 이 문서는 무엇이 옮겨가고 무엇이
남는지, 어드민이 정산 데이터를 어떻게 접근하는지를 정한다.

> admin-service 는 전사 어드민 모듈이다. 추후 다른 도메인(유저·상품·주문) 어드민도
> 이 모듈로 모이지만, 여기서는 정산 몫만 다룬다.
> 어드민이 정산 데이터를 gRPC 로 조회하지 않고 **각 도메인 DB 를 직접 바라보기로** 한
> 결정 배경은 `../trade-offs/admin-data-access.md` 를 본다.

> **운영 상태의 단일 진실은 `seller_settlement`(셀러/유저 모듈 테이블)다.** 셀러 정산 분리
> (`../trade-offs/seller-settlement-separation.md`)로 운영 lifecycle 이 유저 모듈로 옮겨가면서,
> 어드민의 **운영 조회(목록·요약·상세)와 상태변경(승인·지급 등)은 `seller_settlement`(유저 DB)
> 를 직접 read/write** 한다. 정산의 `settlement` 테이블은 배치가 만든 **계산 이력(로그)** 로
> 남고, 어드민이 여기 직접 접근하는 것은 **배치 예약·잡 상태**(정산 DB) 뿐이다. 상태 모델은
> `SettlementDisplayStatus` 7값 단일이다(옛 이중상태 `SettlementStatus`×`PayoutStatus` 아님).

## 목표 구조

```
admin-service (신설)
 ├─ 어드민 API (REST — 어드민 프론트가 호출)
 │   ├─ 정산 조회 (목록·요약·상세)      ── seller_settlement 직접 SELECT   (유저 DB)
 │   ├─ 정산 상태 변경 (승인·지급 등)   ── seller_settlement 직접 UPDATE   (유저 DB)
 │   └─ 배치 예약 접수 · 상태 조회      ── 예약 테이블 INSERT / SELECT     (정산 DB)
 └─ ──JDBC(직접 커넥션)──▶ 운영: 유저 DB(seller_settlement) · 예약/잡: 정산 DB

settlement-service (유지)
 ├─ Spring Batch 잡 본체 + 스케줄러 (@Scheduled 자동 정산 + 예약 폴링)
 ├─ 계산 이력(로그) settlement + seed 이벤트 발행
 └─ 배치 수동 실행 REST (SettlementBatchController — 배치 테스트용 잔류)

user-service (셀러 모듈 — 운영 소유, #236)
 └─ 판매자용 API (REST — SellerSettlementController) + seller_settlement(운영 단일 진실)
```

- **운영 조회·상태변경은 seller_settlement(유저 DB), 배치예약은 정산 DB — 어드민이 각 도메인 DB
  를 직접 접근한다.** 정산에 어드민용 gRPC 서버를 만들지 않고, admin-service 는 정산 서비스
  프로세스를 호출하지 않는다.
- 어드민 프론트 → admin-service 는 REST, admin-service → 각 도메인은 DB 커넥션이다.
  어드민은 도메인 수만큼 DB 커넥션을 맺는 모듈이다.

## 무엇이 가고, 무엇이 남는가

| 대상 | 현재 위치 | 분리 후 |
| --- | --- | --- |
| `SettlementController` (어드민 정산 조회·상태변경) | settlement `presentation` | **admin-service 로 이관** — 어드민이 `seller_settlement`(운영 단일 진실) 직접 조회·UPDATE 로 재구현 |
| `SettlementBatchController` (배치 수동 실행·잡 상태) | settlement `presentation` | 잔류 — **배치 테스트용**. 어드민 프론트 경로에서는 제외 |
| `SellerSettlementController` (판매자용 조회·지급요청) | settlement `presentation` | **유저(셀러) 모듈로 이관(#236)** — 운영 단일 진실 `seller_settlement` 소유 |
| Spring Batch 잡 (Job/Step/Reader/…) | settlement `infrastructure/batch` | 잔류 — 배치는 정산 도메인 로직 |
| `SettlementBatchScheduler` (@Scheduled 자동 정산) | settlement `infrastructure/batch/scheduler` | 잔류 + **예약 폴링 스케줄러 추가** |
| 배치 예약 테이블 | 없음 | **정산 DB 에 신설** — 어드민이 INSERT, 정산이 폴링 |

스케줄러가 남는 이유: 자동 정산은 어드민 페이지 기능이 아니라 정산의 자체 운영 주기다.
잡 본체와 같은 프로세스에 있어야 `JobLauncher` 를 직접 부를 수 있고, 어드민이 죽어도
월 정산은 돌아야 한다.

## 데이터 접근 방식

어드민의 정산 접근은 rpc 계약이 아니라 **DB 스키마가 계약**이다. 운영 조회·상태변경은
`seller_settlement`(유저 DB), 배치 예약·잡 상태는 정산 DB 를 본다. 동작별 경로는 다음과 같다.

| 어드민 동작 | 경로 | 성격 |
| --- | --- | --- |
| 정산 목록·요약·상세 조회 | `seller_settlement` 테이블 직접 SELECT (유저 DB) | 조회 — 페이징·표시상태(7값)는 어드민이 자체 쿼리로 구현. 판매자명은 유저 DB 를 직접 조회해 병합 |
| 정산 상태 변경 | `seller_settlement` 테이블 직접 UPDATE (유저 DB) | 명령 — 상태 전이 가능 여부 검증을 어드민 UPDATE 조건으로 재구현 |
| 배치 예약 | 예약 테이블 INSERT (정산 DB) | 명령 — 실행 주체는 배치를 가진 정산 |
| 예약·잡 상태 조회 | 예약 테이블·Spring Batch 메타테이블 SELECT (정산 DB) | 조회 |

지켜야 할 것:

- **스키마 = 계약.** 운영 접근의 계약 스키마는 `seller_settlement`(유저 DB)다. 그 컬럼
  변경·리네이밍은 어드민 쿼리에 영향을 준다. 스키마 변경 시 어드민 영향 확인을 필수 절차로 둔다.
  (배치 예약 테이블은 정산 DB 스키마가 계약이다.)
- **상태 전이 규칙 단일 출처.** 운영 lifecycle(승인·지급 전이 규칙)의 소유자는 `seller_settlement`
  엔티티(유저 모듈)다. 그 도메인 불변식과 어드민의 UPDATE 조건이 어긋나면 어드민이 규칙을
  우회하는 게 된다. 상태 전이 표를 문서 한 곳에 두고 양쪽이 따른다.
- **어드민 전용 DB 계정.** 필요한 테이블에 최소 권한만 준다. 어드민이 전 도메인 자격증명을
  보유하는 구조의 안전장치다.

## 배치 예약 실행 — 예약 테이블 + 폴링

수동 정산은 "즉시 실행"에서 "시간 지정 예약 실행"으로 바뀐다(`../final-roadmap.md` §2).
어드민이 정산 프로세스를 호출할 수 없으므로, 예약 테이블이 그 사이를 잇는다.

```
admin-service                          settlement-service
 예약 INSERT ──▶ [배치 예약 테이블] ◀── 폴링 스케줄러(@Scheduled, 주기 예: 1분)
 상태 SELECT ◀──  (정산 DB)             ├─ 도래한 PENDING 예약 집기
                                        ├─ PENDING → RUNNING 전이 후 잡 실행
                                        └─ 완료/실패 상태 갱신
```

- 예약 row 는 예약 시각·정산 기준일·상태(PENDING/RUNNING/COMPLETED/FAILED)·요청자를 담는다.
- **즉시 실행은 지금 시각 예약**의 특수형이다.
- **중복 실행 방지:** PENDING → RUNNING 전이를 조건부 UPDATE 로 잡아, 같은 예약을 한 번만
  집게 한다. 중복 예약 정책·테이블 상세 스키마는 예약 기능 설계 시 정한다.
- 기존 즉시 실행 REST(`SettlementBatchController`)는 **배치 테스트용으로 유지**한다.
  어드민 프론트가 타는 표준 경로는 예약이다.

## 이행 단계

1. **문서 반영(지금)** — 이 문서 + trade-off + 연동 카탈로그.
2. **계약 확정** — 배치 예약 테이블 스키마·상태 전이 표 확정, admin-service 모듈 뼈대 생성,
   어드민 전용 DB 계정·권한 준비.
3. **API 이관** — 정산에 예약 폴링 스케줄러 구현, admin-service 에 어드민 REST 구현(운영 조회·
   상태변경은 `seller_settlement`(유저 DB) 직접 SELECT/UPDATE, 배치예약은 정산 DB INSERT). 이
   기간엔 정산의 어드민 REST 와 병행 운영. seller_settlement 재작업은 #245, 병행 제거는 #234.
4. **정리** — 어드민 프론트가 admin-service 로 전환 완료되면 settlement 의
   `SettlementController` 제거. `SettlementBatchController` 는 배치 테스트용으로 남긴다.

## 관련 문서

- 셀러 정산 분리·운영 단일 진실(seller_settlement) 결정: `../trade-offs/seller-settlement-separation.md`
- 접근 방식 결정(직접 DB vs gRPC): `../trade-offs/admin-data-access.md`
- 내부 동기 호출 전송 결정(REST vs gRPC — 서비스 간 호출에 계속 적용): `../trade-offs/internal-sync-transport.md`
- 연동 카탈로그(어드민 절 — rpc 계약 없음): `integration-catalog.md`
- 파이널 전체 로드맵: `../final-roadmap.md`
