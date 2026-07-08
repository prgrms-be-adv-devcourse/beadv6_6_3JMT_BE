# 정산 셀러 기능 분리 — settlement=로그 / seller_settlement=운영 단일 진실

정산을 K8s CronJob(배치 전용)으로 돌리기로 하면서, 지금 settlement-service 안에 있는
**셀러 정산 기능(본인 정산 조회·지급요청)** 을 셀러(유저) 모듈로 옮기는 방법을 정해야 했다.
처음엔 gRPC 를 떠올렸지만 제약을 따라가다 보니 "정산은 계산 이력(로그), 운영 상태는 셀러
모듈이 소유하는 단일 테이블" 구조로 수렴했다. 이 문서는 그 과정에서 검토한 선택지와 각각을
접은/택한 이유를 남긴다.

> 어드민 쪽 데이터 접근 결정은 `admin-data-access.md`(직접 DB), 모듈 분리 전체 그림은
> `../architecture/admin-module-separation.md` 를 본다. 이 문서는 **셀러 쪽 + 운영 상태를
> 어느 모듈이 소유하느냐** 를 다룬다.

## 결정을 몰고 간 제약

- **정산은 순수 CronJob.** 상시 떠 있는 서버가 없다 → 고정 엔드포인트도, 실시간 요청 수신도 안 된다.
- **지급(payout)은 어드민이 수동 처리.** 셀러의 지급요청을 어드민이 **즉시** 봐야 한다.
- **배치는 월 1회 주기가 될 수 있다.** 배치가 셀러 명령을 소비해 반영하는 방식은 최대 한 달
  지연이라 못 쓴다.
- **어드민은 모든 도메인 DB 를 직접 본다**(전사 어드민 방향, `admin-data-access.md`).
- **셀러는 자기 모듈에 정산 테이블(엔티티)을 소유**하고 거기서 조회한다.
- **정산 도메인은 이중 상태 머신**(`SettlementStatus` × `PayoutStatus`)과 전이 규칙
  (approve/requestPayout/payout/hold/cancel)을 갖는다.

## 검토한 선택지

### 1. gRPC — 정산이 서버가 되어 셀러가 호출 (기각)

CronJob 은 상시 프로세스도 고정 주소도 없어 **gRPC 서버가 못 된다.** 아웃바운드(클라이언트)
호출은 되지만, 셀러/어드민이 정산으로 들어오는 호출을 받을 수는 없다.

### 2. CronJob + Deployment 분리 — 정산에 상시 서버 부활 (보류)

실시간 명령을 받으려면 정산에 작은 Deployment 를 두면 된다. 순수 CronJob 방침과 어긋나
**최후 수단으로만** 남겼다.

### 3. seller_settlement 를 이벤트 read-model 로 (부분 검토 후 폐기)

셀러가 자기 read-model 을 두고 정산이 이벤트(아웃박스)로 채우는 방식.

- **접근 1(배치가 셀러 명령 소비): 기각.** 월 1회 배치가 실시간 지급요청을 반영할 수 없다.
- **접근 2(쓰는 주체가 각자 이벤트 발행): 검토.** 하지만 아래 5의 최종안이 운영 상태를 한
  테이블로 합치면서 이 이벤트 구조 자체가 대부분 불필요해졌다.

### 4. requestPayout 실시간 처리의 갈림길

지급요청은 상태 변경(명령)이고 실시간이어야 한다. 순수 CronJob 엔 받을 서버가 없으니:

- **(a) 셀러가 자기 테이블만 낙관적 변경 + 배치가 나중에 반영** → 어드민은 다음 배치까지
  못 본다. 어드민 수동 지급과 안 맞는다.
- **(b) 셀러가 settlement DB 를 직접 write**(어드민과 같은 direct-DB).
- **(c) 어드민이 seller_settlement 를 직접 조회** → 하지만 요청이 settlement DB 에 안 닿으면
  authoritative(settlement)와 갈라져(split-brain) 어드민이 지급 처리를 못 한다.

### 5. 최종 — settlement=로그, seller_settlement=운영 단일 진실 (채택)

관점을 바꾸니 풀렸다. **`settlement`(정산 DB)는 배치가 만든 계산 이력(로그)**,
**`seller_settlement`(셀러 모듈)은 운영 상태의 단일 진실**로 둔다. 어드민·셀러 둘 다
seller_settlement 를 **직접 read/write** 한다.

핵심 근거 — **표시 상태 하나로 어드민 워크플로우까지 충분하다.** `SettlementDisplayStatus`
7값으로 **전이·가드 판단에 필요한 정보가 전부 보존**된다. `(APPROVED, NOT_READY)` 는
approve 가 항상 READY 로 만들어 실제로 나오지 않기 때문이다.

| 표시 상태 | raw 상태 |
| --- | --- |
| WAITING | (PENDING_APPROVAL, NOT_READY) |
| APPROVAL_ON_HOLD | (SETTLEMENT_ON_HOLD, ·) |
| APPROVED | (APPROVED, READY) |
| PAYOUT_REQUESTED | (APPROVED, PAYOUT_REQUESTED) |
| PAYOUT_ON_HOLD | (APPROVED, PAYOUT_ON_HOLD) |
| PAID | (APPROVED, PAID) |
| CANCELLED | (CANCELLED, ·) |

그래서 어드민의 모든 전이·가드가 이 7값으로 표현된다(approve: WAITING→APPROVED,
payout: PAYOUT_REQUESTED→PAID …). 운영 테이블에 상태 컬럼 하나면 **어드민 워크플로우와
셀러 표시가 다 된다.** (앞의 4-(c)에서 "어드민은 raw 이중상태가 필요하다"고 본 판단을 이
지점에서 뒤집었다.)

