# 어드민 모듈 분리 설계 (파이널)

정산 서비스 한 프로세스에 지금 세 가지가 섞여 있다 — 정산 배치(스케줄러 포함), 어드민
페이지용 API, 판매자용 API. 파이널에서 어드민 페이지 전용 모듈(admin-service)이 생기며,
이 중 **어드민 API 만 admin-service 로 이관**한다. 이 문서는 무엇이 옮겨가고 무엇이
남는지, 두 서비스가 어떻게 통신하는지를 정한다.

> admin-service 는 전사 어드민 모듈이다. 추후 다른 도메인(유저·상품·주문) 어드민도
> 이 모듈로 모이지만, 여기서는 정산 몫만 다룬다.
> 어드민이 정산 데이터를 직접 DB 로 읽지 않고 gRPC 로 조회하기로 한 결정 배경은
> `../trade-offs/admin-data-access.md` 를 본다.

## 목표 구조

```
admin-service (신설)
 ├─ 어드민 API (REST — 어드민 프론트가 호출)
 │   ├─ 정산 조회 (목록·요약·상세)
 │   ├─ 정산 상태 변경 (승인·지급 등)
 │   └─ 배치 수동 실행 · 잡 상태 조회
 └─ ──gRPC──▶ settlement-service

settlement-service (유지)
 ├─ Spring Batch 잡 본체 + 스케줄러(@Scheduled 자동 정산)
 ├─ 판매자용 API (REST — SellerSettlementController)
 └─ 어드민용 gRPC 서버 (신설 — 기존 유스케이스 재사용)
```

- **조회·상태변경·배치실행 전부 admin → settlement gRPC 단일 경로다.** 어드민은 정산
  DB 에 커넥션을 맺지 않는다.
- 어드민 프론트 → admin-service 는 REST, admin-service → settlement 는 gRPC. 밖으로는
  REST·안으로는 gRPC 라는 기존 구분(`../trade-offs/internal-sync-transport.md`) 그대로다.

## 무엇이 가고, 무엇이 남는가

| 대상 | 현재 위치 | 분리 후 |
| --- | --- | --- |
| `SettlementController` (어드민 정산 조회·상태변경) | settlement `presentation` | **admin-service 로 이관** |
| `SettlementBatchController` (배치 수동 실행·잡 상태) | settlement `presentation` | **admin-service 로 이관** |
| `SellerSettlementController` (판매자용 조회·지급요청) | settlement `presentation` | 잔류 — 판매자 API 는 정산 소유 |
| Spring Batch 잡 (Job/Step/Reader/…) | settlement `infrastructure/batch` | 잔류 — 배치는 정산 도메인 로직 |
| `SettlementBatchScheduler` (@Scheduled 자동 정산) | settlement `infrastructure/batch/scheduler` | 잔류 — 잡과 같은 프로세스에 있어야 함 |
| 어드민용 gRPC 서버 | 없음 | **settlement 에 신설** |

스케줄러가 남는 이유: 자동 정산은 어드민 페이지 기능이 아니라 정산의 자체 운영 주기다.
잡 본체와 같은 프로세스에 있어야 `JobLauncher` 를 직접 부를 수 있고, 어드민이 죽어도
월 정산은 돌아야 한다.

## 통신 계약

settlement 가 어드민용 gRPC 서비스를 제공하고, admin-service 가 블로킹 스텁으로
호출한다. rpc 는 이관되는 두 컨트롤러의 엔드포인트에 대응한다.

| rpc (안) | 대응하는 기존 엔드포인트 | 성격 |
| --- | --- | --- |
| 정산 목록·요약·상세 조회 | `SettlementController` GET 계열 | 조회 |
| 정산 상태 변경 | `SettlementController` PATCH | 명령 — 도메인 상태 전이 |
| 배치 실행 | `SettlementBatchController` POST | 명령 — `RunSettlementBatchUseCase` |
| 잡 상태 조회 | `SettlementBatchController` GET | 조회 |

- proto 는 정산이 정의해 제안한다(기존 User·Product 조회 proto 와 같은 방식).
  필드 단위 계약은 `integration-catalog.md` 의 어드민 연동 절에 정리한다.
- 상태 변경·배치 실행이 gRPC 를 타므로 **도메인 규칙(상태 전이 불변식)과 잡 실행은
  계속 정산이 보장**한다. 어드민은 결과만 받는다.

## 계층 구현 그림 (settlement 쪽)

gRPC 서버는 REST 컨트롤러와 같은 "인바운드 어댑터"다. 기존 유스케이스(인바운드 포트)를
그대로 재사용하고, presentation 에 gRPC 어댑터 패키지를 추가한다.

```
presentation/grpc/SettlementAdminGrpcService   ──▶  application/usecase/SettlementUseCase
                                               ──▶  application/usecase/RunSettlementBatchUseCase
                                               ──▶  application/usecase/GetSettlementJobStatusUseCase
```

비즈니스 로직은 한 줄도 안 옮긴다 — 옮겨가는 건 HTTP 겉껍데기(어드민 REST)뿐이고,
정산 쪽에는 같은 유스케이스를 부르는 gRPC 겉껍데기가 하나 늘어난다.

## 이행 단계

1. **문서 반영(지금)** — 이 문서 + trade-off + 연동 카탈로그.
2. **gRPC 계약 확정** — proto 정의, admin-service 모듈 뼈대 생성.
3. **API 이관** — settlement 에 gRPC 서버 구현, admin-service 에 어드민 REST + gRPC
   클라이언트 구현. 이 기간엔 정산의 어드민 REST 와 병행 운영.
4. **정리** — 어드민 프론트가 admin-service 로 전환 완료되면 settlement 의
   `SettlementController`·`SettlementBatchController` 제거.

## 관련 문서

- 접근 방식 결정(직접 DB vs gRPC): `../trade-offs/admin-data-access.md`
- 내부 동기 호출 전송 결정(REST vs gRPC): `../trade-offs/internal-sync-transport.md`
- gRPC 계약 상세: `integration-catalog.md`
- 파이널 전체 로드맵: `../final-roadmap.md`
