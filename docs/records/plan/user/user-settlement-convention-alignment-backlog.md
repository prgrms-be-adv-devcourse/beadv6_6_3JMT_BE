# user-service 컨벤션 정렬 백로그

`user-service`를 `settlement-service`와 같은 클린아키텍처·컨벤션으로 맞추는 작업 중, **이번(#236)에
처리하지 않고 나중으로 미룬 항목**을 기록한다. 기준 컨벤션은 `user-service/.claude/rules/` (settlement과 동일).

> 이미 완료된 것: #236에서 `sellersettlement`(셀러 정산) 패키지 코드·문서 정렬 완료. 이 문서는 **남은 것**만 다룬다.

## A. `seller` 패키지 정렬 (정산 외 셀러 등록·조회) — 우선 진행 대상

`seller` 패키지는 셀러 등록/조회를 담당하며, `sellersettlement`와 같은 이탈이 남아 있다.

| # | 현재 | settlement 기준 | 대상 |
| --- | --- | --- | --- |
| A-1 | `seller/presentation/controller/dto/{request,response}/` | `seller/presentation/dto/{request,response}/` | `SellerRegisterRequest`, `SellerRegisterResponse` |
| A-2 | 컨트롤러 반환 타입이 모듈 내에서 혼재(`ResponseEntity<ApiResult<T>>`) | `ApiResult<T>` 직접 반환 | `SellerController` |

## B. user 모듈 전역 표준이라 보류 — 모듈 차원 결정 필요

아래는 `sellersettlement`뿐 아니라 user 모듈 전체(auth·user·seller·wishlist)가 공유하는 표준이라,
바꾸려면 모듈 전역 리팩토링이 필요하다. settlement 컨벤션과 원리적으로 상충하지만 **단일 기능만 바꾸면
오히려 모듈 내부 일관성이 깨진다.** 모듈 차원에서 방향을 정한 뒤 진행한다.

| # | 현재(user 전역) | settlement 기준 | 비고 |
| --- | --- | --- | --- |
| B-1 | 도메인 예외 = `BusinessException`+`UserErrorCode`(=`ErrorCode` 의존) | domain은 순수 예외(`extends RuntimeException`), 핸들러가 `ErrorCode` 매핑 | user 예외 11개 전부 이 방식 |
| B-2 | 엔티티가 `createdAt`/`updatedAt` 직접 선언(`@EntityListeners`) | 공통 `BaseEntity` 상속 | user엔 BaseEntity 자체가 없음 |
| B-3 | `@RequestMapping("/api/v1/...")` 하드코딩 | `${api.init}` 프로퍼티 주입 | user 전 컨트롤러가 하드코딩 |

## 참고
- 완료 내역·정렬 기준: `user-service/.claude/rules/`, `user-service/CLAUDE.md`
- 관련 브랜치: `feat/#236-user-seller-settlement`