엄밀히는 무손실이 아니다 — cancel 은 지급요청·지급보류 상태에서도 가능하고 payout 상태를
리셋하지 않아, 여러 raw 상태가 CANCELLED 하나로 접힌다(표의 `(CANCELLED, ·)`). 다만 어떤
전이 가드도 이 접힌 정보를 쓰지 않으므로 운영에는 영향이 없고, "취소 직전에 지급요청
상태였는지"가 필요해지면 settlement 로그에서 복원한다.

이 구조의 이득:

- 운영 상태가 **한 테이블**에 있고 어드민·셀러가 직접 접근 → 서로의 변경이 즉시 보인다.
  **둘 다 실시간**, 한 테이블이라 당연하다.
- **Kafka·아웃박스·역방향 이벤트가 운영 lifecycle 에선 불필요**해진다. 남는 이벤트는 배치가
  새 정산을 만들 때 seller_settlement 초기행을 **seed** 하는 것뿐이다. (그 seed 발행의
  유실 대비용 최소 아웃박스는 남는다 — 아래 "seed 경로" 참고)
- **split-brain 없음**(운영 진실이 하나). 실시간이지만 settlement DB 에 실시간 write 를 하지
  않으므로 순수 CronJob 이 유지된다.

## 감수하는 비용

- **책임이 settlement-service 밖으로 이동한다.** 운영 lifecycle
  (approve/payout/requestPayout/hold/cancel 도메인 로직)이 seller_settlement 엔티티(셀러
  모듈)로 옮겨간다. settlement-service 는 **배치 + 계산 로그 + seed 발행**으로 축소된다.
  지금의 `Settlement` 도메인·`SettlementController` 어드민 엔드포인트·admin-service 재매핑을
  seller_settlement 기준으로 **재작업**해야 한다(#218 에서 만든 정산 어드민 이관을 상당 부분
  다시 손댄다).
- **어드민이 셀러 모듈 DB 에 직접 write 한다.** approve/payout 등을 seller_settlement 에 쓴다.
  재무 승인 워크플로우가 셀러 모듈 DB 에서 도는 그림이다(admin-centric 모델이면 수용).
- **로그·운영 이중화.** 금액·기간 등은 settlement(로그)와 seller_settlement(운영) 양쪽에
  존재한다(seed 시 복사).

## seed 경로 — Kafka `SettlementCreated` 이벤트로 확정

배치가 `settlement`(로그)를 만들 때 seller_settlement 운영행을 누가 만드나. 세 갈래를 봤다.

- **(i) 배치가 `SettlementCreated` 이벤트 발행 → 셀러 모듈이 소비해 생성 (채택).**
- **(ii) 셀러/어드민이 settlement 로그를 직접 읽어 생성 (기각).** 두 모듈이 각자 읽다 보면
  "누가 언제 초기행을 만드나"가 불명확해지고(on-read 생성 레이스), settlement 로그 스키마가
  셀러·어드민 양쪽의 공용 계약이 된다.
- **(iii) 배치가 seller_settlement 에 직접 INSERT (기각).** 배치(settlement-service)는 셀러
  모듈 DB 자격증명이 없다. 직접 DB 는 전사 어드민이라는 특수 지위로 정당화한 예외인데
  (`admin-data-access.md`), 배치엔 그 지위가 없다. 허용하면 정산이 셀러 스키마에 결합돼
  서비스별 DB 소유 원칙을 반대 방향으로 한 번 더 깨게 된다.

(i)이면 계약이 이벤트 페이로드로 한정되고, 초기행 생성(초기 상태 강제)은 소유자인 셀러
모듈 엔티티 안에 남는다. CronJob 은 서버는 못 되지만 실행 중 Kafka **발행**은 문제없다.

챙길 것 두 가지:

- **멱등 소비.** 배치 재실행·중복 발행에 대비해 셀러 컨슈머는 `settlementId` 유니크 제약으로
  중복 seed 를 무시한다.
- **발행 유실 대비.** "DB 커밋 후 발행 실패"가 남는다. 상시 릴레이는 없으니, 정산 쪽 아웃박스
  테이블을 **다음 배치 실행(또는 배치 마지막 스텝)이 flush** 하는 방식으로 at-least-once 를
  맞춘다.

## 정리

- 셀러 정산 데이터는 **셀러 모듈이 소유한 `seller_settlement`(운영 단일 진실)** 에서
  조회·변경한다. 정산의 `settlement` 은 배치 계산 **로그**로 남는다.
- 어드민·셀러 모두 seller_settlement 를 **직접 DB** 로 접근 → 실시간, 이벤트 인프라 최소화,
  순수 CronJob 유지.
- 상태는 `SettlementDisplayStatus` 7값 하나로 어드민 워크플로우·셀러 표시를 모두 커버한다
  (전이 가드에 필요한 정보는 전부 보존 — CANCELLED 로 접히는 payout 상세만 로그에서 복원).
- 대가: 운영 lifecycle 이 settlement-service → 셀러 모듈로 이동, 어드민이 셀러 DB write,
  로그·운영 이중화.
- seed 경로는 배치의 Kafka `SettlementCreated` 이벤트 발행으로 확정 — 배치는 셀러 DB 에
  접근할 수 없고(직접 DB 는 어드민 전용 예외), 컨슈머 멱등 + 배치 flush 아웃박스로 보강한다.
