# product-service 품질 고도화 — 리팩토링 로드맵

작성일: 2026-08-05 · 기준 커밋: `develop 64de86be`

한 줄 목적: **기능은 다 되는데 구조가 밀린 지점 9곳을 이슈 단위로 끊어서 순서대로 갚는다.**

이 문서는 "무엇을 왜 고칠지"의 공유 기록이다. 각 항목은 별도 이슈 + 브랜치 + PR로 진행하며,
이슈가 생성되면 아래 표의 `이슈` 칸을 채운다. `#411`은 이미 열려 있어 재사용한다.

---

## 왜 지금인가

product-service는 PR 58건이 전부 머지돼 기능은 완성 단계다. 그런데 고정 루브릭으로 코드
품질을 재측정해보니 **2026-07-29 대비 나빠져 있었다.**

| 정량 지표 | 2026-07-29 | 현재 |
| --- | --- | --- |
| 비대 클래스 | 4 | **6** |
| 긴 메서드 | 0 | 0 |
| 중복률 | 1.85% | 1.62% |
| LOC / 파일 수 | 4,726 / 110 | 5,014 / 112 |

새로 비대 판정을 받은 둘은 `ProductSellerService`(242 LOC·**17메서드**)와
`ProductJpaRepository`(**307 LOC**)다. 여기에 이전 점검에서 못 잡았던 위반 4건을 이번에
코드에서 직접 확인했다(I-2·I-7에 편입).

**PR·이슈 이력을 보면 결함이 세 갈래로 반복된다.** 이 로드맵은 그 뿌리를 겨냥한다.

| 반복 패턴 | 과거 이슈 | 뿌리 | 대응 |
| --- | --- | --- | --- |
| 검색 파이프라인 사고 | #548 #553 #645 #689 | 색인·재대사 실패가 조용히 지나감 | **I-2** |
| 응답 조립 누락 | #441 #482 #690 | 응답 조립 코드가 여러 계층에 흩어짐 | **I-3 · I-8** |
| 계약 정합 오류 | #496 #323 #509 | — | 이번 범위 아님 |

---

## 측정 기준

고정 루브릭 14개 카테고리(컨트롤러 위임, 엔티티 캡슐화, HTTP 의미, 로그, 예외, 레이어 분리,
미사용 코드, 의미론적 중복, 진단성, 서비스 간 통신 견고성, 서비스 경계, 값객체 설계, 다형성
기회, 응집도·결합도)로 위반을 세고, 아래 산식으로 점수를 낸다.

```
score = 100 − 5×High − 2×Medium − 0.5×Low
            − 비대클래스 − 긴메서드 − max(0, ceil(중복률 − 3))
```

비대 기준은 **300 LOC 초과 또는 메서드 15개 초과**, 긴 메서드는 50 LOC 초과다. 각 항목의
`점수` 칸은 이 산식 기준 예상 회복분이며, **우선순위를 정하는 신호로만 쓴다.** 점수를 올리려고
구조를 억지로 비트는 일은 하지 않는다(→ "고치지 않기로 한 것" 참고).

---

## 진행 순서

빠르고 위험이 낮은 것부터, 계약을 건드리는 큰 것을 마지막에 둔다.

| 차수 | 항목 | 회복 | 성격 |
| --- | --- | --- | --- |
| **1차** | I-1 · I-2 | +16 | 실패가 조용히 묻히는 곳 — 운영 리스크 |
| **2차** | I-3 · I-4 · I-5 · I-8 · I-9 | +19.5 | 정리 — 파일이 안 겹쳐 병렬 가능 |
| **3차** | I-7 | +4 | 검색/공개 조회 경계 분리·쿼리 개선 |

### Codex 설계 → Claude 구현 → Codex Publish 인계 절차

현재 단계에서는 이슈·브랜치·소스 코드를 만들지 않는다. Codex가 이 문서에 각 PR의 확정 계약, 변경
경계, 기존 정상 동작 기준, 테스트, 이슈 본문 초안을 순서대로 완성한다. 모든 PR 인계가 끝나면 Claude가
저장소 규칙과 해당 스킬을 다시 읽고 다음 절차를 PR 하나씩 반복한다.

```text
1. 확정된 PR 인계에서 GitHub 이슈 생성
2. 생성된 이슈 번호로 작업 브랜치 생성
3. 변경 전 BE 테스트·FE lint/build baseline 기록
4. `$ponytail`과 `$write-readable-code`로 해당 이슈 범위만 구현
5. 관련 단위·통합 테스트와 FE 화면 회귀 수행
6. 전체 diff 코드 리뷰, 발견 결함 수정, 영향받은 검증만 재실행
7. 목적별 커밋 후 커밋된 HEAD에 `$verify-project-changes` 최종 게이트 수행
8. 같은 HEAD에서 이미 성공한 명령·결과는 PR 생성 단계에서 재사용하고 push·PR 생성
9. CI와 기존 CodeFlow Receipt 확인
10. 실제 결과를 이 로드맵에 환류하고 다음 PR 진행
11. 게시할 근거가 있으면 `$publish`로 중복 없는 게시 계획만 먼저 제안
```

- 이슈 생성은 로드맵 전체가 끝난 뒤 한꺼번에 모두 생성하지 않고, **구현할 PR 차례가 왔을 때 하나씩**
  수행한다. 뒤 PR의 설계가 앞 PR 구현 결과에 의해 달라질 수 있기 때문이다.
- Claude는 이슈 생성·브랜치 생성·검증·PR 생성 시 저장소 `AGENTS.md`와 해당 프로젝트 스킬을 사용한다.
  구현과 구현 후 Quality 측정도 Claude가 맡는다.
- 검증 재사용은 commit SHA, 작업 트리, 검증 명령이 모두 같을 때만 허용한다. 하나라도 달라지면
  영향받은 검증을 다시 실행한다. 기존 baseline 실패는 변경 파일 검사와 분리해 기록하고,
  동일한 기존 실패만으로 PR 생성을 반복 차단하지 않는다.
- Codex는 구현 전에 전체 흐름을 코드·테스트·FE와 대조해 이 로드맵과 Publish 초안을 완성한다. Claude의
  PR이 끝난 뒤에는 실제 diff·테스트·Quality·CodeFlow 결과를 다시 검토해 설계와 달라진 점을 로드맵에
  반영하고, 개선이 확인된 내용만 Review/Decision/Study/트러블슈팅 Publish 소재로 확정한다.
- 구현 중 설계 변경이 필요하면 코드에서 임의로 확장하지 않는다. 이유·대안·테스트 영향을 이 문서에
  먼저 환류해 합의한 뒤 구현을 계속한다. 세션이 바뀌어도 이 문서를 단일 인계 기준으로 사용한다.
- 최종 Publish는 모든 PR 구현과 Claude Quality가 끝난 후 Codex가 검증된 수치와 PR 링크로 초안을
  교체해 완성한다. 현재 문구는 구현 전 가설과 학습 기록이며 완료 성과로 표현하지 않는다.

구현 PR 순서는 다음으로 고정한다. 반려본 재편집은 자동 판정의 상태 분기를 단순하게 만드는 선행 수정이므로
둘을 한 PR에 합치지 않는다.

| 순서 | 구현 단위 |
| --- | --- |
| PR 1 | 파일 업로드 계약·승격 책임(I-4 + I-8) |
| PR 2 | 판매 후 REJECTED major row 동일 version 재편집 |
| PR 3 | 상품 수정 MAJOR/PATCH 자동 판정과 동시 version 방어 |
| PR 4 | Kafka 검수 요청 발행 관측·최소 재발행(I-1) |
| PR 5 | ES scheduler-only 정합성(I-2) |
| PR 6 | 상품 조회 가독성·집계 성능(I-7) |
| PR 7 | 독립 소규모 정리(I-3 + I-5 + I-9) |
| PR 8 | PR4 품질 재검증에서 드러난 잔여 부채 정리(I-10) |

---

## 항목

| ID | 제목 | 타입 | 점수 | 이슈 |
| --- | --- | --- | --- | --- |
| I-1 | Kafka 발행 실패가 조용히 사라진다 | `fix` | +7 | #722 |
| I-2 | ES 색인이 실패를 숨긴다 | `fix` | +9 | #729 |
| I-3 | 응답에 값이 절대 안 들어가는 필드 3개 | `refactor` | +6 | |
| I-4 | 업로드 정책이 컨트롤러에 있고 S3 키 파싱이 중복 | `refactor` | +6 | |
| I-5 | ProductType별 본문 해석이 3곳에 흩어짐 | `refactor` | +2 | |
| I-6 | Kafka 컨슈머 파싱 3중복 + Jackson 버전 혼재 | `작업 취소` | 0 | scheduler-only 전환으로 자연 해소 |
| I-7 | ProductQueryService 가독성 + 집계 쿼리 개선 | `refactor` | +4 | **#411 재범위화** |
| I-8 | ProductSellerService 슬림화 + 응답 DTO의 S3 직접 호출 제거 | `refactor` | +3 | |
| I-9 | 잔여 소소한 정리 | `chore` | +2.5 | |
| I-10 | PR4 품질 재검증 잔여 부채 — 예외 규율·타임아웃·응답 DTO 값객체 | `refactor` | +18 | #738 |
| I-11 | 검색어+정렬(rating·price-asc) 조합에서 결과 소실 — 하이브리드 후보 확장이 popular 정렬에만 적용됨 | `fix` | 채점 외(기능 결함) | #733 |

---

### I-1 · Kafka 발행 실패가 조용히 사라진다 — `fix` — +7

| 위반 | 심각도 | 위치 |
| --- | --- | --- |
| `kafkaTemplate.send()` 반환 `CompletableFuture` 미확인 | **High** | `ProductEventProducer.java:86,90` |
| 발행 어댑터를 포트 없이 구체 클래스로 주입 | Medium | `ProductSellerService.java:14,40` |

`send()`가 돌려주는 future를 아무도 안 본다. AFTER_COMMIT 콜백 경로와 즉시 발행 경로 둘 다
같다. 전송이 비동기로 실패해도 **로그도 재시도도 없이** 이벤트가 사라진다. 가격 변경·판매중단
이벤트가 유실되면 ES 색인이 조용히 stale해지고, 그게 검색 결과로 나갈 때까지 아무도 모른다.

- `.whenComplete((r, ex) -> { if (ex != null) log.error(...) })`를 붙여 최소한
  `topic`·`eventType`·`aggregateId`를 남긴다.
- 리포지토리 3종(Product/Review/ProcessedEvent)은 전부 `domain/repository` 포트 +
  `infra/persistence` 어댑터 구조인데 **이벤트 발행만 이 패턴에서 빠져 있다.**
  `ProductEventPublisher` 포트를 `application/usecase`에 두고
  (`.claude/rules/clean-architecture.md` §4 — 내부 기술 인프라 아웃바운드 포트)
  `ProductEventProducer`가 구현하게 한다.

**검증**: 발행 실패를 주입해 `log.error`가 찍히는지. 포트로 바꾼 뒤 기존 발행 5종이 그대로 도는지.

#### product-service만 변경하는 검수 요청 유실 최소 방어

ai-service 이벤트 계약은 이번 작업 범위가 아니므로 product-service 단독으로 다음까지만 보완한다.

1. `PRODUCT_REVIEW_REQUESTED`를 포함한 `ProductEventProducer`의 send future 완료 여부를 관측하고,
   실패 시 payload 본문을 제외한 `eventId`·`eventType`·`productId`·예외를 구조화 로그로 남긴다.
2. `PENDING_REVIEW` 진입 시각과 자동 재발행 횟수를 product-service DB에 기록한다.
3. 설정 가능한 대기 시간을 넘긴 `PENDING_REVIEW` 중 아직 자동 재발행하지 않은 상품만 scheduler가
   한 번 다시 발행한다. product row에서 검수 스냅샷을 다시 조립하므로 별도 이벤트 본문 저장소와
   Outbox는 만들지 않는다.
4. 재발행도 실패하거나 재발행 후에도 계속 대기하는 상품은 반복 OpenAI 호출을 막기 위해 더 이상
   자동 발행하지 않고 오류 로그와 운영 확인 대상으로 남긴다.

이 최소안은 product-service의 최초 Kafka 발행 실패와 ai-service 결과 발행 유실로 상품이 영구히
대기하는 상황을 한 번 복구할 기회를 준다. 정상 AI 검수가 오래 걸리는 중복 호출을 피하도록 대기
시간은 설정값으로 두며, 실제 값은 운영 검수 소요 시간을 확인해 정한다. `updatedAt`을 재시도 시각으로
재사용하면 ES 재조정 대상까지 불필요하게 바뀌므로 검수용 시각·횟수를 별도 필드로 둔다.

`product_processed_event`를 이 흐름에 추가하는 것은 이번 최소안에 포함하지 않는다. 같은 검수 결과의
즉시 재전달은 현재 PENDING_REVIEW 상태 가드로 무해하지만, 서로 다른 검수 회차의 오래된 결과를
구분하는 문제는 eventId 저장만으로 해결되지 않기 때문이다.

#### PR 4 구현 인계 — Kafka 검수 요청 발행 관측·최소 재발행(I-1)

##### 이 PR이 해결하는 실제 장애 구간

현재 `ProductEventProducer`는 활성 트랜잭션이 있으면 `afterCommit()`에서, 없으면 즉시
`kafkaTemplate.send()`를 호출한다. 따라서 DB rollback 시 이벤트를 보내지 않는 것은 보장하지만,
DB commit 후 broker 전송이 비동기로 실패하는 경우 반환 future를 버려 실패 사실조차 남지 않는다.
ai-service의 consumer retry는 **broker에 도착한 뒤 소비에 실패한 이벤트**만 다시 처리하므로 이 구간을
복구할 수 없다.

이 PR은 완전한 exactly-once 전달을 만들지 않는다. 다음 두 단계의 최소 방어만 제공한다.

1. 모든 product event의 비동기 전송 성공·실패를 관측한다.
2. 검수 결과가 오지 않아 오래 `PENDING_REVIEW`에 머문 상품에 한해 검수 요청을 최대 1회 재발행한다.

로그에 남은 `eventId`나 `productId`를 사람이 그대로 다시 전송하는 API는 만들지 않는다. 로그는 원인
추적 근거이고, 실제 복구 입력은 DB의 현재 product snapshot이다. 임의 eventId 수동 재사용은 ai-service가
검수 회차를 식별하는 계약도 없는 현재 구조에서 더 강한 멱등성을 제공하지 않는다.

##### 데이터 모델과 상태 규칙

`product`에 다음 검수 요청 전용 필드를 추가한다. PR 3 migration이 `V10`을 사용한다는 전제에서 이 PR은
`V11__add_product_review_request_retry_metadata.sql`을 사용하고, 실제 구현 순서가 달라지면 번호만 충돌
없이 조정한다.

| 필드 | DB | 의미 |
| --- | --- | --- |
| `reviewRequestedAt` | `review_requested_at timestamp null` | 현재 검수 회차가 시작된 시각 |
| `reviewRequestRetryCount` | `review_request_retry_count integer not null default 0` | 현재 검수 회차의 자동 재발행 횟수 |

- `DRAFT` 또는 `REJECTED`에서 검수를 요청하거나, 판매 중 상품의 MAJOR 새 row가 `PENDING_REVIEW`로
  만들어질 때 `startReviewRequest(now)`가 시각을 기록하고 횟수를 0으로 초기화한다.
- 단순 상품 수정 시각인 `updatedAt`은 사용하지 않는다. 검수 재발행 때문에 ES 증분 대상이 바뀌는 부작용과
  일반 수정/검수 대기 시작의 의미 혼합을 피한다.
- 승인·반려 시 이 필드는 과거 검수의 운영 근거로 그대로 보존한다. 다음 검수 회차가 시작되면 다시
  덮어쓴다.
- `markReviewRequestRetried()`는 `PENDING_REVIEW && retryCount == 0`일 때만 1로 바꾼다. 두 번째 자동
  재발행은 허용하지 않는다.

##### 발행 포트와 완료 관측

- `application/usecase/ProductEventPublisher`를 만들고 application service와 재발행 service는 이 포트에만
  의존한다. 기존 `ProductEventProducer`는 `infra/messaging/producer`에 남아 포트를 구현한다.
- 기존 공개 메서드의 이벤트 envelope·topic·Kafka key·payload 계약은 바꾸지 않는다. PR 5에서 삭제하기로
  한 검색용 이벤트 메서드 정리는 이 PR에 섞지 않는다.
- `EventMessage`를 만든 뒤 send future에 완료 callback을 연결한다.
  - 성공: debug 수준으로 `eventId`, `eventType`, `aggregateId`, topic, partition, offset을 기록한다.
  - 실패: error 수준으로 `eventId`, `eventType`, `aggregateId`, topic과 예외를 기록한다.
  - prompt 본문, presigned URL, payload 전체는 로그에 남기지 않는다.
- callback 연결은 즉시 발행 경로와 `afterCommit` 경로가 반드시 같은 private send 메서드를 공유하게 한다.
- producer 내부 retry 설정에 기대어 성공했다고 간주하지 않는다. future가 최종 실패했을 때 위 로그가
  남아야 한다.

##### 오래 대기한 검수의 1회 재발행

구성은 Config Server의 `product-service.yml` 아래에 둔다.

```yaml
prompthub:
  product:
    review-request-retry:
      fixed-delay-ms: 60000
      stale-after: 10m
      batch-size: 50
```

`stale-after: 10m`은 설계 기본값일 뿐이다. 운영 AI 검수 지연 분포를 확인해 조정하며, 정상 처리 중인
요청을 너무 빨리 중복 호출하지 않도록 fixed delay보다 충분히 크게 둔다.

구조는 다음으로 고정한다.

```text
product/application/service/ProductReviewRequestRetryService
product/infra/batch/ProductReviewRequestRetryScheduler
```

1. scheduler는 `now - staleAfter` 이전에 검수를 요청했고, `PENDING_REVIEW`, retry count 0,
   `deletedAt is null`인 ID를 `batchSize`만큼 조회한다.
2. service는 각 ID를 짧은 개별 트랜잭션에서 다시 확인하고 retry count를 1로 선점한 뒤 저장한다.
   상태나 횟수가 이미 달라졌으면 건너뛴다.
3. 같은 트랜잭션에서 기존 검수 snapshot 조립 로직을 재사용해 발행을 등록한다. 실제 Kafka send는 commit
   후 실행된다.
4. 재발행도 최종 실패하면 producer error 로그가 남고 count가 이미 1이므로 자동 반복하지 않는다.
5. 재발행 후에도 계속 `PENDING_REVIEW`인 상품은 다음 tick부터 후보에서 제외하며, 운영 확인 대상으로
   error 또는 warn 로그를 한 번 남길 수는 있지만 매 tick 같은 로그를 반복하지 않는다.

조회 후 상태가 바뀌는 경쟁 조건을 막기 위해 repository에는 조건부 선점 연산을 둔다. 구현은
`UPDATE ... WHERE id = :id AND status = 'PENDING_REVIEW' AND review_request_retry_count = 0 AND
review_requested_at <= :cutoff AND deleted_at IS NULL`처럼 영향 row 수 1일 때만 발행하는 방식을 우선한다.
단순 조회 후 엔티티 값만 바꾸는 방식은 파드가 2개가 되었을 때 같은 상품을 둘 다 재발행할 수 있으므로
사용하지 않는다. 이 조건부 update는 이 작업에 필요한 좁은 동시성 방어이며 별도 분산 lock은 필요 없다.

##### 재사용해야 하는 검수 snapshot 조립

현재 `ProductSellerService.publishReviewRequestedEvent()` 안에는 중복 상품 조회, thumbnail/gallery presign,
payload 조립이 함께 있다. 최초 발행과 scheduler 재발행이 같은 계약을 사용하도록 이를
`ProductReviewRequestPublisher` 같은 application service로 추출한다. 이 클래스는 이름과 달리 Kafka 구현을
직접 알지 않고 `ProductEventPublisher`, `ProductRepository`, PR 1의 object storage port를 조합한다.
`ProductSellerService`와 retry service가 이 한 경로를 호출한다.

presigned GET URL은 재발행 시점에 DB object key로 **새로 발급**한다. 오래된 이벤트를 보관했다가 수동
재생하는 기능은 만들지 않으므로, 최초 이벤트에 들어 있던 URL 만료 문제는 이번 설계의 장애 조건이 아니다.

##### 정확히 보장하는 범위와 남는 한계

| 상황 | 이 PR 이후 결과 |
| --- | --- |
| DB rollback | `afterCommit`이 실행되지 않아 발행하지 않음 |
| DB commit 후 최초 send 실패 | 구조화 error 로그 + stale 탐지 후 1회 재발행 기회 |
| 최초 send 성공, ai-service 처리/결과 발행 유실 | stale 탐지 후 1회 재발행 기회 |
| 정상 AI 검수가 10분보다 오래 걸림 | 최대 1회 중복 검수 가능; threshold 운영 조정 필요 |
| 선점 commit 후 프로세스 종료, send 호출 전 종료 | Outbox가 없으므로 복구 보장하지 못함 |
| 재발행까지 실패 | 로그와 retry count 1이 남고 자동 반복하지 않음 |
| 서로 다른 검수 회차의 늦은 결과 | product-service 단독으로 완전 해결하지 않음 |

따라서 “발행 실패를 로그와 scheduler로 발견·완화한다”가 정확한 표현이고 “Kafka 이벤트 전달을
보장한다”라고 표현하지 않는다. 완전한 전달 보장이 필요해지는 시점의 Transactional Outbox와,
ai-service의 `inspectionRequestId`/inbox 멱등성은 별도 후속 이슈다.

##### 변경 파일 경계

예상 변경은 다음 product-service 및 해당 Config Server 설정에 한정한다.

- `Product.java`: 검수 요청 메타데이터와 상태 행위
- `ProductRepository.java`, `ProductJpaRepository.java`, `ProductRepositoryAdapter.java`: stale 후보 조회와
  조건부 1회 선점
- `ProductEventPublisher.java`, `ProductEventProducer.java`: 포트와 send future 관측
- `ProductReviewRequestPublisher.java`: 최초/재발행 snapshot 조립 단일화
- `ProductReviewRequestRetryService.java`, `ProductReviewRequestRetryScheduler.java`: 1회 복구 orchestration
- `V11__add_product_review_request_retry_metadata.sql`
- `config/src/main/resources/configs/product-service.yml`
- 대응 단위·repository·scheduler 테스트

ai-service, Kafka payload schema, topic 이름, API, FE는 변경하지 않는다. 사용자 화면 계약이 바뀌지 않으므로
FE 코드 변경은 없지만, 회귀 확인에서 검수 요청 후 대기/승인·반려 화면 흐름은 확인한다.

##### 필수 테스트와 완료 조건

- producer 즉시 경로 send 성공/실패 callback 로그 필드 검증
- 활성 트랜잭션에서는 commit 전 send 0회, commit 후 1회; rollback 후 0회
- 최초 검수 요청과 MAJOR 생성 시 requestedAt 설정, retry count 0
- stale 이전/다른 상태/이미 retry 1/deleted 상품 제외
- stale 후보를 조건부 선점한 단 한 실행만 발행하고 count 1 저장
- 두 실행이 같은 ID를 경쟁해도 발행은 총 1회
- 재발행 snapshot이 최초 발행과 같은 payload 계약을 사용하고 presigned URL은 새로 생성
- 재발행 send 실패 시 error 로그를 남기되 다음 scheduler tick에서 다시 발행하지 않음
- `:product-service:test`, `git diff --check`, 적용 규칙 기반 diff 검증 통과
- 기존 baseline 실패는 별도 기록하고, 이 PR로 추가된 실패는 0건

##### Claude가 생성할 이슈 본문 핵심

- 문제: DB commit 뒤 Kafka 비동기 발행 실패가 관측되지 않아 검수 요청이 영구 대기할 수 있다.
- 범위: send future 구조화 로그, publisher port, 검수 요청 메타데이터, 오래된 PENDING_REVIEW 1회 조건부
  재발행, 테스트와 Config 설정.
- 제외: Outbox, 무제한 retry, 수동 event replay API, ai-service 수정, 검수 회차 correlation, API/FE 변경,
  PR 5의 검색 이벤트 삭제.
- 수용 기준: 위 필수 테스트와 보장 범위 표를 그대로 사용한다.

##### 실제 구현 결과 (2026-08-11) — `IMPLEMENTED · PR_PENDING`

이슈 #722, 브랜치 `feat/#722-kafka-inspection-request-observability-retry`(최신 `develop` 기준). 아직
commit·push·PR은 하지 않았다 — 구현·테스트만 완료된 상태.

**네이밍 차이**: 인계 초안의 `review*`(`reviewRequestedAt`·`ProductReviewRequestRetryScheduler`)를
전부 `inspection*`으로 바꿨다. 코드베이스에 이미 고객 리뷰용 `Review` 엔티티와 AI 검수용
`ProductInspectionResultHandler`/`InspectionChecklist`가 공존해, `review`를 그대로 쓰면 이름이
충돌한다. 최종: `Product.inspectionRequestedAt`/`inspectionRequestRetryCount`,
`ProductInspectionRequestPublisher`/`RetryService`/`RetryScheduler`. `PRODUCT_REVIEW_REQUESTED`
Kafka eventType 등 기존 wire 계약은 바꾸지 않았다.

**구현 범위**: 인계 문서 그대로 — `ProductEventPublisher` 포트(`application/usecase`) +
`ProductEventProducer` 구현체에 send future 완료 콜백(성공 debug/실패 error, payload 미포함) 추가.
`V11__add_product_inspection_request_retry_metadata.sql`로 `inspection_requested_at`·
`inspection_request_retry_count` 컬럼 추가. `Product.startInspectionRequest()`가
`submitForReview()`·`createNextVersion(MAJOR)`에서 새 검수 회차를 기록한다(`updatedAt` 재사용 안 함).
`ProductInspectionRequestPublisher`가 최초/재발행 snapshot 조립(중복 탐지+presign)을 단일화.
`ProductInspectionRequestRetryScheduler`(`@ConfigurationProperties` 기반
`fixed-delay-ms`/`stale-after`/`batch-size`)가 stale 후보를 조회하고, `ProductInspectionRequestRetryService`가
개별 트랜잭션에서 `ProductJpaRepository`의 조건부 UPDATE(`claimInspectionRequestRetry`)로 1회
선점 후 재발행한다.

**설계 차이 — ProductSellerService 리팩토링 동시 진행**: 구현 도중 사용자가 PR1~PR3 누적으로
`ProductSellerService`가 이미 300 LOC(304줄)를 넘었고 `updateProduct()`가 73줄(50 LOC 긴 메서드
기준 초과)이라는 점을 지적해, PR4 범위를 Kafka 기능에 더해 이 부채 해소까지 확장했다(원 인계
문서에는 없던 결정). `ProductVersionChangePolicy`(no-op·changeReason·MAJOR 중복 대기 판정)와
`ProductVersionTransitionService`(새 버전 row 생성·저장·이벤트 발행)를 분리하고, DRAFT/REJECTED
in-place 수정 중복도 `updateContentInPlace()`로 합쳤다. 결과: `ProductSellerService` 304→289줄,
메서드 수 15→15(동일, `findOriginalProductIdByContentHash` 제거+`updateContentInPlace` 추가로 상쇄),
`updateProduct()` 73→약 52줄(주석 포함)로 협력 객체 위임만 남았다. CodeFlow 배지(`.github/codeflow-card.json`)는
2026-07-15 커밋 기준으로 PR1~4 전부를 반영하지 못해 이번 비교에서 배제하고 git diff 직접 측정만 썼다.

**검증 결과**: `:product-service:test` 437개 전부 통과(신규 테스트 약 30개 포함 — Product 도메인
검수 회차 기록, ProductEventProducer 콜백 로깅(success/failure/null-future), ProductInspectionRequestPublisher,
ProductInspectionRequestRetryService, ProductVersionChangePolicy, ProductVersionTransitionService,
ProductInspectionRequestRetryScheduler 단위 테스트와 ProductJpaRepositoryTest의 실제 Postgres 기반
조건부 UPDATE 동시 선점 재현). 기존 baseline 실패는 없었다(develop 기준 이 브랜치 이전에 실패하던
테스트 없음). FE는 API·이벤트 계약을 바꾸지 않아 코드 변경이 없으므로 FE lint/build는 실행하지
않았다 — 필요해지면 회귀 확인은 검수 요청 대기/승인·반려 화면 흐름 수동 확인으로 별도 진행한다.

**남은 후속 과제(이번에 해결하지 않음)**: `Product.java`의 도메인 상태 전이 메서드 전반이 여전히
범용 `IllegalStateException`을 던진다(`domain-model.md`/`controller-exception.md` §2-4가 권장하는
전용 도메인 예외가 아님) — PR3 이전부터 있던 저장소 전반의 기존 패턴이라 이번 PR 파일 경계 밖으로
남겨둔다. Kafka 검색 이벤트 정리(PR 5)와 `ProductQueryService` 가독성(PR 6, I-7)도 그대로 계획대로 남아 있다.

---

### I-2 · ES 색인이 실패를 숨긴다 — `fix` — +9

| 위반 | 심각도 | 위치 |
| --- | --- | --- |
| `client.bulk()` 응답의 **item별 실패 미검사** | **High** | `ElasticsearchProductSearchIndexer.java:70-84` |
| alias 자리에 rogue index가 있으면 무방비 | Medium | `ProductIndexBootstrap.java:26-34` |
| `size(10000)` 고정 | Medium | `ElasticsearchProductSearchIndexer.java:53` |

**bulk 부분 실패** — bulk는 HTTP 200을 주면서 개별 item만 실패할 수 있다. 지금은 `IOException`
계열만 잡고 응답 본문은 안 본다. 즉 **문서 절반이 색인에 실패해도 "전체 재조정 완료" 로그가
찍힌다.** 응답 `errors()`와 item별 `error()`를 검사해 실패 ID·원인을 구조화 로그로 남기고,
재시도 가능한 오류만 제한적으로 재시도한다. 최종 실패는 다음 재대사 주기에서 복구되게 둔다
(무제한 재시도 금지). bulk 요청은 고정 chunk로 나눈다.

**alias 무결성** — `existsAlias(ALIAS)` 하나만 본다. `products`라는 이름의 *인덱스*가 alias
자리를 차지하면 `existsAlias`는 `false`를 주고, 이어지는 `putAlias`가 "an index exists with
the same name as the alias"로 터진다. **실제로 겪은 장애다.** alias 존재 + 대상 인덱스 이름 +
필수 mapping을 함께 검사하고, 불일치면 조용히 넘어가지 말고 명시적으로 실패시켜 복구 절차를
로그에 남긴다.

**10k 상한** — `findAllIndexedFamilyRootIds`가 `size(10000)` 고정이라 색인 문서가 1만 건을
넘으면 고아 문서 탐지가 불완전해진다. `search_after`로 전환한다.

**검증**: 일부 item만 실패하는 bulk 응답에서 실패 ID가 잡히는지 / alias 없음·rogue index·mapping
불일치 각각 / 1만 건 초과 고아 탐지.

#### PR 5 구현 인계 — Elasticsearch scheduler-only 정합성(I-2)

##### 구현 착수 전 코드 재확인 (2026-08-12)

PR4(#722)·패키지 재구성(#723) 머지 이후 develop 기준으로 다시 코드를 읽어보니, 이 문서를 처음
쓴 시점(2026-08-05) 이후 일부가 이미 다른 작업 중에 구현돼 있었다. PR5 이슈는 아래 "재작업
불필요" 항목을 제외한 실제 잔여 범위로 좁혀 만든다. 이 문서를 처음 쓴 시점의 문제 설명은 당시
판단의 기록으로 아래 그대로 두고, 지금 상태는 이 절이 최신 기준이다.

**이미 구현됨 — 재작업 불필요**

- **bulk 부분 실패 검사**: `ElasticsearchProductSearchIndexer.validateBulkResponse()`가
  `BulkResponse.errors()`와 item별 `error()`를 검사해 실패 operation·index·id·reason을 담아
  예외를 던진다. 아래 "bulk 부분 실패와 제한적 재시도" 절의 문제 설명("지금은 IOException 계열만
  잡고 응답 본문은 안 본다")은 더 이상 사실이 아니다 — 남은 실제 작업은 **chunking(기본
  500건 단위)** 과 **일시적 오류(429/502/503/504) 1회 재시도**, delete-404를 성공으로 취급하는
  처리뿐이다.
- **watermark 미전진**: `ProductReconcileScheduler.reconcile()`은 `bulkReconcile()`이 예외를
  던지면 `succeeded` 대입 이전에 예외가 전파돼 `lastSucceededAt`을 전진시키지 않는다. 명시적
  분기는 아니지만 결과적으로 요구를 만족한다. 사이클 시작 시각·소요 시간·upsert/delete 건수를
  구조화 로그로 남기는 관측성 보강만 남는다.

**여전히 필요 — 실제 잔여 범위**

- 증분 변경 감지가 soft-delete row를 제외함(`findFamilyRootIdsByProductUpdatedSince`·
  `...ReviewUpdatedSince` 둘 다 `deletedAt is null` 필터가 그대로 있음)
- 새 embedding이 같은 재조정 사이클의 ES 문서에 반영되지 않음(`ProductReindexService.buildReconciliation()`의
  기존 ponytail 주석이 이 한 사이클 지연을 이미 알고 있다고 명시)
- bulk chunking 없음(전체를 한 번에 `client.bulk()`로 전송)
- 일시적 오류 재시도 없음
- `findAllIndexedFamilyRootIds()`가 여전히 `size(10000)` 고정 — PIT/search_after 미전환
- `ProductIndexBootstrap`이 `existsAlias` 단일 검사뿐 — rogue index·mapping 불일치 무방비
- 검색용 Kafka 이벤트 4종(`PRODUCT_CHANGED`·`PRODUCT_STOPPED`·`PRODUCT_DELETED`·`PRODUCT_PRICE_CHANGED`)이
  product-service producer(`ProductEventProducer`)와 order-service 로그 전용 consumer
  (`ProductEventConsumer`)에 그대로 남아 있음

##### 목표와 작업 순서

RDB를 상품 상태의 단일 진실 공급원으로 유지하고 ES는 지연을 허용하는 검색 projection으로 운영한다.
이 PR은 다음 순서를 바꾸지 않는다.

1. polling이 상품·리뷰의 변경과 삭제를 모두 잡게 한다.
2. 새 임베딩을 같은 재대사 사이클의 ES 문서에 포함한다.
3. bulk 부분 실패가 성공으로 기록되지 않게 하고 scheduler watermark를 실패 시 전진시키지 않는다.
4. 전체 스윕의 10k 상한과 index/alias 무결성을 보완한다.
5. 위 수렴 테스트가 통과한 뒤에만 검색용 Kafka 경로와 실제 소비자가 없는 로그 전용 이벤트를 제거한다.

API URL과 검색 응답 계약, FE 화면 계약은 바꾸지 않는다. 검색 결과는 최대
`재대사 실행 시간 + fixed delay 20초 + ES refresh interval`만큼 늦을 수 있고, 상세·주문은 계속 RDB의
최신 `ON_SALE`·삭제 여부·가격을 권위 데이터로 사용한다.

##### 증분 변경 감지 보완

현재 두 변경 쿼리의 `deletedAt is null` 때문에 soft delete된 row 자체가 증분 후보에서 빠진다. 다음처럼
바꾼다.

- product 변경 쿼리: `updatedAt >= since`만으로 family root를 반환한다. 삭제된 product도
  `softDelete()`가 `updatedAt`을 함께 바꾸므로 family가 재대사되고, ON_SALE member가 없으면 ES 문서를
  삭제한다.
- review 변경 쿼리: `updatedAt >= since`인 row를 삭제 여부와 무관하게 감지하고
  `coalesce(r.product.parentId, r.product.id)`를 반환한다. child version에 review가 연결되더라도 ES 문서
  ID인 family root가 갱신된다.
- rating 집계 자체는 기존처럼 ACTIVE이고 삭제되지 않은 review만 포함한다. “변경 감지에는 삭제 row를
  포함하고, 현재 통계에는 삭제 row를 제외한다”는 두 책임을 혼동하지 않는다.
- `findChangedFamilyRootIds()`는 두 결과를 순서를 보존해 중복 제거한다.

현재 Review에는 삭제 행위/API가 없지만 DB column은 이미 존재한다. 따라서 review 삭제 감지는 새 API를
추가하는 작업이 아니라 기존 projection 동기화의 안전성을 완성하는 범위다.

##### 임베딩을 같은 사이클에 반영

현재 `ProductReindexService.buildReconciliation()`은 기존 embedding을 읽어 `FamilyUpsertInput`을 만든 뒤
`ProductEmbeddingUpdater.refresh()`를 호출한다. refresh의 native update는 `embedding`과
`embedding_source_hash`만 바꾸고 product `updatedAt`은 바꾸지 않는다. 따라서 새 vector는 “다음
20초 사이클” 대상이 되지 않을 수 있고, 실제로는 다음 일일 전체 스윕까지 ES에 반영되지 않을 수 있다.

이를 다음 순서로 고친다.

```text
현재 ON_SALE representative 결정
  -> source hash와 기존 embedding 일괄 조회
  -> hash가 바뀐 상품만 OpenAI 재임베딩 및 pgvector 갱신
  -> 기존/신규 vector를 합친 Map 반환
  -> 그 Map으로 FamilyUpsertInput 조립
  -> 같은 bulk 요청에서 ES upsert
```

`ProductEmbeddingUpdater`는 `refreshAndGet(List<Product>)`처럼 호출 결과가 드러나는 메서드로 바꾸고,
현재 사이클에 사용할 `Map<productId, float[]>`를 반환한다. 새 임베딩 생성에 실패해 `null`이면 이전
vector가 있으면 이전 것을 유지하고, 처음부터 vector가 없으면 keyword-only 문서를 허용한다. 한 상품의
embedding 실패 때문에 다른 family의 키워드 색인까지 모두 막지 않되 실패 productId는 payload 없이
로그로 남긴다.

##### bulk 부분 실패와 제한적 재시도

`ElasticsearchProductSearchIndexer.bulkReconcile()`은 `BulkResponse.errors()`와 각 item의
`error()`를 반드시 검사한다.

- 요청을 설정 가능한 고정 chunk(기본 500 operations)로 나눈다.
- 429, 502, 503, 504처럼 일시적 상태인 실패 item만 한 번 재시도한다. 성공 item과 영구 실패 item을
  무조건 다시 보내지 않는다.
- 최종 실패가 하나라도 남으면 실패 operation의 문서 ID·operation 종류·status·reason을 구조화 로그로
  남기고 `ProductSearchIndexingException`을 던진다. 문서 본문과 embedding은 로그에 남기지 않는다.
- delete 대상이 이미 없는 404는 목표 상태가 이미 충족됐으므로 성공으로 취급한다.
- `ProductReindexService`가 최종 성공 로그를 남기는 것은 모든 chunk가 성공한 뒤뿐이다.

scheduler는 사이클 시작 시각과 소요 시간, 변경 family 수, upsert/delete 수, 마지막 성공 시각을
기록한다. 예외를 잡아 실패 로그를 남기되 `lastSucceededAt`을 전진시키지 않는다. 다음 tick은 기존
watermark와 30초 overlap부터 다시 읽어 멱등 upsert/delete로 복구한다. 실패를 `boolean true`나 빈 결과로
바꾸어 숨기지 않는다.

##### 10k 상한 제거

`findAllIndexedFamilyRootIds()`의 단일 `size(10000)` 검색을 PIT(point in time) + `search_after` 순회로
바꾼다.

- `_source`는 계속 제외하고 문서 `_id`만 읽는다.
- page size 기본값은 1,000으로 둔다.
- PIT 내부의 `_shard_doc` 정렬값으로 마지막 hit 이후를 요청한다.
- 성공·실패와 무관하게 `finally`에서 PIT를 닫는다.
- 한 page가 page size보다 작을 때 종료한다.

이는 일일 전체 스윕에서 ES에만 남은 고아 문서를 모두 찾기 위한 경로다. 매 20초 증분에서는 전체 ES
ID를 읽지 않는다.

##### index/alias bootstrap 무결성

`ProductIndexBootstrap`은 단순 `existsAlias("products")` 확인을 다음 상태 검사로 바꾼다.

| 상태 | 동작 |
| --- | --- |
| alias와 `products-v1` 모두 없음 | mapping resource로 index 생성 후 alias 연결 |
| `products-v1`은 있고 alias만 없음 | 필수 mapping 검증 후 alias 연결 |
| alias가 정확히 기대 index를 가리킴 | 필수 mapping 검증 후 정상 진행 |
| `products`라는 실제 index가 alias 이름을 점유 | 자동 삭제·덮어쓰기 없이 시작 실패와 복구 안내 로그 |
| alias가 다른/여러 index를 가리킴 | 자동 재연결 없이 시작 실패 |
| 필수 field type 또는 vector dimensions 불일치 | 시작 실패 |

필수 mapping 검증은 검색과 색인에 실제 사용하는 핵심 field 및 embedding `dense_vector` dimensions를
대상으로 한다. 운영 데이터를 자동 삭제하거나 새 alias로 몰래 갈아끼우지 않는다. rogue index는 실제로
겪은 장애이므로 실패 로그에는 현재 충돌 상태와 사람이 확인할 index/alias 이름을 포함한다.

##### Kafka 검색 경로와 로그 전용 계약 제거

증분·전체 수렴 검증 뒤 다음을 한 번에 정리한다.

- product-service의 `ProductSearchEventConsumer`, `ProductSearchEventHandler` 및 전용 테스트 삭제
- `PRODUCT_CHANGED` producer method, payload, enum 값과 ProductSellerService 호출 삭제
- `PRODUCT_STOPPED`, `PRODUCT_DELETED`, `PRODUCT_PRICE_CHANGED` producer method, payload, enum 값과
  ProductSellerService 호출 삭제
- order-service에서 세 이벤트를 파싱해 로그만 남기던 consumer 분기·application handler·DTO와 전용
  테스트 삭제. 이 경로는 주문·장바구니·DB·캐시 상태를 바꾸지 않는다는 코드 대조를 이미 완료했다.
- 최종 `ProductEventType`과 `ProductEventProducer`에는 AI가 실제 소비하는
  `PRODUCT_REVIEW_REQUESTED`만 남긴다.
- `product_processed_event` 테이블과 공통 repository는 삭제하지 않는다. `ORDER_PAID`와
  `ORDER_REFUND`의 판매량 증감 멱등성에 계속 필요하다. 기존 `consumer_group=product-service-search` row는
  더 이상 읽히지 않는 과거 운영 기록으로 두며 이번 PR에서 데이터 삭제 migration을 만들지 않는다.

현재 계약을 설명하는 `docs/api-spec/product.md`, `docs/api-spec/order.md`,
`docs/architecture/event-flow.md`도 함께 최신화한다. 과거 의사결정 기록인 기존
`docs/records/plan/**` 문서는 당시 맥락 보존을 위해 일괄 수정·삭제하지 않고, 이 최종 로드맵이 변경 결정을
덮는 문서가 된다.

검색 consumer 제거로 Jackson 2를 쓰는 Kafka consumer가 사라진다. 남은 order/AI consumer는 Jackson 3,
ES client의 `JacksonJsonpMapper`는 라이브러리 제약에 따른 Jackson 2이므로 공통 parser나 mapper 교체를
추가하지 않는다.

##### 단일 파드 전제

이번 PR은 현재 Kubernetes `product-service replicas: 1`을 전제로 하며 ShedLock, leader election,
공유 checkpoint를 도입하지 않는다. API pod가 2개 이상이 되면 각 JVM의 scheduler와 메모리 watermark가
독립 실행되어 OpenAI embedding 및 ES bulk가 중복될 수 있다. scale-out 전에 다음 중 하나를 별도 이슈로
선택한다.

- 권장: API와 별도인 단일 indexing worker로 scheduler를 이동한다.
- 대안: 모든 API pod에 scheduler를 두되 DB/Redis 분산 lock과 공유 checkpoint를 사용한다.

이번 PR의 30초 overlap은 commit 가시성 경계와 짧은 시계 차이를 줄이는 장치이지 분산 실행 제어가 아니다.
애플리케이션에서 `LocalDateTime.now()`로 찍은 `updatedAt`과 DB commit 사이가 overlap보다 길거나 pod/DB
시계 차이가 30초를 넘으면 증분 누락 가능성이 남고, 재시작 시 전체 재대사와 일일 전체 스윕이 최종
안전망이다. 더 강한 SLA가 필요하면 공유 DB checkpoint 또는 Debezium CDC를 재검토한다.

##### 변경 파일 경계

```text
product-service
  product/domain/repository/ProductRepository.java
  product/infra/persistence/ProductJpaRepository.java
  product/infra/persistence/ProductRepositoryAdapter.java
  product/application/service/ProductSellerService.java
  product/infra/messaging/producer/**
  search/application/ProductReindexService.java
  search/application/ProductEmbeddingUpdater.java
  search/application/ProductSearchIndexer.java
  search/infra/batch/ProductReconcileScheduler.java
  search/infra/es/ElasticsearchProductSearchIndexer.java
  search/infra/es/ProductIndexBootstrap.java
  search/infra/messaging/ProductSearchEventConsumer.java (삭제)
  search/application/ProductSearchEventHandler.java (삭제)
  대응 테스트

order-service
  로그 전용 product event consumer/application/DTO와 대응 테스트의 세 분기 제거

docs
  api-spec/product.md
  api-spec/order.md
  architecture/event-flow.md
```

PR 4에서 `ProductEventPublisher` 포트와 producer 완료 관측을 먼저 도입하므로 PR 5는 그 실제 결과를
기준으로 사용하지 않는 이벤트 메서드만 제거한다. `PRODUCT_REVIEW_REQUESTED`의 future 관측과 재발행
경로는 유지한다.

##### 필수 테스트와 완료 조건

- product soft delete·STOPPED·승인·반려·판매 중 PATCH가 증분 후보 family root로 반환됨
- root/child review 생성·수정과 삭제 row가 올바른 family root를 반환하고 현재 평균 평점은 삭제 review 제외
- source hash 변경 시 새 embedding이 같은 reconcile bulk input에 포함되고 `updatedAt` 재감지에 기대지 않음
- embedding 실패 시 기존 vector 유지 또는 최초 keyword-only 처리와 실패 로그
- bulk HTTP 성공 안의 일부 item 실패 감지, retryable item 1회 재시도, 영구 실패 예외, delete 404 성공 처리
- bulk 최종 실패 시 scheduler watermark 불변; 다음 tick이 같은 변경을 다시 반영
- 10,001건 이상의 ES ID를 PIT/search_after로 모두 순회하고 예외 시에도 PIT close
- alias 없음, 정상 alias, 기존 정상 index+alias 없음, rogue index 이름 충돌, 잘못된 alias 대상, mapping
  불일치 bootstrap 테스트
- 검색 이벤트 제거 후 product-service가 자기 `product-events`를 검색 목적으로 구독하지 않음
- order-service에서 제거한 세 이벤트가 실제 주문 기능에 영향을 주지 않고 기존 주문 테스트 통과
- FE `/`, `/browse`, `/detail/[id]`에서 신규 판매·수정·중지 상품이 설정 지연 후 수렴하며, stale 검색
  결과를 눌렀을 때 RDB 기반 판매 불가 처리가 유지됨
- `:product-service:test`, `:order-service:test`, ES integration test, `git diff --check`, 적용 규칙 기반 diff
  검증 통과
- 기존 baseline 실패는 별도 기록하고 이 PR이 추가한 실패는 0건

##### Claude가 생성할 이슈 본문 핵심

`bulk item 실패 검사`와 `watermark 미전진`은 위 "구현 착수 전 코드 재확인" 절에서 확인한 대로
이미 되어 있으므로 아래 문제·범위에서 제외한다.

- 문제: 증분 쿼리가 삭제 row를 제외해 soft-delete·삭제 review가 재조정 대상에서 빠지고, 새
  embedding이 같은 사이클에 실리지 않아 반영이 한 사이클 늦다. bulk 요청에 chunking·일시적 오류
  재시도가 없고, 10k 초과 시 고아 문서 탐지가 불완전하며, alias bootstrap이 rogue index·mapping
  불일치에 무방비다. 실시간 Kafka와 polling 이중 경로도 책임과 멱등성 비용을 중복시킨다.
- 결정: RDB 원본 + 20초 scheduler-only eventual consistency. polling 증분 보완·임베딩 동시 반영·
  bulk chunking/재시도·10k 상한 제거·bootstrap 무결성을 먼저 완성한 뒤 검색 및 로그 전용 product
  event 4종을 제거한다.
- 제외: API URL/응답 변경, 즉시 ES refresh, Outbox, Debezium, 분산 lock, 다중 worker, 과거 processed-event
  데이터 삭제, AI 검수 이벤트 변경, 이미 구현된 bulk item 실패 검사·watermark 미전진 로직 재작성.
- 수용 기준: 위 필수 테스트와 단일 파드 전제 및 FE 수렴 확인을 그대로 사용한다.

##### 실제 구현 결과 (2026-08-12) — `IMPLEMENTED · PR_PENDING`

이슈 #729, 브랜치 `feat/#729-es-scheduler-only-consistency`(최신 develop 기준). 구현·테스트·코드
리뷰 반영이 끝나 목적별로 커밋하고 PR을 여는 단계다.

**구현 범위**: 인계 문서의 "구현 착수 전 코드 재확인"에서 남은 것으로 확인한 항목 그대로 완성했다.

1. 증분 변경 감지: `findFamilyRootIdsByProductUpdatedSince`·`...ReviewUpdatedSince`에서 `deletedAt is null`
   필터를 제거하고, review 쿼리는 `coalesce(r.product.parentId, r.product.id)`로 family root를 반환하도록
   수정했다.
2. 임베딩 동일 사이클 반영: `ProductEmbeddingUpdater.refresh()`(void)를 `refreshAndGet()`
   (`Map<UUID, float[]>` 반환)으로 바꾸고, `ProductReindexService.buildReconciliation()`이 그 결과를
   기존 저장 임베딩과 병합해 같은 사이클 upsert에 반영한다.
3. bulk chunking·재시도: `ElasticsearchProductSearchIndexer.bulkReconcile()`을 청크 단위(`bulkChunkSize`,
   기본 500)로 나눠 보내고, 429/502/503/504로 실패한 item만 원본 operation 그대로 1회 재시도한다. delete
   404는 실패로 보지 않는다. 최종 실패 item은 operation·index·id·status·reason을 구조화 로그로 남긴 뒤
   예외를 던진다.
4. 10k 상한 제거: `findAllIndexedFamilyRootIds()`를 PIT + search_after 순회로 전환했다(`orphanScanPageSize`,
   기본 1000). `finally`에서 PIT를 항상 닫는다(닫기 자체가 실패해도 warn 로그만 남기고 원래 예외를
   가리지 않는다).
5. alias/index 무결성: `ProductIndexBootstrap`이 alias 없음/있음·rogue index 점유·alias 대상 불일치·
   mapping(핵심 field 4개 + embedding dims) 불일치를 구분해, 실패 상태는 자동 복구 없이 시작을 막는다.
6. 검색용 Kafka 이벤트 4종 제거(product-service 쪽만): `PRODUCT_CHANGED`/`PRODUCT_STOPPED`/`PRODUCT_DELETED`/
   `PRODUCT_PRICE_CHANGED` producer 메서드·payload·enum 값과 `ProductSellerService`/
   `ProductVersionTransitionService` 호출을 삭제했다. `ProductSearchEventConsumer`/
   `ProductSearchEventProcessor`(인계 문서의 EventHandler)와 전용 테스트를 삭제했다. 실제 소비자가
   없어진 `ProductSearchIndexPort.upsert()`(단건 색인)도 함께 제거하고, 이를 테스트 시딩 용도로 쓰던
   통합 테스트 두 곳은 `bulkReconcile()`로 교체했다.

   **order-service 쪽은 사용자 요청으로 되돌렸다.** 원래는 로그만 남기고 주문 상태를 바꾸지 않는
   `ProductEventConsumer`·`OrderProductEventService`·이벤트 DTO 3종과 전용 Kafka consumer/listener
   container factory 빈 2개, 관련 테스트를 함께 삭제했었지만, 사용자가 "order-service는 건드리지
   말라"고 명시적으로 요청해 전부 원상복구했다. **기능적으로는 문제가 없다** — product-service가 이제
   그 4종을 발행하지 않으므로, order-service의 컨슈머는 코드는 남아 있어도 매칭되는 메시지를 더 이상
   받지 못해 사실상 비활성 상태다. 배포 순서와 무관하게 안전하다(Kafka pub/sub이라 한쪽만 먼저 배포돼도
   에러가 나지 않는다는 것을 확인했다). `docs/api-spec/order.md`·`docs/architecture/event-flow.md`도
   이 되돌림에 맞춰 다시 갱신했다.

**설계 차이**:
- 인계 문서가 `ProductSearchEventHandler`로 부른 클래스는 실제 코드에서 이미 `ProductSearchEventProcessor`로
  구현돼 있었다(PR4 이후 명명) — 삭제 대상 식별만 이름 매핑으로 조정했고 별도 설계 변경은 아니다.
- bulk 재시도 판정(`isFailure`/`isRetryable`)은 기존 `validateBulkResponse`가 package-private static
  이었던 테스트 관례를 그대로 유지해 static으로 남겼다.
- `ProductIndexBootstrap`의 mapping 검증은 "핵심 field"로 `familyRootId`·`productId`·`amount`·
  `embedding`(+dims) 4개만 본다 — 매핑 전체(20개 필드) 구조적 비교는 하지 않는다. 검색·색인에 실제
  쓰이는 필드로 범위를 좁힌 설계 의도를 그대로 따랐다.
- `ProductVersionTransitionService.transitionToNextVersion()`의 `familyRootId` 파라미터를 제거했다 —
  `publishProductChanged` 호출이 없어지며 그 값을 쓰는 곳이 사라져, 그대로 두면 미사용 파라미터가
  됐다.

**검증 결과 (2026-08-12, 코드 리뷰 반영 + order-service 되돌림 후 기준)**: `:product-service:test`
357개 전부 통과. order-service는 되돌려 변경이 없으므로 재검증 대상이 아니다(기존 상태 그대로).
`checkstyleMain`/`checkstyleTest`는 기존에도 있던 gRPC 생성 코드 경고만 남고 이번 변경으로 새로
추가된 위반은 없다. `git diff --check` 통과(공백 오류 없음, CRLF 안내만). CodeFlow 등 자동 채점은
이번 세션에서 실행하지 않았다 — 실제 점수는 CI/CodeFlow 재실행 결과로 다음 인계에서 채운다.
변경 범위는 product-service·config·docs로 한정되며, 총 40개 파일이 바뀌었다(order-service는 원상
복구돼 diff 없음).

신규 테스트가 실제로 검증하는 범위는 다음과 같다. **PIT/search_after 페이지네이션은 5건·페이지
2건으로 순회 로직 자체(마지막 hit 기준 다음 페이지 요청, 페이지가 다 차지 않으면 종료)만 증명한
것**이고, 완료 조건이 요구하는 "10,001건 이상" 규모는 이번에 실행하지 않았다 — 대용량 시드는 매번
도는 통합 테스트로는 비용이 커서, 알고리즘 검증(작은 규모)과 실제 규모 확인(운영/별도 부하 테스트)을
분리했다. 나머지는 기록과 실제 커버리지가 맞다: bulk 첫 시도 → retryable item만 원본 operation
그대로 정확히 1회 재전송 → 최종 판정(영구 실패 1건이면 예외) 흐름, PIT 조회 중 예외가 나도 `finally`에서
close가 호출되는지, `ProductReconcileScheduler`가 성공 시에만 watermark를 전진시키고 실패(예외·false
반환) 시에는 다음 tick도 같은 구간을 다시 훑는지, `ProductIndexBootstrap`의 6개 상태 분기(정상 alias·
잘못된 대상·rogue index 점유·index만 있고 alias 없음·**alias와 index 모두 없어 새로 생성**·mapping
dims 불일치) 전부를 새 테스트로 검증했다.

**FE**: API·검색 응답 계약을 바꾸지 않아 코드 변경 없음. FE lint/build는 실행하지 않았다 — 필요해지면
`/`, `/browse`, `/detail/[id]` 수렴 확인은 별도로 진행한다.

**남은 후속 과제(이번에 해결하지 않음)**: 단일 파드(`replicas: 1`) 전제이며, scale-out 전 별도 indexing
worker 분리 또는 분산 lock 도입이 필요하다(인계 문서에 이미 명시돼 있음). `ProductQueryService`
가독성(PR 6, I-7)은 그대로 계획대로 남아 있다. PIT 페이지네이션의 10,001건 이상 규모 검증은 위에서
설명한 이유로 이번 범위에 포함하지 않았다.

##### 코드 리뷰 반영 (2026-08-12, Codex 리뷰)

- **P1 — `bulkChunkSize`/`orphanScanPageSize`가 0 이하면 무한 루프·PIT 순회 오류**: `ProductReindexProperties`에
  compact constructor로 두 값 모두 1 이상 검증을 추가했다(기동 시 즉시 실패). 검증 테스트 추가.
- **P1 — 신규 테스트가 완료 조건을 실제로 다 덮지 못함**: bulk 재시도 흐름(첫 시도 → retryable만
  재전송 → 최종 판정), PIT 조회 예외 시 `finally` close, `ProductReconcileScheduler` watermark 전진·
  불변 6개 시나리오, bootstrap의 "alias·index 모두 없어 생성" 경로를 각각 새 테스트로 추가했다.
  10,001건 규모 PIT 순회는 위 "검증 결과"에 설명한 이유로 여전히 실제 규모로는 검증하지 않는다.
- **P2 — 임베딩 생성 실패가 로그 없이 사라짐**: `ProductEmbeddingUpdater.refreshAndGet()`의 null 분기에
  `log.warn(productId, ...)`을 추가하고, 로그가 실제로 남는지 검증하는 테스트를 추가했다.
- **P2 — scheduler-only 전환 후에도 product-service 자기소비용 Kafka bean이 남아 있었음**: 컨슈머
  클래스는 지웠지만 `product/infra/messaging/config/KafkaConfig.java`의
  `productEventConsumerFactory`/`productEventContainerFactory`/`productEventErrorHandler` 3개 bean과
  관련 주석을 빠뜨렸다 — 삭제했다.
- **P3 — 삭제된 실시간 색인 경로를 설명하는 주석이 남아 있었음**: `ProductReindexService`·
  `FamilyStatsResolver`의 클래스 Javadoc이 삭제된 `ProductSearchEventProcessor`를 "실시간 반영" 주체로
  계속 언급하고 있었다 — 지금은 증분 재조정 하나가 product-service 자체 변경과 admin-service발 변경을
  구분 없이 잡는다는 내용으로 다시 썼다.

##### Codex 최종 리뷰 (2026-08-12)

위 5건 반영 후 재검토 결과 **No findings**. `:product-service:test` 재검증도 통과 상태를 유지한다.
order-service는 이전 라운드에서 삭제했던 로그 전용 consumer 스택(`ProductEventConsumer`,
`OrderProductEventService`, 이벤트 DTO 3종, 전용 Kafka bean 2개)을 사용자 요청으로 전부 되돌려 이
브랜치에서 변경 없음 — product-service·config·docs만 범위로 남는다. 최종 구현 범위는 이 절 상단의
"구현 범위" 6개 항목 중 6번(검색용 Kafka 이벤트 제거)이 **product-service 쪽 producer·자기소비
정리로 한정**된다(원래 인계 문서와 코드 리뷰 반영 절 다음의 "order-service 쪽은 사용자 요청으로
되돌렸다" 문단 참고). 목적별로 6개 커밋을 만들고 PR #730(`develop` 대상)을 열었다.

##### PR #730 리뷰 후속 — 10,001건 용량 테스트와 검증 방법 자체의 결함 (2026-08-12)

PR을 연 뒤 "10,001건 상한 제거를 실제로 테스트했는지" 확인이 나와, 완료 조건이 요구하는 실제
규모를 마저 검증했다. 그 과정에서 검증 방법 자체의 결함 두 가지를 추가로 잡았다.

1. **10,001건 PIT 순회 테스트 추가**: `ElasticsearchProductSearchIndexerIntegrationTest`에
   기본 page size(1000)로 10,001개 문서를 직접 bulk 색인한 뒤 전부 순회되는지 확인하는 테스트를
   추가했다. `bulkReconcile()`은 `ProductEmbeddingUpdater`(OpenAI 호출 지점)를 거치지 않는
   경로라 임베딩 API를 부르지 않는다.
2. **Gradle 테스트 캐시 함정 발견**: 코드 리뷰 반영 직후 "357개 전부 통과"로 보고했던 결과가 실은
   `compileTestJava`가 `UP-TO-DATE`로 잘못 캐시돼 새로 추가한 테스트가 재실행되지 않은 채 이전
   결과를 재사용한 것이었다. `--rerun-tasks`로 강제 재실행하자 리뷰 반영 라운드에서 추가한 PIT
   예외 테스트가 애초부터 깨져 있었다는 게 드러났다(`OpenPointInTimeResponse`가 `id` 외에
   `shards`도 필수 필드인데 안 채움 — `IndexAliases.aliases` 때 겪은 것과 같은 종류의 실수).
   `shards`를 채워 고쳤다. **이후로는 캐시 신뢰 대신 `--rerun-tasks`로 재검증한다.**
3. **공유 ES 컨테이너 오염**: 10,001건 테스트가 정리 없이 끝나자
   `ElasticsearchProductSearchQuerierIntegrationTest`의 정렬·필터 테스트 3개가 깨졌다 — 통합
   테스트가 `ElasticsearchIntegrationTestSupport`의 static 컨테이너를 공유하는데, 그중
   `search_priceAsc` 테스트는 고유 키워드로 격리하지 않고 상위 20건만 보는 구조라 대량의 남은
   문서에 취약했다. 10,001건 테스트와, 같은 파일에서 문서를 남기고 정리 안 하던 기존 테스트
   2개(chunk 테스트, 페이지네이션 테스트) 모두 `finally`에서 색인한 문서를 지우도록 고쳐 원래
   상태로 복구했다. `--rerun-tasks`로 452개 전부 통과 재확인.
4. **임베딩 실패 시 기존 vector 유지 테스트 추가**: `ProductReindexServiceTest`에
   `refreshAndGet()`이 빈 결과를 돌려줘도(원문 해시가 안 바뀌었거나 생성 실패) 기존 저장된
   embedding이 upsert에 그대로 실리는지 검증하는 테스트를 추가했다. 나머지 "애매한" 항목
   (429 외 개별 상태 코드 테스트, bulk 호출 횟수 카운트, 죽은 Kafka bean 회귀 테스트)은 반환
   대비 확인 비용이 낮다고 판단해 이번 범위에서 스킵했다.

---

### I-3 · 응답에 값이 절대 안 들어가는 필드 3개 — `refactor` — +6

| 필드 | 실제 값 | 선언 | 채우는 코드 |
| --- | --- | --- | --- |
| `originalAmount` | 항상 `null` | `ProductListItemResponse.java:13` | `ProductQueryService.java:252,272` |
| `features` | 항상 `List.of()` | `ProductDetailResponse.java:24` | `ProductQueryService.java:157` |
| `badge` | 항상 `null` | 두 Response | 채우는 경로 없음 |

`Product.getBadge()`는 ES 색인에는 들어가지만(`ElasticsearchProductSearchIndexer.java:105`)
`ProductSearchHit`에 그 필드가 없어 **조회 응답으로 돌아올 길이 없다.** 값을 만드는 정책도
어디에도 없다.

**FE 영향 없음 — 확인 완료.** 세 필드 모두 optional 선언 + fallback이라 응답에서 빠져도
화면이 깨지지 않는다.

- `components/ui/PromptCard.tsx:27,32,74` — `if (p.originalAmount && p.originalAmount > p.amount)`
- `app/detail/[id]/page.tsx:38,45,50,316` — `const features = p.features ?? ['결제 즉시 다운로드', ...]`
- `app/mypage`·`app/reader`·`lib/orderAdapters.ts`의 `badge`는 **주문 상태**를 담는 별개 필드다 — 무관

`badge`를 `ProductSearchHit`에 추가해 살리지 않고 공개 목록·상세 응답에서 제거한다. 값을 만드는 정책이
정해진 적이 없으므로 임의의 기본값을 추가하지 않는다. DB의 legacy `product.badge` column 제거는 데이터
정리 migration이 필요한 별도 범위로 두고, 뱃지 기능이 실제로 필요해지면 정책부터 별도 이슈로 설계한다.

**검증**: 응답 JSON에서 세 필드가 빠진 뒤 `/browse`·`/detail/[id]`·`/` 렌더 확인.

---

### I-4 · 업로드 정책이 컨트롤러에 있고 S3 키 파싱이 중복 — `refactor` — +6

| 위반 | 심각도 | 위치 |
| --- | --- | --- |
| 허용 확장자·productType 분기가 컨트롤러에 | Medium | `FileUploadController.java:59-82` |
| `purpose`가 `"file"`/`"thumbnail"`/`"image"` 문자열 리터럴 | Medium | 같은 메서드 |
| `extractKey` 로직 완전 중복 | Medium | `FileUploadController.java:110-115` ↔ `ProductSellerService.java:258-263` |

`resolveContentType`이 "PPT면 pptx/ppt만, EXCEL이면 xlsx/xls만" 같은 **업무 규칙**을 컨트롤러
안에서 판단한다. 컨트롤러는 HTTP 관심사만 다뤄야 한다(`.claude/rules/controller-exception.md` §1).

`purpose`가 문자열이라 오타·누락이 컴파일 타임에 안 잡힌다. `UploadPurpose` enum으로 바꾼다.

`extractKey`(presigned URL → S3 key)는 두 파일에 **글자 그대로 같은 코드**가 있다. URL을 다시
파싱하지 않도록 presigned URL 발급 응답에 `tempObjectKey`를 포함하고 상품 등록·수정 요청도 URL 대신
이 key를 전달한다. 공용 `ObjectStorageKey` 값객체가 key 형식·temp 여부·seller 소유권·purpose를
검증하며, Controller와 SellerService는 AWS URL 문자열 구조를 직접 해석하지 않는다.

#### 2026-08-09 상품 등록 흐름 검토에서 확정한 방향

현재 `createUploadUrl`은 확장자 추출, 상품 유형별 허용 확장자 판단, 임시 S3 key 생성,
presigned PUT·GET URL 생성을 한 메서드에서 수행한다. 이름도 `key`·`uploadUrl`·`fileUrl`이라
"객체 식별자", "실제 업로드", "파일 자체 URL"을 구분하기 어렵다. 구현에서는 다음 용어를 쓴다.

| 현재 이름 | 구현 목표 이름 | 뜻 |
| --- | --- | --- |
| `key` | `tempObjectKey` | 아직 상품에 귀속되지 않은 S3 임시 객체 key |
| `uploadUrl` | `presignedPutUrl` | 브라우저가 실제 파일 바이트를 S3에 PUT할 수 있는 만료 URL |
| `fileUrl` | `presignedGetUrl` | 업로드 완료 후 미리보기에 사용할 수 있는 만료 GET URL |

`s3PutUploadUrl`·`s3GetDownloadUrl`보다 HTTP 동작과 "서명된 임시 권한"을 함께 드러내는
`presignedPutUrl`·`presignedGetUrl`을 사용한다. `UploadUrlResponse`의 공개 응답 JSON 필드도 같은
이름으로 변경하고, FE의 업로드 응답 타입과 참조부를 함께 수정한다. 실제 S3 key와 presigned URL의
차이를 설명하는 한 줄 주석은 경계 메서드에만 두고, 코드와 같은 내용을 반복하는 주석은 추가하지 않는다.

Controller는 HTTP 요청 수신과 유스케이스 호출만 담당하도록 다음 책임으로 재구성한다.

```text
FileUploadController
  -> FileUploadUseCase
     -> FileUploadPolicy (purpose·productType·확장자 조합 검증과 content type 결정)
     -> ObjectStorageGateway (임시 key 및 presigned PUT·GET URL 생성)
```

- `UploadPurpose`를 enum으로 도입해 `file`·`thumbnail`·`image` 문자열 비교를 제거한다.
- 파일명에 확장자가 없으면 JPG로 추정하지 않고 `P008 INVALID_UPLOAD_FILE_TYPE`으로 거절한다.
- `FileUploadPolicy`가 `PPT -> pptx/ppt`, `EXCEL -> xlsx/xls`, 이미지 purpose -> 이미지 확장자
  규칙을 한 곳에서 관리한다. Controller의 `resolveContentType` 중첩 분기는 제거한다.
- `X-User-Id`의 `sellerId`를 버리지 않고 임시 객체 key와 삭제 소유권 검증에 사용한다. 목표 임시
  key는 `products/temp/{sellerId}/{purpose}/{uuid}.{ext}`다.
- 읽는 순서와 선언 순서를 맞춘다. public endpoint를 먼저 두고, 같은 클래스에 남는 private 메서드는
  호출되는 순서대로 바로 아래에 배치한다. 정책 추출 후 Controller에는 확장자·MIME·key 조립 helper를
  남기지 않는 것을 목표로 한다.
- endpoint 경로는 유지한다. 응답 필드는 `uploadUrl`·`fileUrl`에서 `presignedPutUrl`·
  `presignedGetUrl`로 변경하며, BE와 FE를 같은 이슈 또는 연결된 이슈에서 함께 반영한다.

외부 연동 포트와 S3 구현의 이름·패키지는 저장소 공통 규칙에 맞춰 다음으로 확정한다. `Client`는
내부 서비스 동기 호출에 사용하는 접미사이므로 `StorageClient`를 유지하지 않는다. application 코드는
AWS 공급자가 아니라 필요한 객체 저장소 능력만 알고, S3라는 기술명은 infrastructure 구현에서 드러낸다.

```text
application/gateway/external/ObjectStorageGateway.java

infrastructure/external/s3/
  S3ObjectStorageAdapter.java
  S3Config.java
  AwsS3Properties.java
```

현재 클래스가 세 개뿐이므로 `s3/config` 하위 패키지를 추가하지 않는다. `S3Config`와
`AwsS3Properties`는 모두 S3 설정 역할이지만, 같은 S3 어댑터 경계에 함께 두는 편이 불필요한 패키지
깊이를 만들지 않는다. application 서비스의 필드명은 `objectStorage`로 사용하고, 포트 메서드는
`createPresignedPutUrl`·`createPresignedGetUrl`·`copy`·`delete`처럼 능력과 HTTP 동작을 드러낸다.

application 서비스는 구현 기술인 `s3` 패키지로 묶지 않고 사용자 기능인 `upload`로 묶는다. 업로드
관련 클래스가 독립된 변경 흐름을 이루므로 저장소의 "계층 안 기능 하위 패키지" 규칙을 적용한다.

```text
application/usecase/FileUploadUseCase.java
application/service/fileupload/
  FileUploadService.java
  FileUploadPolicy.java
  TempFilePromoter.java
application/gateway/external/
  ObjectStorageGateway.java
  ObjectStorageKey.java
```

`TempFilePromoter`는 temp key 검증과 영구 승격 순서를 조율하고, `ObjectStorageKey`는 포트 계약의
값 타입으로서 key와 URL·일반 문자열을 구분한다. `fileupload` 패키지가 이미 product 서비스의 파일
업로드 문맥을 드러내므로 클래스명에 `ProductFileUpload`을 반복하지 않는다. Controller는 구현체가 아닌
`FileUploadUseCase`를 주입받고, 구현체는 `FileUploadService`로 둔다. `application/service` 패키지가
계층을 이미 표현하므로 클래스명에서는 중복되는 `Application`을 생략한다. 이는 이 product-service
로드맵에서 합의한 이름이며 공용 스킬·공용 규칙이나 다른 서비스 이름을 변경하지 않는다. 별도
`S3Helper`나 key factory는 만들지 않는다.

**검증**:

- 확장자 없는 파일, 지원하지 않는 확장자·purpose·productType 조합은 `P008`/HTTP 400.
- `PPT + ppt/pptx`, `EXCEL + xls/xlsx`, 이미지 purpose + 허용 이미지 확장자는 기존처럼 성공.
- 대문자 확장자는 `Locale.ROOT`로 정규화한 뒤 동일하게 처리.
- 생성된 temp key에 요청한 `sellerId`와 `UploadPurpose`가 포함됨.
- 다른 판매자 소유의 temp object 삭제 요청은 거절하고, 본인 객체만 삭제 가능.
- 응답 JSON은 `tempObjectKey`·`presignedPutUrl`·`presignedGetUrl`을 반환하고 FE가 PUT·미리보기 후
  상품 등록 요청에 URL이 아닌 `tempObjectKey`를 전달. endpoint 경로는 리팩토링 전후 동일.
- `ObjectStorageKey`는 정상 temp key와 잘못된 prefix·sellerId·purpose·`null`을 각각 검증.

---

### I-5 · ProductType별 본문 해석이 3곳에 흩어짐 — `refactor` — +2

"이 상품 유형에서 본문은 어느 필드인가"를 세 곳이 각자 판단한다.

| 위치 | 하는 일 |
| --- | --- |
| `ProductContent.java:43-47` | 유형별 필수 필드 조합 검증 |
| `ProductGrpcService.java:89-93` | 구매자 산출물 결정 |
| `PurchasedProductQueryService.java:53-57` | 구매 상품 응답 조립 |

셋 다 `PROMPT → content` / `PPT,EXCEL → fileUrl` / `NOTION → externalUrl`로 같은 규칙이다.
새 유형이 생기면 **세 곳을 다 고쳐야 하고, 하나를 빠뜨려도 컴파일은 통과한다.** `ProductType`에
메서드를 두거나 작은 전략으로 묶어 한 곳만 고치게 한다.

**검증**: 4개 유형 전부에 대해 세 경로 결과가 이전과 동일한지.

---

### I-6 · Kafka 컨슈머 파싱 3중복 + Jackson 버전 혼재 — 별도 작업 취소

`EventMessage<JsonNode>` 역직렬화 try/catch가 세 컨슈머에 거의 그대로 있고, **서로 다른
Jackson 메이저 버전**을 쓴다.

| 파일 | Jackson |
| --- | --- |
| `OrderEventConsumer.java:14-16` | `tools.jackson.*` (3.x) |
| `ProductInspectionResultConsumer.java:13-15` | `tools.jackson.*` (3.x) |
| `ProductSearchEventConsumer.java:3-6` | `com.fasterxml.jackson.*` (2.x) |

최초 분석에서는 버전 혼재를 의도하지 않은 드리프트로 보고 공통 parser 도입을 고려했다. 그러나 검색
동기화를 scheduler-only로 확정하면서 Jackson 2를 쓰는 `ProductSearchEventConsumer`와 handler를 제거한다.
그러면 남는 `OrderEventConsumer`와 `ProductInspectionResultConsumer`는 모두 Jackson 3를 사용하므로
Kafka consumer의 메이저 버전 혼재가 별도 수정 없이 사라진다.

**`ElasticsearchClientConfig.java:8-10`은 그대로 둔다** — `JacksonJsonpMapper`가 Jackson 2를
요구하는 ES 클라이언트 제약이지 드리프트가 아니다.

남은 두 consumer의 `parse()` 중복은 각각 약 8줄이며, 실패 문맥과 payload 변환도 서로 다르다. 이를
줄이려고 공통 `KafkaEventParser`를 만들면 작은 중복보다 공용 infrastructure 추상화 비용이 더 크다.
따라서 parser helper를 만들지 않고 각 consumer의 얇은 private 메서드를 유지한다.

`ProductInspectionResultConsumer`가 구체 handler 대신 `ProductInspectionUseCase`에 의존하도록 바꾸고
중복 `IllegalStateException` catch를 제거하는 작업은 검수 service 구조 리팩터링에 포함한다. 안정적으로
동작하는 주문 consumer는 이번 판단 때문에 함께 고치지 않는다.

**검증**: 검색 이벤트 consumer 제거 후 컴파일과 검색 scheduler 통합 테스트, 남은 order·AI consumer의
정상·미지원 eventType·payload 매핑 실패 테스트를 유지한다.

---

### I-7 · ProductQueryService 가독성 + 집계 쿼리 개선 — `refactor` — +4 — **#411 재범위화**

`ProductQueryService`를 목록·상세·batch·조회수 service 네 개로 나누는 기존 안은 취소한다. 대신 ES
검색과 RDB fallback이라는 독립된 장애 경계를 가진 목록·자동완성만 `ProductSearchService`와
`ProductSearchUseCase`로 분리한다. 상세·추천·리뷰·wishlist/order batch는 판매 중 representative와
family 조회 정책을 공유하므로 기존 `ProductQueryService`와 `ProductQueryUseCase`에 함께 유지한다.
DTO mapper나 별도 `ProductViewService`는 만들지 않고 다음 문제만 고친다.

1. **검색 경계 분리와 읽는 순서 정리** — `ProductSearchService`는 상품 목록·자동완성, ES/RDB 응답 조립,
   검색 입력 정규화 순서로 둔다. `ProductQueryService`는 상품 상세·추천·리뷰, 목적별 ID 목록 조회,
   응답 변환 순서로 둔다. 각 묶음에는 코드를 번역하지 않고 정책만 설명하는 한 줄 주석을 둔다.
2. **private 메서드만 직관화** — `searchViaElasticsearch` → `searchProductsWithElasticsearch`,
   `searchViaRdb` → `searchProductsWithRdb`, `getOnSaleProduct` → `findCurrentOnSaleProduct`,
   `toVersionHistory` → `getPublicVersionHistory`, `toUrl(s)` → `createDownloadUrl(s)`로 바꾼다.
   공개 Controller/usecase 메서드는 대규모 rename하지 않는다.
3. **폴백 catch 좁히기** — `catch (RuntimeException)`을 제거하고 ES 연결·timeout·조회 실패를 adapter가
   `search/application/ProductSearchUnavailableException`으로 변환한 경우에만 RDB로 폴백한다.
   adapter는 ES 통신·응답 예외만 감싸고 NPE와 DTO 조립 오류는 숨기지 않는다. 자동완성도 같은
   전용 예외에서만 기존 정책대로 빈 목록을 반환한다.
4. **`getProductsByIds`의 2N 집계 제거** — 상품마다 판매량과 평점을 각각 조회해 100건이면 최대
   200번 실행되는 구조를 family ID 일괄 집계 두 번과 Map 조립으로 바꾼다. 입력 1·10·100건에서도
   `getSalesCounts(List<UUID>)` 1회와 기존 `getAverageRatings(List<UUID>)` 1회로 고정한다. 조회 결과가
   없는 family는 판매량 `0L`, 평점 `0.0`을 사용한다.
5. **조회수 처리** — 별도 `ProductViewService`는 만들지 않는다. 상세 조회의 조회수 증가 정책은
   유지하되 `incrementViewCount(UUID productId)` repository 원자적 증가 쿼리로 바꾼다. 엔티티를 읽어
   `viewCount + 1`로 다시 저장할 때 동시 요청 둘이 같은 값을 덮는 lost update를 막고, `updated_at`도
   DB 시각으로 함께 갱신한다. 한 줄 정책 주석과 동시 증가 테스트로 부수 효과를 명시한다.

#### 변경 금지 외부 API 계약

다음 URL은 합의를 거쳐 확정된 계약이므로 이번 리팩터링에서 이름·HTTP method·경로를 변경하지 않는다.

```text
GET  /api/v2/products
GET  /api/v2/products/suggest
POST /api/v2/products/wishlists
POST /api/v2/products/orders
GET  /api/v2/products/{productId}
GET  /api/v2/products/{productId}/recommends
GET  /api/v2/products/{productId}/reviews
```

현재 API 기준 문서는 `docs/api-spec/product.md` 하나만 동기화 대상으로 삼는다. 과거
`docs/records/plan/**`의 endpoint 표현은 당시 의사결정 기록이므로 이번 작업에서 삭제·갱신하지 않는다.
실제 요청·응답 계약이 바뀌는 PR만 `docs/api-spec/product.md`를 함께 수정한다.

**검증**: Controller 경로와 응답 JSON이 전후 동일한지, FE가 사용하는 wishlist/order 요청이 그대로
동작하는지, ES 전용 실패만 RDB fallback 되는지, `getProductsByIds` 입력 1·10·100건에서 집계 쿼리
수가 고정되는지 확인한다. 일반 RuntimeException은 폴백하지 않고 전파하며, 조회수 증가를 여러 번
동시에 실행해도 호출 횟수만큼 증가해야 한다.

#### PR 6 구현 인계 — 상품 조회 가독성·집계 성능(I-7)

##### 이번 PR의 구조 판단

목록·자동완성은 신규 `ProductSearchUseCase`와 `ProductSearchService`로 옮기고, 상세·추천·리뷰·batch는
기존 `ProductQueryUseCase`와 `ProductQueryService`에 유지한다. `ProductController`는 두 usecase를
주입받되 endpoint, 요청·응답 DTO와 JSON field는 바꾸지 않는다. 목록·상세·batch·조회수별 4분할,
별도 mapper와 `ProductViewService`는 추가하지 않는다.

코드 순서는 다음으로 고정한다.

```text
ProductSearchService

상수와 검색 의존성

// 상품 목록·검색: ES 우선, 검색 인프라 실패에서만 RDB fallback
getProducts
searchProductsWithElasticsearch
searchProductsWithRdb
suggest

// 검색 응답 조립과 외부 입력 정규화
toListItemResponse overloads
ObjectStorageGateway.presignIfPresent 재사용
normalizePage/Positive/Keyword/ProductType/Sort

ProductQueryService

상수와 공개 조회 의존성

// 공개 상세: 판매 중 대표본, 조회수, 추천, 리뷰와 공개 버전 이력
getProduct
getRecommendedProducts
getProductReviews
findCurrentOnSaleProduct
getPublicVersionHistory

// wishlist·order 카드용 ID 일괄 조회: 입력 순서 보존 + family 집계 일괄 조회
getProductsByIds

// 응답 조립과 다운로드 URL 생성
toListItemResponse overloads
toReviewResponse
toVersionResponse
createPreviewContent
ObjectStorageGateway.presignIfPresent/presignAllIfPresent 재사용
```

주석은 각 묶음의 정책을 한 줄로 설명한다. 메서드 내용을 그대로 한국어로 번역한 주석이나 모든 private
메서드의 Javadoc은 추가하지 않는다.

private 메서드 rename은 다음으로 제한한다.

| 기존 | 변경 |
| --- | --- |
| `searchViaElasticsearch` | `searchProductsWithElasticsearch` |
| `searchViaRdb` | `searchProductsWithRdb` |
| `getOnSaleProduct` | `findCurrentOnSaleProduct` |
| `toVersionHistory` | `getPublicVersionHistory` |

PR 1에서 object storage port 이름이 이미 확정되면 `StorageClient` 대신 그 결과를 사용하되 이 PR에서
저장소 경계를 다시 설계하지 않는다.

##### ES fallback 예외 경계

`search/application/ProductSearchUnavailableException`을 추가한다. 이 예외는 “검색 인프라를 사용할 수
없어 RDB fallback이 허용된다”는 application 계약이다.

- `ElasticsearchProductSearchQuerier`는 `IOException`, Elastic client/transport 예외, msearch item의
  명시적 failure를 이 예외로 변환한다.
- ES response를 domain hit로 조립하는 코드의 `NullPointerException`, `ClassCastException`, index 계산
  오류 등 프로그래밍 결함은 감싸지 않는다.
- `ProductQueryService.getProducts()`는 이 전용 예외만 catch해 warn 로그 후 RDB로 fallback한다.
- `suggest()`도 이 전용 예외만 catch해 기존 정책대로 빈 목록을 반환한다.
- 입력 검증의 `ProductException`, DTO 조립 오류, S3 download URL 생성 실패와 기타
  `RuntimeException`은 RDB fallback으로 숨기지 않고 원래 예외 처리 경로로 전달한다.
- query embedding은 `QueryEmbeddingCache`가 timeout/외부 실패를 이미 `null`로 바꾸어 lexical search로
  낮추므로, embedding 실패만으로 전체 RDB fallback하지 않는 현재 정책을 유지한다.

로그에는 검색 조건 전체나 사용자 입력 원문을 불필요하게 남기지 않고 fallback 종류와 원인 예외를
남긴다.

##### `getProductsByIds()`의 2N 집계 제거

현재 representative마다 `sumSalesCountByFamilyRootId()`와 `getAverageRating()`을 호출한다. wishlist/order
요청 100건이면 대표본 조회 외에 집계 쿼리만 최대 200회 발생한다. 다음 순서로 바꾼다.

```text
요청 productIds
  -> ProductFamilyResolver로 각 요청 ID의 표시 representative 결정
  -> representative의 familyRootId를 중복 제거
  -> getSalesCounts(familyRootIds) 1회
  -> getAverageRatings(familyRootIds) 1회
  -> 원래 productIds 순서로 response 조립
```

repository port에 `Map<UUID, Long> getSalesCounts(List<UUID> familyRootIds)`를 추가하고 JPA query는
`coalesce(parentId, id)`로 group by해 삭제되지 않은 모든 version의 `salesCount` 합을 반환한다. 빈 입력은
query를 실행하지 않고 빈 Map을 반환한다. 기존 `getAverageRatings()`도 동일하게 빈 입력 단축을 유지한다.

- response의 `productId`는 기존처럼 요청에 들어온 ID를 유지한다.
- 실제 표시 정보와 집계 기준은 resolver가 고른 representative와 그 family root를 사용한다.
- 요청 ID의 순서와 중복을 기존과 동일하게 보존한다. 조회할 수 없는 ID만 기존처럼 빠진다.
- 집계 row가 없는 family는 판매량 `0L`, 평균 평점 `0.0`을 사용한다.
- thumbnail presign은 반환되는 각 response에 기존처럼 적용하며 URL/필드 의미를 바꾸지 않는다.

기존 단건 `sumSalesCountByFamilyRootId()`와 `getAverageRating()`은 상세 조회와 다른 호출자가 계속 사용하므로
이 PR에서 무조건 삭제하지 않는다. 실제 사용처가 0개가 된 메서드만 compiler/검색으로 확인 후 제거한다.

##### 조회수 lost update 방지

현재 상세 조회는 엔티티의 `viewCount`를 1 증가시켜 save한다. 동시에 두 요청이 같은 값을 읽으면 둘 다
`N+1`을 저장해 한 번이 사라질 수 있다. repository에 다음 의미의 원자적 update를 추가한다.

```sql
update product
set view_count = view_count + 1,
    updated_at = :viewedAt
where id = :productId
  and deleted_at is null
```

application은 상세용 ON_SALE representative를 결정한 뒤 `LocalDateTime viewedAt`을 한 번 만들고
`incrementViewCount(productId, viewedAt)`을 호출한다. 영향 row가 1이 아니면 조회 도중 삭제된 경쟁 상태로
보고 `PRODUCT_NOT_FOUND`로 처리한다. 응답의 `updatedAt`은 기존 조회 행위와 동일하게 `viewedAt`을 사용해
DB와 맞추고, view count 자체는 상세 응답 field가 아니므로 재조회하지 않는다.

이 update가 `updatedAt`을 바꾸는 것은 의도적이다. PR 5 scheduler가 조회수 변경 family를 감지해 ES의
정렬 통계를 갱신해야 하기 때문이다. embedding source hash가 같으면 OpenAI API는 다시 호출하지 않는다.

##### 변경 금지 계약과 FE 확인

아래 endpoint는 그대로 유지한다.

```text
GET  /api/v2/products
GET  /api/v2/products/suggest
POST /api/v2/products/wishlists
POST /api/v2/products/orders
GET  /api/v2/products/{productId}
GET  /api/v2/products/{productId}/recommends
GET  /api/v2/products/{productId}/reviews
```

FE `lib/products.ts`는 wishlist와 order를 각각 기존 두 POST endpoint로 호출하고, 응답
`ProductByIdsItem`의 모든 field를 그대로 사용한다. 따라서 FE source 변경은 없다. 다음 회귀만 확인한다.

- 홈·browse 목록의 검색, 정렬, page와 ES 장애 시 RDB 목록
- 검색 자동완성 정상 응답과 ES 장애 시 빈 dropdown
- 공개 상세 이미지·추천·리뷰·버전 이력
- 마이페이지 wishlist/order 카드의 입력 순서, thumbnail, 판매량, 평점, 상태
- 판매하지 않거나 삭제된 상품의 기존 제외/오류 처리

API 계약이 바뀌지 않으므로 `docs/api-spec/product.md` 내용 변경도 만들지 않는다. 실제 구현 중 명세와
코드의 기존 불일치가 발견되면 이 PR에 몰래 섞지 않고 별도 근거로 기록한다.

##### 변경 파일 경계

```text
product-service/src/main/java/com/prompthub/product/
  application/usecase/query/ProductSearchUseCase.java
  application/usecase/query/ProductQueryUseCase.java
  application/service/query/ProductSearchService.java
  application/service/query/ProductQueryService.java
  presentation/controller/product/ProductController.java
  domain/repository/ProductRepository.java
  infra/persistence/ProductJpaRepository.java
  infra/persistence/ProductRepositoryAdapter.java

product-service/src/main/java/com/prompthub/search/
  application/query/ProductSearchUnavailableException.java
  infra/es/query/ElasticsearchProductSearchQuerier.java

product-service/src/test/java/com/prompthub/product/
  application/service/ProductSearchServiceTest.java
  application/service/ProductQueryServiceTest.java
  presentation/controller/product/ProductControllerTest.java
  infra/persistence/ProductJpaRepositoryTest.java

product-service/src/test/java/com/prompthub/search/infra/es/query/
  ElasticsearchProductSearchQuerierTest 또는 기존 integration test 보완
```

검색 경계를 위한 usecase/service 각 하나 외에 새 service/mapper 패키지, endpoint, DTO, migration은
추가하지 않는다. repository port와 응답 DTO는 두 service가 기존 계약을 그대로 재사용한다.

##### 필수 테스트와 완료 조건

- ES 전용 unavailable 예외이면 목록은 RDB fallback, suggest는 빈 목록
- 일반 RuntimeException, response mapping 결함, ProductException은 fallback하지 않고 전파
- lexical·hybrid·msearch item·suggest의 실제 ES 통신 실패가 전용 예외로 변환
- `getProductsByIds` 입력 0·1·10·100건에서 판매량 query 최대 1회, 평점 query 최대 1회
- 여러 요청 ID가 같은 family를 가리켜도 집계 기준 ID는 중복 제거하고 응답 순서·중복은 보존
- 집계 row 없음은 판매량 0·평점 0.0, 삭제 version 판매량 제외
- 동시 상세 조회 N회 후 view count가 정확히 N 증가하고 update 각각의 영향 row가 1
- 상세 조회 중 삭제 경쟁으로 update 0건이면 PRODUCT_NOT_FOUND
- 검색 메서드는 `ProductSearchUseCase`, 나머지 조회 메서드는 `ProductQueryUseCase`에만 존재하고
  Controller MockMvc endpoint와 response JSON snapshot은 전후 동일
- FE `/`, `/browse`, `/detail/[id]`, wishlist/order 마이페이지 회귀 확인
- `:product-service:test`, 관련 ES integration test, `git diff --check`, 적용 규칙 기반 diff 검증 통과
- 기존 baseline 실패는 별도 기록하고 이 PR이 추가한 실패는 0건

##### Claude가 생성할 이슈 본문 핵심

- 문제: 조회 service의 책임 순서와 private 이름이 흐름을 숨기고, 광범위한 RuntimeException fallback이
  코드 결함까지 감추며, ID 100건에서 집계 쿼리가 최대 200회 실행되고 조회수 동시 증가가 유실될 수 있다.
- 결정: 목록·자동완성만 `ProductSearchService`/`ProductSearchUseCase`로 분리한다. 나머지는 기존
  query service/usecase에 유지하고, ES 전용 예외만 fallback, family 판매량/평점은 각각 한 번에 집계,
  조회수는 원자적 update로 변경한다.
- 제외: public endpoint/DTO 변경, 서비스 4분할, 별도 mapper/ViewService, 캐시, migration, FE source 변경.
- GitHub: 사용자 소유 신규 #731에서 구현하고, 완료 PR이 합의한 검색 경계 분리와 CQS 위험 완화를
  충족하면 #731과 #411을 함께 종료한다.
- 수용 기준: 위 필수 테스트와 변경 금지 계약을 그대로 사용한다.

##### 실제 구현 결과 (2026-08-13) — `IMPLEMENTED · PR_PENDING`

- 실제 범위: 목록·자동완성을 `ProductSearchUseCase`/`ProductSearchService`로 분리하고, 상세·추천·리뷰·batch는
  `ProductQueryUseCase`/`ProductQueryService`에 유지했다. Controller와 공개 API·DTO·FE source는 변경하지
  않았다. batch family 판매량·평점은 각각 한 번의 집계 query로 조회하고 조회수는 원자적 update로 바꿨다.
- 설계 차이: service별 `createDownloadUrl(s)`를 새로 만들지 않고 기존 공용 port의
  `ObjectStorageGateway.presignIfPresent/presignAllIfPresent`를 재사용했다. 같은 규칙의 중복 구현을 피하면서
  URL 생성 실패를 숨기지 않는 기존 계약을 유지한다.
- 검증: `getProductsByIds` 0·1·10·100건, lexical·hybrid·msearch item·suggest 실패 경계, 조회수 0건과
  동시 증가를 테스트에 포함했다. 집중 테스트, `:product-service:test`, `:product-service:build`, FE build와
  `git diff --check`는 통과했다. FE lint는 기존 source의 baseline 22 errors·93 warnings로 실패했으며 FE
  작업 트리는 깨끗하고 이 PR의 신규 lint 실패는 없다.
- Quality: `ProductQueryService`는 320 LOC에서 191 LOC로 줄고 검색 책임은 159 LOC의
  `ProductSearchService`와 대응 테스트로 분리했다. 광범위한 `RuntimeException` fallback과 신규 코드의
  미사용 import를 제거했다. build의 checkstyle warning 152건은 generated protobuf source에만 남아 있다.
  별도 mapper·조회수 service·다운로드 URL helper는 만들지 않았다.

---

### I-8 · ProductSellerService 슬림화 + 응답 DTO의 S3 직접 호출 제거 — `refactor` — +3

| 해소 | 회복 |
| --- | --- |
| presentation DTO의 정적 팩토리가 `StorageClient`로 S3 원격 호출 (레이어 분리) | +2 |
| 비대 `ProductSellerService` (242 LOC·**17메서드**) | +1 |

`SellerProductDetailResponse.java:32,55,60`과 `SellerProductListItemResponse.java:26,44`가
`StorageClient`를 **인자로 받아 DTO 안에서 presign 원격 호출을 한다.** 같은 서비스의
`ProductDetailResponse`·`ProductListItemResponse`는 완성된 URL 문자열만 받는데 셀러 쪽만
다르다. 정확히는 JSON 직렬화 시점이 아니라 application service가 DTO의 `from()`을 호출하는 순간
네트워크 요청이 실행된다. 그래도 presentation DTO가 application 포트를 알고 외부 호출을 수행하는
계층 역전은 그대로이므로 service가 URL을 먼저 완성해 값으로 넘기도록 통일한다.

파일 이동 로직(`moveToProductPath`·`moveToProductPaths`)은 I-4에서 확정한
`application/service/fileupload/TempFilePromoter`로 옮긴다. URL 재파싱 메서드
`extractKey`·`extractKeys`는 `tempObjectKey` 계약으로 제거한다. 조회 응답의
`presignOrNull`·`presignAll`은 각각 `createDownloadUrl`·`createDownloadUrls`로 이름을 바꾸고
application service가 완성한 URL을 DTO에 전달한다.

`ProductSellerService`와 `ProductSellerUseCase`는 하나로 유지한다. seller query·다운로드 URL·response
mapper를 별도 service로 추가하지 않는다. 생성·수정, 상태 변경·검수, 판매자 조회, 응답 URL 생성,
공통 검증 순서로 메서드를 재배치하고 코드 번역이 아닌 정책 주석만 짧게 둔다.

private 메서드는 다음처럼 의도를 드러낸다.

```text
getProductForSeller            -> getOwnedProduct
findDuplicateOfProductId       -> findOriginalProductIdByContentHash
presignOrNull                  -> createDownloadUrl
presignAll                     -> createDownloadUrls
```

#### FE 이미지 회귀 인수 조건

I-8은 URL 생성 위치만 옮기므로 API URL·응답 JSON 필드명·presigned GET URL 값의 의미를 바꾸지 않는다.

- `GET /products/sellers/me`의 `thumbnailUrl`로 판매자 카드 이미지가 표시된다.
- `GET /products/{id}/sellers/me`의 `thumbnailUrl`·`imageUrls`·`fileUrl`로 수정 화면의 기존
  썸네일·소개 이미지·PPT/EXCEL 파일이 표시된다.
- S3 key가 없으면 썸네일·파일은 null, 이미지 목록은 빈 배열이며 이미지 순서를 유지한다.
- NOTION `externalUrl`은 S3 URL 변환 없이 그대로 반환한다.
- I-8 단독 적용은 FE 변경 없이 동작해야 한다.
- I-4의 신규 업로드 계약을 함께 적용할 때만 FE가 미리보기에는 `presignedGetUrl`, 저장 요청에는
  `tempObjectKey`를 사용하도록 BE·FE를 같은 배포 단위로 변경한다.
- BE 응답 회귀 테스트와 FE 판매자 목록·수정·공개 상세 화면에서 실제 이미지 표시를 모두 확인한다.

**검증**: 판매자 목록·상세 응답의 URL과 JSON 구조가 이전과 동일한지, DTO가 `StorageClient`를 더 이상
import하지 않는지, 위 FE 이미지 회귀 조건이 모두 통과하는지 확인한다.

#### PR 1 구현 결과 — 파일 업로드 계약·책임 정리(I-4 + I-8)

상태: **IMPLEMENTED · PR_PENDING** · 확인일: 2026-08-10 · 기준: `develop 30b8aa48`

구현은 아래의 확정 API 계약과 파일 경계를 따랐다. `StorageClient`와 URL 재파싱을 제거하고,
seller-scoped temp key·업로드 정책·S3 adapter·transaction 보상 책임을 분리했다. FE는
`objectKey`를 저장·취소 요청에, `previewUrl`을 화면 표시에만 사용한다. 공개 조회·검색·구매
응답은 변경하지 않았다.

이 PR의 최우선 완료 기준은 구조 점수가 아니라 **변경 전에 정상 동작하던 상품 등록·수정·미리보기·취소
흐름이 변경 후에도 그대로 동작하는 것**이다. 구현 시작 전에 BE 테스트와 FE lint/build를 실행해 baseline을
남긴다. 이미 실패하던 테스트나 화면은 명령, 실패 항목, 확인 시점을 별도로 기록하고 이 PR이 만든 회귀와
구분한다. 기존 실패를 고치는 일은 이 PR 범위에 자동으로 포함하지 않는다.

##### 확정 API 계약

endpoint URL은 모두 유지한다.

```text
POST   /api/v2/products/uploads/presigned-urls
DELETE /api/v2/products/images
POST   /api/v2/products
PATCH  /api/v2/products/{productId}
GET    /api/v2/products/{productId}/sellers/me
```

업로드 URL 발급 응답은 다음 세 값을 반환한다.

```json
{
  "tempObjectKey": "products/temp/{sellerId}/{purpose}/{uuid}.{ext}",
  "presignedPutUrl": "https://...",
  "presignedGetUrl": "https://..."
}
```

FE는 `presignedPutUrl`로 파일 바이트를 PUT하고 `presignedGetUrl`로 미리보기를 표시하며, 상품 POST/PATCH와
temp 삭제 요청에는 URL이 아니라 object key를 보낸다. 이를 위해 FE의 파일 상태는 최소한 다음 두 의미를
함께 보관한다.

```ts
type UploadedObject = {
  objectKey: string;
  previewUrl: string;
};
```

상품 생성·수정 요청의 저장소 필드는 의미를 숨기는 `*Url` 대신 `thumbnailObjectKey`,
`imageObjectKeys`, `fileObjectKey`를 사용한다. NOTION의 `externalUrl`은 실제 외부 URL이므로 그대로 둔다.

Swagger UI는 Gateway 경유 Bearer JWT 테스트를 기준으로 한다. Gateway가 주입하는 `X-User-Id`와
`X-User-Role`은 product-service `SwaggerConfig`에서 전역으로 숨기며 Controller마다 숨김 annotation을
반복하지 않는다. product-service 전용 기준은 `product-service/docs/swagger-rules.md`를 Codex와 Claude가
함께 사용한다. 이 PR에서 모든 endpoint의 `@ApiResponses`나 모든 DTO 필드의 `@Schema`를 소급
추가하지 않는다.

수정 화면은 기존 영구 객체도 다시 저장할 수 있어야 한다. 판매자 상세 응답은 기존 미리보기용
`thumbnailUrl`·`imageUrls`·`fileUrl`을 유지하고, 수정 요청용 `thumbnailObjectKey`·`imageObjectKeys`·
`fileObjectKey`를 추가한다. 공개 상세·검색·구매 응답 계약은 바꾸지 않는다. 이 추가 필드는 I-3의
"항상 null인 미사용 응답 필드"와 달리 FE 수정 화면이 실제로 소비하는 편집 계약이다.

취소 API는 현재 URL 목록 대신 temp object key 목록을 받는다. `ObjectStorageKey`가
`products/temp/{sellerId}/...` 형식과 요청 seller의 소유권을 검증하고, 영구 key나 다른 seller의 key는
삭제하지 않는다. 개별 파일 선택 해제 과정에서 상태에서 사라진 temp object는 이 요청에서 추적 범위를
넓히지 않고 S3 Lifecycle 안전망으로 정리한다.

##### 정상 흐름

```text
FE 파일 선택
  -> POST presigned-urls
  -> { tempObjectKey, presignedPutUrl, presignedGetUrl }
  -> FE가 presignedPutUrl로 PUT 완료를 await
  -> 성공한 뒤에만 UploadedObject 상태 반영 및 presignedGetUrl 미리보기
  -> 상품 POST/PATCH에 objectKey 전달
  -> TempFilePromoter가 seller·purpose·temp 형식 검증
  -> products/{productId}/{purpose}/...로 copy
  -> Product 저장
  -> transaction 완료 뒤 이 요청에서 승격한 temp 정리
```

PUT이 실패하면 FE 상태에 object key를 완료된 파일처럼 넣지 않고 기존 업로드 실패 메시지를 표시한다.
presigned GET URL을 먼저 발급하는 것은 문제가 아니며 별도 polling을 추가하지 않는다. `fetch(PUT)`의 성공
응답을 기다린 뒤 미리보기 상태를 갱신하는 현재 동작을 유지한다.

##### 변경 파일 경계

BE의 주요 변경 대상은 다음과 같다. 실제 구현 중 동일 책임의 테스트 fixture가 추가로 영향을 받는 것은
허용하지만 다른 상품 정책을 이 PR에 섞지 않는다.

```text
product-service/src/main/java/com/prompthub/product/
  presentation/controller/FileUploadController.java
  presentation/dto/request/UploadUrlRequest.java
  presentation/dto/response/UploadUrlResponse.java
  presentation/dto/request/ProductCreateRequest.java
  presentation/dto/request/ProductUpdateRequest.java
  presentation/dto/response/SellerProductDetailResponse.java
  config/SwaggerConfig.java
  application/usecase/FileUploadUseCase.java                         (신규)
  application/service/fileupload/FileUploadService.java             (신규)
  application/service/fileupload/FileUploadPolicy.java              (신규)
  application/service/fileupload/TempFilePromoter.java              (신규)
  application/gateway/external/ObjectStorageGateway.java            (StorageClient 대체)
  application/gateway/external/ObjectStorageKey.java                (신규)
  application/service/ProductSellerService.java
  infrastructure/external/s3/S3ObjectStorageAdapter.java            (이동·개명)
  infrastructure/external/s3/S3Config.java                          (이동)
  infrastructure/external/s3/AwsS3Properties.java                   (이동)
```

`application/client/StorageClient.java`, 기존 `infra/storage/S3StorageAdapter.java`, Controller와
SellerService의 `extractKey(s)`·업로드 정책 helper는 새 구조로 대체한 뒤 제거한다. 조회 계열에서 기존
`StorageClient`를 사용하는 파일도 import와 포트 타입은 `ObjectStorageGateway`로 바꾸되 공개 응답 값은
변경하지 않는다. `docs/api-spec/product.md`와 `docs/error-codes.md`는 실제 공개 계약과 오류 코드를 함께
갱신한다.

FE 주요 변경 대상은 다음과 같다.

```text
lib/upload.ts
components/ui/ImageUpload.tsx
components/ui/FileUpload.tsx
app/sell/page.tsx
app/edit/[id]/page.tsx
```

##### 필수 테스트와 회귀 확인

BE 자동 테스트:

- `FileUploadPolicyTest`: PPT/PPTX, EXCEL/XLSX, 이미지 확장자, 대문자 정규화, 확장자 없음과 잘못된 조합.
- `ObjectStorageKeyTest`: 정상 temp key, 잘못된 prefix·sellerId·purpose·null/blank, 영구 key 구분.
- `FileUploadServiceTest`: seller가 포함된 temp key와 세 응답값 생성, 정책 위반 시 storage 미호출.
- `TempFilePromoterTest`: 본인 temp 승격, 다른 seller 거부, null/영구 key 유지, copy 실패 시 delete 미호출,
  여러 객체 중 부분 실패 시 이 요청이 만든 영구 객체만 보상 삭제하고 temp는 Lifecycle을 위해 유지.
- `FileUploadControllerTest`: endpoint 유지, 신규 JSON 필드, key 기반 취소 삭제와 소유권 거부.
- `SwaggerConfigTest`: Bearer 계약은 유지하고 내부 사용자 헤더만 OpenAPI 파라미터에서 제거.
- `ProductSellerServiceTest`: 생성·수정 요청에서 URL 파싱 없이 key 사용, 저장 전 유형 검증, 기존 영구 key
  유지, 판매자 조회 응답의 preview URL과 object key 일치.
- `S3ObjectStorageAdapterTest`: PUT/GET presign request의 bucket·key·contentType·만료 시간, copy 전용
  `S3_COPY_FAILED`, delete 실패 로그 정책.
- 전체 `:product-service:test`; baseline에 없던 실패가 한 건이라도 생기면 PR 완료로 보지 않는다.

FE 자동·수동 검증:

- 현재 별도 단위 테스트 script가 없으므로 `npm run lint`와 `npm run build`를 변경 전·후 같은 환경에서
  비교한다. 기존 실패는 신규 실패와 분리해 기록한다.
- PROMPT·NOTION·PPT·EXCEL 신규 등록, 임시저장 후 수정 화면 진입, 기존 상품 수정.
- 썸네일과 소개 이미지 미리보기, PPT/EXCEL 업로드 완료 표시, 기존 파일을 바꾸지 않고 저장.
- S3 PUT 실패 시 완료 상태로 바뀌지 않고 오류 표시, 저장 버튼 중복 클릭 방어 유지.
- 신규 등록 취소와 수정 취소 시 본 요청의 temp key만 삭제 요청하며 화면 이동 유지.
- 판매자 카드·수정 화면·공개 상세·구매자 파일 다운로드에서 기존 이미지와 파일 표시 회귀 없음.

##### 구현 후 검증 결과

- `./gradlew.bat :product-service:cleanTest`: 성공. 이 task는 현재 빌드 설정에서 테스트 산출물 정리만 수행했다.
- `./gradlew.bat :product-service:test`: Gradle `BUILD SUCCESSFUL` 확인.
- FE `npm.cmd run build`: 성공.
- FE `npm.cmd run lint`: 저장소 기존 전체 lint 오류로 실패(22 errors, 93 warnings). PR 1 변경 5개
  파일 대상 ESLint는 0 errors, 6 warnings.
- BE·FE `git diff --check`: 성공.
- 수동 실 S3 PUT·미리보기·취소 E2E와 Lifecycle 설정은 PR 자동 검증 범위에서 제외한다.

##### 이슈 본문 초안

**제목**: `refactor(product): 파일 업로드 계약과 S3 승격 책임 정리`

**배경**: Controller가 확장자·상품 유형 정책과 S3 key 생성을 처리하고, FE가 presigned GET URL을 상품
요청에 다시 보내 BE 두 곳에서 AWS URL을 key로 재파싱한다. `sellerId`도 temp key와 삭제 소유권에 쓰이지
않으며 SellerService가 copy/delete까지 맡아 책임과 실패 경계가 불명확하다.

**목표**: object key와 presigned PUT/GET URL의 역할을 명확히 분리하고, 업로드 정책·외부 저장소 포트·
temp 승격 책임을 각각 분리한다. 기존에 정상 동작하던 등록·수정·미리보기·취소·조회 흐름은 유지한다.

**범위**: I-4와 I-8, BE/FE 업로드 계약 동시 변경, 판매자 상세 편집용 object key 추가, 관련 API·오류
문서와 테스트. 자동 버전 판정, Kafka 신뢰성, ES scheduler, S3 Lifecycle 실제 AWS 설정은 제외한다.

**완료 조건**: 위 BE 테스트와 FE 회귀 조건 통과, URL 파싱 제거, 다른 seller temp 삭제 차단, 공개 API
endpoint 유지, baseline에 없던 신규 실패 없음. CodeFlow는 기존 Receipt만 확인하며 이 PR에서 설정을
변경하지 않는다.

---

### I-9 · 잔여 소소한 정리 — `chore` — +2.5

| 항목 | 위치 | 회복 |
| --- | --- | --- |
| `ProductFamily`의 죽은 메서드 2개 제거 → 16메서드에서 14로 | `ProductFamily.java:43,63` | +1 |
| `handleNoResourceFound`/`handleException`의 미사용 `HttpServletRequest` 파라미터 | `ProductExceptionHandler.java:66-69,80` | +0.5 |
| `copyObject` 실패에 `S3_PRESIGN_FAILED`("파일 업로드 URL 생성에 실패했습니다") 재사용 | `S3StorageAdapter.java:83` | +0.5 |
| `new ProductContent(...)` 12개 위치 인자와 mutable list | `ProductSellerService.java:53-57,86-90`, `ProductContent.java` | +0.5 |

`ProductFamily`의 죽은 메서드는 확인 완료다. `members()`는 **프로덕션·테스트 어디서도 안
쓰이고**, `mostRecentSuperseded()`는 **자기 테스트에서만** 호출된다. 둘을 제거하면 메서드가
14개가 되어 비대 판정에서 빠진다. 나머지 14개는 작고 응집도가 높으니 **더 쪼개지 않는다.**

`copyObject` 실패에 "URL 생성 실패" 메시지가 나가는 건 로그와 응답이 실제 원인과 어긋나는
문제다. `S3_COPY_FAILED`(`S002`, 500, "파일 저장에 실패했습니다.")를 추가하고
`docs/error-codes.md`를 동기화한다.

#### ProductContent Builder 적용 판단

`ProductContent`는 productType·name·description·model·S3 key·유형별 본문 등 12개 값을 받는다.
현재 위치 기반 생성자는 같은 `String` 타입이 연속되어 description/model/content/fileUrl/externalUrl
순서를 잘못 넣어도 컴파일이 통과할 수 있고, 호출부만 읽으면 각 값의 의미를 계속 생성자 선언과
대조해야 한다.

`ProductContent` record에 Lombok `@Builder`를 적용하고 production 조립부를 이름 기반 호출로 바꾼다.

```java
ProductContent content = ProductContent.builder()
    .productType(productType)
    .name(request.title())
    .description(request.desc())
    .model(request.model())
    .amountType(amountType)
    .amount(request.amount())
    .thumbnailUrl(thumbnailKey)
    .imageUrls(imageKeys)
    .content(request.content())
    .fileUrl(fileKey)
    .externalUrl(request.externalUrl())
    .tags(request.tags())
    .build();
```

Builder는 필드가 많다는 이유만으로 별도 factory/service를 추가하지 않으면서 호출부에 필드명을
남긴다. 인자 순서 의존과 동일 타입 오배치를 줄이고, 선택 필드가 많은 상품 유형별 조립도 읽기 쉽게
한다. 반면 Builder 자체가 필수값을 보장하는 것은 아니므로 기존 record compact constructor의
`validateTypeFields()`를 유지한다. `build()`도 최종 canonical constructor를 호출하므로 PROMPT/PPT/
EXCEL/NOTION 필드 조합 검증은 이전과 동일하다.

값 객체 불변성도 함께 보완한다. 현재는 전달받은 `imageUrls`·`tags` 목록을 그대로 보관해 외부에서
원본 list를 수정하면 생성 후 `ProductContent`와 content hash의 의미가 바뀔 수 있다. compact
constructor에서 null은 `List.of()`, 값이 있으면 `List.copyOf()`로 방어적 복사한다.

```java
imageUrls = imageUrls == null ? List.of() : List.copyOf(imageUrls);
tags = tags == null ? List.of() : List.copyOf(tags);
```

**검증**: Builder로 4개 ProductType의 정상 조합을 만들 때 기존과 같은 값이 생성되는지, 잘못된
유형별 필드 조합은 여전히 `P007`인지, 원본 image/tag list를 생성 후 수정해도 ProductContent 값이
바뀌지 않는지 확인한다. 테스트 fixture까지 전부 기계적으로 바꾸지는 않고 가독성이 필요한 production
조립부와 대표 fixture부터 적용한다.

### I-11 · 검색어 + 정렬(rating·price-asc) 조합에서 결과 소실 — `fix` — 채점 외(기능 결함)

PR 6(#731) FE 회귀 확인의 홈·browse 검색 및 정렬 항목을 실측하는 과정에서 발견했다. 로컬 검증 데이터에서
`sort=rating`으로 "이력서 작성"을 검색하면 popular·price-asc는 10건, rating은 0건을 반환했고,
"코드 리뷰"는 4건에서 2건으로 줄었다.

원인은 하이브리드 검색을 도입한 커밋 `b6f54a4b`(#378)부터 존재한 기존 결함이며 PR 6은 이 파일을
패키지 재구성 과정에서 옮겼을 뿐 해당 로직을 변경하지 않았다. `ElasticsearchProductSearchQuerier`의
하이브리드 실행 조건이 `SORT_POPULAR`로 고정돼 있어 검색어가 있더라도 rating·price-asc 정렬은
`lexicalSearch`만 실행한다. 이 때문에 텍스트로 약하게 매칭되거나 `minimumShouldMatch("2<75%")`를
충족하지 못한 문서가 의미 기반 KNN 후보 확장에 포함되지 못한다. 기존 주석은 값 정렬에서 하이브리드
병합 순서가 무의미하다고 설명하지만, 하이브리드 검색은 순서뿐 아니라 후보 집합도 확장한다.

영향 범위는 rating과 price-asc 정렬이다. 이번 실측에서는 rating에서만 결과 소실이 드러났지만 두 정렬이
동일한 하이브리드 실행 조건을 사용하므로 함께 수정하고 검증한다. 수정은 구현 방식을 미리 고정하지 않고,
검색어가 있는 정렬 요청에서도 lexical·KNN 후보 확장을 보존한 뒤 요청한 정렬과 명시적인 동점 기준을
적용하는 것을 목표로 한다.

현재 `ProductSearchQueryBuilderTest`와 `ElasticsearchProductSearchQuerierTest`에는 고정된 검색 fixture로
정렬별 후보 집합과 total을 비교하는 회귀 검증이 없다. 수정 시 다음을 확인한다.

- popular·rating·price-asc가 동일한 후보 상품 집합과 total을 반환한다.
- 각 응답은 요청한 정렬 기준과 명시적인 tie-breaker를 따른다.
- 평점이 없거나 가격이 같은 상품에서도 결과가 결정적이며, 페이지네이션 중 중복·누락이 없다.

#### I-11 구현 결과

- 실제 구현: 검색어가 있고 fusion window 안이면 popular·rating·price-asc 모두 동일한 relevance 기반
  lexical 후보와 KNN 후보를 확장한다. popular는 기존 RRF 순서를 유지하고, rating은 평점 내림차순,
  price-asc는 가격 오름차순으로 병합 후보를 최종 정렬한다. 두 값 정렬의 동점 기준은 `familyRootId`
  오름차순이다.
- 설계 차이: 후보 집합과 total을 세 정렬 간 직접 비교하는 단일 fixture 대신, rating·price-asc 각각에서
  lexical 전용 후보와 semantic 전용 후보가 함께 포함되고 요청한 값 순서로 반환되는 통합 회귀 테스트로
  핵심 결함을 고정했다. 기존 primitive 필드와 Elasticsearch 값 정렬 정책은 변경하지 않았다.
- 검증: focused 단위·통합 테스트, `:product-service:test`, `git diff --check` 통과.
- Quality: `ponytail`과 `write-readable-code` 기준으로 기존 query adapter 안에서 최소 변경했으며 새 계층이나
  의존성을 추가하지 않았다. 전체 diff 검토에서 범위 밖 기능 변경과 보안·아키텍처 위반은 발견되지 않았다.
- 현재 상태: `IMPLEMENTED · PR_PENDING`.

#### PR 7 구현 인계 — 죽은 응답·유형별 산출물·값 객체 소규모 정리(I-3 + I-5 + I-9)

##### 선행 PR 결과를 먼저 반영하는 규칙

이 PR은 마지막 순서이므로 계획에 적힌 과거 파일명이나 메서드를 그대로 복원하지 않는다. PR 1~6의 실제
merge 결과에서 사용처를 다시 검색한 뒤 남은 항목만 정리한다.

- `S3_COPY_FAILED(S002)`와 `docs/error-codes.md`는 PR 1에서 구현·검증하므로 PR 7에서 중복 변경하지 않는다.
- `ProductContent` production 조립부가 PR 1/3 과정에서 이미 Builder로 바뀌었으면 남은 불변성·테스트만
  보완한다.
- `ProductFamily.members()` 또는 `mostRecentSuperseded()`가 PR 2/3의 실제 구현에서 새로 사용되었다면
  죽은 코드가 아니므로 제거하지 않는다. production·test 전체에서 사용처가 없을 때만 삭제한다.
- 앞선 PR에서 바뀐 object key/port 이름을 현재 코드 기준으로 사용한다.

##### I-3: 값이 없던 공개 응답 field 제거

다음 field를 BE 공개 response record와 모든 생성자 호출에서 제거한다.

| 응답 | 제거 field | 현재 실제 값 |
| --- | --- | --- |
| `ProductListItemResponse` | `originalAmount`, `badge` | 항상 `null` |
| `ProductDetailResponse` | `badge`, `features` | 항상 `null`, 항상 빈 목록 |

- 할인 전 가격·상품 뱃지·특징 정책을 새로 만들지 않는다.
- `ProductSearchHit`에 badge를 추가하지 않는다. `ProductSearchDocument`의 badge 전달도 제거해 새 문서에
  의미 없는 값을 쓰지 않는다. 기존 `products-v1` mapping에 남은 field는 live index migration 없이
  삭제할 수 없으므로 이번 PR에서 index version을 올리지는 않는다.
- `Product.badge`와 DB column은 legacy persistence 범위로 남긴다. all-null 여부와 migration 운영 절차를
  검증하지 않고 작은 response 정리 PR에서 column을 drop하지 않는다.
- `docs/api-spec/product.md`의 공개 목록·상세 response 표와 예시에서 네 field를 제거한다.

FE는 optional field와 fallback을 이미 사용하므로 source 변경 없이 동작한다.

- `originalAmount`가 없으면 할인 취소선/할인율을 렌더하지 않는다.
- `badge`가 없으면 유료 상품 badge를 렌더하지 않으며 무료 표시는 기존 `amount === 0` 조건으로 유지된다.
- `features`가 없으면 상세 페이지가 기존 기본 문구 세 개를 사용한다.
- order 상태 badge는 `orderAdapters.ts`가 만드는 별개 값이므로 영향이 없다.

FE type의 optional 선언을 억지로 제거하면 기존 fallback 접근 코드가 함께 커지므로 그대로 둔다. 후속으로
FE 자체 타입을 정리할 때 별도 변경할 수 있다.

##### I-5: ProductType별 산출물 규칙 단일화

중복의 핵심은 “PROMPT는 content, PPT/EXCEL은 file object key, NOTION은 external URL”이라는 원본 field
선택이다. 반면 gRPC는 최종 문자열 하나를 반환하고 구매 상세 REST는 content/fileUrl/externalUrl 세
field를 나눠 반환하므로 response 조립까지 억지로 하나의 mapper로 합치지 않는다.

domain에 다음 명시적 값 객체를 둔다.

```text
ProductDeliverable
  type: INLINE_CONTENT | FILE_OBJECT_KEY | EXTERNAL_URL
  value: String
```

- `ProductType.resolveDeliverable(content, fileObjectKey, externalUrl)` 한 곳에서 유형별 원본 field를 선택해
  `ProductDeliverable`을 반환한다.
- `ProductType.isValidContentCombination(...)`도 같은 유형 규칙을 사용하고 `ProductContent` compact
  constructor가 이를 호출한다. 유효하지 않으면 기존처럼 `P007`이다.
- `Product.resolveDeliverable()`은 자신의 세 persistence field를 `ProductType`에 전달하는 짧은 도메인
  행위다. application service가 entity getter 세 개를 직접 switch하지 않는다.
- `ProductGrpcService`는 `FILE_OBJECT_KEY`일 때만 download URL을 만들고 나머지는 value를 그대로 반환한다.
- `PurchasedProductQueryService`는 deliverable type에 따라 기존 response의 content/fileUrl/externalUrl 중
  하나만 채운다. FILE_OBJECT_KEY만 download URL로 변환한다.
- `ProductDeliverable`은 null/blank value가 존재하지 않도록 생성 시 검증한다. 잘못된 persistence row가
  있다면 조용히 null response를 만들지 않고 도메인 불변식 오류로 드러낸다.

이 구조는 새 ProductType을 추가할 때 field 선택과 유효 조합을 `ProductType`에서 컴파일 오류와 함께
결정하게 한다. application service마다 `switch(productType)`를 복사하거나, 반대로 S3 URL 생성 함수를
domain enum에 넘겨 infrastructure 관심사를 섞지 않는다.

##### I-9: ProductContent Builder와 불변성

`ProductContent` record에 Lombok `@Builder`를 적용한다. compact constructor는 다음 순서로 유지한다.

1. `imageUrls`와 `tags`의 null을 `List.of()`로 정규화한다.
2. 값이 있으면 `List.copyOf()`로 방어적 복사한다.
3. `ProductType.isValidContentCombination()`으로 유형별 field 조합을 검증하고 실패하면 `P007`을 던진다.

Builder가 필수값 검증을 대신한다고 간주하지 않는다. `build()`가 canonical constructor를 호출하기 때문에
compact constructor가 최종 불변식 경계다. production의 생성·수정 조립은 12개 위치 인자 대신 이름 기반
Builder로 바꾼다. 대표 test fixture도 Builder로 바꾸되, canonical constructor 자체를 검증하는 테스트는
남겨 Builder를 우회해도 불변식이 유지됨을 확인한다.

원본 mutable list를 Builder에 넘긴 뒤 변경해도 생성된 `ProductContent.imageUrls()`와 `tags()`가 바뀌지
않아야 한다. record accessor가 반환한 list도 수정할 수 없어야 한다.

##### I-9: 실제 죽은 코드와 예외 handler 인자

- `ProductFamily.members()`와 `mostRecentSuperseded()`는 최종 branch의 main/test 전체 사용처를 확인한다.
  사용처 0개인 것만 메서드와 그 메서드만 검증하던 테스트를 함께 제거한다. 단순히 목표 메서드 수를
  맞추기 위해 사용 중인 행위를 삭제하지 않는다.
- `ProductExceptionHandler.handleNoResourceFound()`와 `handleException()`의 사용하지 않는
  `HttpServletRequest` parameter와 import를 제거하고 직접 호출 테스트도 새 signature로 수정한다.
- 404 `SYS002`와 500 `SYS001`의 status/code/message, logging 수준은 바꾸지 않는다.
- `ProductInspectionResultHandler` 이름·위치는 앞서 확정한 검수 service 구조 결과를 따른다. 이 PR에서
  다시 rename하지 않는다.

##### 변경 파일 경계

예상 경계는 다음과 같으며 선행 PR에서 이미 해결된 파일은 변경하지 않는다.

```text
product-service/src/main/java/com/prompthub/product/
  presentation/dto/response/ProductListItemResponse.java
  presentation/dto/response/ProductDetailResponse.java
  application/service/ProductQueryService.java
  domain/model/enums/ProductType.java
  domain/model/vo/ProductDeliverable.java
  domain/model/vo/ProductContent.java
  domain/model/entity/Product.java
  application/service/ProductGrpcService.java
  application/service/PurchasedProductQueryService.java
  domain/model/entity/ProductFamily.java
  exception/ProductExceptionHandler.java

product-service/src/main/java/com/prompthub/search/
  infra/es/ProductSearchDocument.java
  infra/es/ElasticsearchProductSearchIndexer.java

product-service/src/test/**
  response/controller/query/grpc/purchased/domain/exception 관련 테스트와 fixture

docs/api-spec/product.md
```

새 endpoint, service/usecase, DB migration, ES index version, Kafka 변경, FE source 변경은 만들지 않는다.

##### 필수 테스트와 완료 조건

- 목록·상세 JSON에서 `originalAmount`, `badge`, `features`가 빠지고 나머지 field 값과 이름은 동일
- FE `/`, `/browse`, `/detail/[id]`에서 할인/유료 badge는 숨고 무료 badge·기본 features·이미지는 정상
- PROMPT → INLINE_CONTENT, PPT/EXCEL → FILE_OBJECT_KEY, NOTION → EXTERNAL_URL 매핑 parameterized test
- 4개 ProductType의 gRPC 산출물과 구매 상세 REST content/fileUrl/externalUrl 결과가 이전과 동일
- FILE_OBJECT_KEY에서만 download URL 생성, PROMPT/NOTION은 object storage 호출 0회
- 유형별 금지 field 혼합과 필수 field 누락은 계속 P007
- Builder 정상 조립, canonical constructor 검증 유지, null collection은 빈 immutable list
- 원본 list와 accessor list 변경 시도에도 ProductContent 불변
- 최종 사용처 0개인 ProductFamily 메서드만 제거되고 나머지 family 선택/정렬 테스트 유지
- exception handler signature 정리 후 404 SYS002와 500 SYS001 응답 동일
- `:product-service:test`, `git diff --check`, 적용 규칙 기반 diff 검증 통과
- 기존 baseline 실패는 별도 기록하고 이 PR이 추가한 실패는 0건

##### Claude가 생성할 이슈 본문 핵심

- 문제: 공개 응답에 항상 null/empty인 field가 남고, ProductType별 산출물 선택이 세 곳에 복제되며,
  ProductContent의 12개 위치 인자와 mutable list가 오배치·불변성 위험을 만든다. 일부 미사용 메서드와
  handler parameter도 남아 있다.
- 결정: 정책 없는 response field는 제거하고, 원본 산출물 선택만 domain의 ProductType과
  ProductDeliverable로 단일화한다. ProductContent는 이름 기반 Builder와 compact constructor 검증,
  방어적 복사를 함께 사용한다. 실제 사용처 0개인 코드만 삭제한다.
- 제외: 새 상품 badge/할인/features 정책, DB badge column drop, ES index migration, service 추가 분리,
  infrastructure를 domain에 주입, endpoint/Kafka/FE source 변경.
- 선행 중복: S002는 PR 1 결과를 사용하고 재구현하지 않는다.
- 수용 기준: 위 필수 테스트와 변경 파일 경계를 그대로 사용한다.

#### PR 7 구현 결과

- 실제 구현 범위: 공개 목록의 `originalAmount`·`badge`, 상세의 `badge`·`features`와 새 ES 문서의 의미 없는
  `badge` 전달을 제거했다. 유형별 원본 산출물 선택은 `ProductType`과 `ProductDeliverable`로 단일화하고,
  `ProductContent` production 조립은 Builder로 바꾸면서 compact constructor의 조합 검증과 collection
  방어적 복사를 유지했다. 최종 사용처가 없던 `ProductFamily.members()`·`mostRecentSuperseded()`와 예외
  handler의 미사용 request 인자도 제거했다.
- 설계 차이: 기존 ES mapping과 DB `badge` column은 migration 없이 유지했다. 파일 상품의 비어 있는 object
  key는 종전처럼 null 응답으로 숨기지 않고 `ProductDeliverable` 생성 시 불변식 오류로 드러낸다. FE optional
  type과 fallback은 그대로 두어 FE source는 변경하지 않았다.
- 검증: 관련 domain·application·controller·exception·search focused 테스트 72건, Docker 기반 통합 테스트를
  포함한 `:product-service:test`, FE build가 통과했다. FE lint는 기존 22 errors·93 warnings로 실패했다.
  Checkstyle은 생성된 protobuf의 기존 warning만 남기고 task가 통과했으며, 변경 관련 테스트 실패는 0건이다.
- Quality: `ponytail`과 `write-readable-code` 기준으로 새 service나 mapper 없이 기존 domain 규칙과 호출부만
  정리했다. endpoint·DB migration·ES index version·Kafka·FE source 등 범위 밖 기능은 변경하지 않았다.
- 현재 상태: `IMPLEMENTED · PR_PENDING`.

---

## 상품 파일 업로드·등록 분석 인계 기록

상태: **PR 1 IMPLEMENTED · PR 2~7 DESIGN_COMPLETE** · 확인일: 2026-08-10 · 분석 기준: `develop 30b8aa48`

이 섹션은 긴 대화 원문 대신, 다른 세션이나 Claude가 후속 PR을 이어갈 수 있도록 확인한
사실과 확정 결정을 보존한다. PR 1은 위 구현 결과와 검증 기록을 기준으로 하고, PR 2~7은 아직
구현·성과가 검증되지 않았으므로 예상 효과를 완료 결과로 표현하지 않는다.

### BE·FE 공동 확인 원칙

이후 product-service 로드맵의 사용자 흐름과 계약은 BE 코드만으로 판정하지 않는다. 다음 항목은 반드시
`C:\programmers_prj\beadv6_6_3JMT_FE`의 실제 화면과 API 호출을 함께 대조한다.

- 상태별 버튼·입력 필드 노출 조건
- request payload에 실제 포함·생략되는 필드와 기본값
- 업로드 완료 대기, 취소·이탈 정리와 미리보기 흐름
- 성공 toast, 오류 status별 처리와 중복 제출 방지
- BE 응답 필드 변경이 사용되는 컴포넌트와 타입

BE가 기술적으로 허용하는 경로와 정상 FE UX 경로를 구분해 기록한다. FE 우회 호출에 대한 서버 방어도
별도로 판단한다. FE 확인은 모든 관련 로드맵 분석의 기본 범위지만, 실제 FE 코드 변경은 이슈 범위와
배포 계약에 포함됐을 때만 수행한다.

### 확인한 현재 실행 흐름

```text
1. POST /api/v2/products/uploads/presigned-urls
2. FileUploadController가 fileName 확장자와 purpose/productType 조합 검사
3. products/temp/{purpose}/{uuid}.{ext} key 생성
4. S3StorageAdapter가 presigned PUT(10분)·GET(30분) URL 생성
5. FE가 PUT URL로 S3에 직접 파일 업로드하고 응답 성공을 기다림
6. FE가 GET URL로 미리보기
7. 사용자가 상품 등록 요청
8. ProductSellerService가 GET URL에서 key를 다시 파싱
9. temp key를 products/{productId}/...로 copy하고 temp 객체 delete
10. ProductContent를 생성하면서 유형별 필드 조합 검증
11. Product.create()가 DRAFT 상품 생성
12. Product DB save 후 PRODUCT_CHANGED 발행을 afterCommit에 등록
13. DB commit 성공 후 Kafka send 호출
14. 판매자가 별도로 검수를 요청해야 DRAFT -> PENDING_REVIEW 및 PRODUCT_REVIEW_REQUESTED 발행
```

근거 코드:

- `FileUploadController.java:46-115`
- `S3StorageAdapter.java:27-96`
- `ProductSellerService.java:44-74,245-267`
- `ProductContent.java:30-51`
- `Product.java:162-175,264-270`
- `ProductEventProducer.java:47-91`
- FE `lib/upload.ts` 및 상품 등록·수정 페이지의 업로드/취소 처리

### Presigned URL과 객체 key에서 확인한 사실

| 개념 | 현재 동작 |
| --- | --- |
| `GetObjectRequest` | bucket과 key로 어떤 객체를 GET할지 표현하며, 자체로 다운로드하지 않음 |
| `GetObjectPresignRequest` | GET 요청과 서명 유효시간 30분을 결합 |
| `PresignedGetObjectRequest` | 서명 완료 결과이며 최종 GET URL 제공 |
| `PutObjectRequest` | bucket·key·Content-Type으로 어떤 PUT을 허용할지 표현 |
| `PutObjectPresignRequest` | PUT 요청과 서명 유효시간 10분을 결합 |
| `PresignedPutObjectRequest` | 서명 완료 결과이며 FE가 사용할 PUT URL 제공 |
| Presigned URL 만료 | URL 사용 권한만 만료하며 이미 업로드된 S3 객체를 삭제하지 않음 |
| 실제 파일 전송 | BE를 거치지 않고 FE와 S3 사이에서 이루어짐 |
| PUT 완료 확인 | FE가 `fetch(PUT)`을 `await`하고 `response.ok`를 확인하므로 BE polling 불필요 |
| `Content-Type` | BE 서명값과 FE PUT header가 일치해야 하며, 현재 파일 내용 자체는 검증하지 않음 |

`S3Client`는 copy/delete처럼 서버가 S3 API를 직접 실행할 때 사용하고, `S3Presigner`는 실행 가능한
서명 URL을 만들 때 사용한다. 설정은 `spring.application.name=product-service`와
`SPRING_CONFIG_IMPORT=configserver:http://config:8888`을 통해 Config Server의
`configs/product-service.yml`을 병합한 뒤 `cloud.aws.*`를 `AwsS3Properties`에 바인딩한다. local
프로필은 Config Server를 끄고 `application-local.yml` 값을 사용한다. AWS 자격 증명은 코드에 넣지
않고 AWS SDK 기본 자격 증명 공급자 체인을 사용한다.

### S3 승격과 트랜잭션에서 확인한 실패 경로

| 실패 시점 | 현재 결과 | 판단 |
| --- | --- | --- |
| 첫 copy 실패 | DB 저장 전 중단, temp는 남음 | 중단은 맞지만 `S3_PRESIGN_FAILED` 재사용 오류 |
| 여러 파일 중 중간 copy 실패 | 앞 파일은 영구 승격·temp 삭제, 뒤 파일과 DB는 미처리 | 부분 영구 고아 객체 가능 |
| copy 성공 후 temp delete 실패 | 로그만 남기고 상품 등록 계속, temp·영구 객체 중복 | 등록 계속은 유지, Lifecycle 안전망 필요 |
| `ProductContent` 검증 실패 | S3 승격이 이미 끝나 영구 고아 객체 가능 | 순서 리팩터링 필요 |
| `save()` 즉시 실패 | 이미 승격한 S3 객체는 rollback되지 않음 | transaction rollback callback에서 현재 요청의 영구 객체 삭제 |
| 메서드 반환 뒤 DB commit 실패 | 메서드 내부 try/catch로 못 잡을 수 있고 S3는 rollback되지 않음 | 단순 catch 대신 afterCompletion rollback 정리, 프로세스 강제 종료는 별도 관측 |
| DB commit 후 Kafka send 실패 | DB는 DRAFT지만 이벤트 복구 레코드 없음 | I-1에서 별도 처리 |

S3와 PostgreSQL은 하나의 `@Transactional` 경계로 묶이지 않는다. S3의 이동은 rename이 아니라
`copy + delete`이며, copy는 동기 호출이고 delete 실패는 현재 로그만 남기는 best-effort다. 완전한
분산 트랜잭션이나 Saga를 도입하지 않고 검증 순서·소유권·Lifecycle·관측을 보강한다. 정상적으로
transaction completion callback이 실행되는 rollback은 현재 요청의 destination만 보상 삭제하고 temp는
유지한다. 프로세스 강제 종료처럼 callback 자체가 실행되지 않는 경우는 구조화 로그와 향후 reconcile
필요성 판단 대상으로 남긴다.

### `ProductContent`에서 확인한 규칙

```text
PROMPT    -> content 필수, file/external 금지
PPT/EXCEL -> file 필수, content/external 금지
NOTION    -> external 필수, content/file 금지
```

- request 경계는 `title`, `desc`, `amount >= 0`만 Bean Validation으로 검사한다.
- `ProductContent` compact constructor가 `imageUrls`·`tags` null을 빈 목록으로 정규화하고 위 조합을
  검사한다.
- 불일치 시 현재 `P007 PRODUCT_TYPE_FIELD_MISMATCH`/HTTP 400을 반환한다.
- `ProductContent`가 domain에서 HTTP 상태를 가진 `ProductException`을 던지는 것은 공통 계층 규칙과
  맞지 않아 순수 도메인 예외와 handler 매핑으로 바꿀 후보지만, 아직 별도 이슈 판정 전이다.
- Product 내부 `thumbnailUrl`·`imageUrls`·`fileUrl`에는 실제 URL이 아니라 영구 object key가 저장돼
  이름과 값의 의미가 어긋난다. `externalUrl`은 NOTION 외부 URL이므로 실제 URL이 맞다.
- 신규 Product는 `majorVersion=1`, `patchVersion=0`, `status=DRAFT`로 생성된다. 등록과 검수 요청을
  분리해 판매자가 검수 전 계속 수정할 수 있게 한 설계는 유지한다.

### 확정한 리팩터링 계약

```text
FileUploadController
  -> FileUploadUseCase
     -> FileUploadService
        -> FileUploadPolicy
        -> ObjectStorageGateway

ProductSellerService
  -> TempFilePromoter
     -> ObjectStorageKey 검증
     -> ObjectStorageGateway.copy/delete
```

목표 패키지와 타입:

```text
application/usecase/FileUploadUseCase.java
application/service/fileupload/
  FileUploadService.java
  FileUploadPolicy.java
  TempFilePromoter.java
application/gateway/external/
  ObjectStorageGateway.java
  ObjectStorageKey.java
infrastructure/external/s3/
  S3ObjectStorageAdapter.java
  S3Config.java
  AwsS3Properties.java
```

확정 사항:

- `StorageClient`를 제거하고 외부 연동 포트 `ObjectStorageGateway`로 교체한다. 현재
  `application/client`에는 이 파일만 있으므로 이동 후 패키지도 제거한다.
- application은 기술명 `s3`가 아니라 사용자 기능 `fileupload`로 묶는다. S3는 infrastructure 구현에만
  드러낸다.
- 공개 응답을 `tempObjectKey`, `presignedPutUrl`, `presignedGetUrl`로 변경한다.
- FE는 PUT·미리보기에 각각 URL을 쓰고 상품 등록·수정에는 URL이 아니라 `tempObjectKey`를 전달한다.
- 목표 temp key는 `products/temp/{sellerId}/{purpose}/{uuid}.{ext}`이며 seller·purpose를 검증한다.
- 확장자 없는 파일을 JPG로 추정하지 않고 `P008`/HTTP 400으로 거절한다.
- `UploadPurpose` enum과 `FileUploadPolicy`로 Controller의 문자열·중첩 분기를 제거한다.
- `ObjectStorageKey`가 key와 URL·일반 문자열을 타입으로 구분한다. AWS URL 재파싱은 제거한다.
- `TempFilePromoter`가 temp key 검증과 영구 승격 순서를 담당한다. `S3Helper`·별도 key factory는
  만들지 않는다.
- public endpoint 경로 `POST /api/v2/products/uploads/presigned-urls`는 유지한다.
- BE·FE 응답 계약 변경을 연결된 이슈로 관리하고 같은 배포 단위에서 반영한다.

### FE에서 확인한 취소·업로드 동작

- 신규 등록 페이지의 명시적 취소는 업로드한 URL 목록을 `DELETE /products/images`로 보내지만 실패는
  사용자 흐름을 막지 않고 삼킨다.
- 수정 페이지의 취소는 `/temp/`가 포함된 URL만 골라 삭제한다.
- 탭 닫기·새로고침·브라우저 비정상 종료를 처리하는 `beforeunload` 계열 로직은 확인되지 않았다.
- PUT 요청은 완료를 기다리고 실패 응답을 검사하며, 중복 submit 방지도 존재한다.
- 따라서 명시적 DELETE는 즉시 정리 수단으로 유지하고 S3 Lifecycle을 비정상 종료·삭제 실패의 최종
  안전망으로 추가해야 한다.

### 현재 테스트가 보장하는 것

- `FileUploadControllerTest`: PPTX와 이미지의 Content-Type, 잘못된 productType/확장자 조합 400,
  productType 누락 400, temp URL 삭제, 영구 URL 삭제 제외.
- `ProductContentTest`: PROMPT+file 실패, PPT 파일 누락 실패, NOTION external 성공, PPT file 성공,
  null list 정규화.
- `ProductSellerServiceTest`: PPT temp file copy 호출과 영구 key 저장, NOTION은 storage 미사용,
  생성 시 `PRODUCT_CHANGED` 발행 메서드 호출.
- 대상 product-service 테스트와 AI `ProductReviewRequestedConsumerTest`는 분석 중 실제 실행해 성공했다.

### 구현 시 추가할 테스트

- 확장자 없음·대문자·지원하지 않는 purpose/productType/확장자 조합.
- temp key에 sellerId/purpose 포함 및 타 seller·잘못된 purpose 거절.
- 응답의 세 필드와 FE의 PUT·미리보기·상품 등록 key 전달.
- 상품 유형 조합 검증 실패 시 copy가 한 번도 호출되지 않음.
- copy 실패 시 DB 저장 안 됨, delete 실패 시 등록 계속, 다중 파일 중간 실패의 관측 로그.
- PROMPT·PPT·EXCEL·NOTION의 성공/금지 조합 전체 매트릭스.
- 남겨 두는 Kafka 이벤트는 실제 Spring 트랜잭션에서 commit 전에는 전송되지 않고 commit 성공 후
  호출되는지 검증.

### 검색 색인 일관성 최종 판단 — scheduler-only

결정: **RDB를 단일 진실 공급원으로 두고 Elasticsearch는 수십 초 늦을 수 있는 검색 projection으로
운영한다. `PRODUCT_CHANGED` 실시간 Kafka 경로는 증분 재대사를 보완한 뒤 제거한다.**

허용하는 사용자 경험은 다음과 같다.

```text
신규 ON_SALE 상품이 검색에 나타남       -> 증분 재대사까지 지연 가능
STOPPED/DELETED 상품이 검색에 잠시 남음 -> 상세·주문에서 RDB 상태로 거절
검색 결과의 가격·상태가 잠시 오래됨     -> 상세·주문에서 RDB 최신값 재검증
```

상세 페이지뿐 아니라 주문 API도 `productId`, 현재 가격, `ON_SALE`, 삭제 여부와 구매 가능 여부를 RDB
기준으로 다시 검증해야 한다. ES 결과를 결제·주문 판단의 권위 데이터로 사용하지 않는다.

#### 20초 증분 재대사의 정확한 의미

현재 `@Scheduled(fixedDelay=20000)`은 이전 실행이 **끝난 뒤** 20초를 기다린다. 실제 반영 지연은
`재대사 실행 시간 + 최대 대기 약 20초 + ES refresh`이며 정확한 상한 20초가 아니다. 현재
`ProductReindexService`는 기존 embedding을 읽어 ES 입력을 만든 뒤 embedding을 refresh하므로 새 vector는
한 사이클 더 늦는 정도가 아니라, embedding update가 `updatedAt`을 바꾸지 않아 증분 대상에서 빠지고
일일 전체 스윕까지 반영되지 않을 수 있다. PR 5에서 refresh 결과 vector를 같은 bulk input에 바로 넣는다.

Elasticsearch의 near-real-time은 RDB polling과 별개다. 문서를 ES에 쓴 뒤 Lucene refresh가 검색에
보이게 만드는 시간이며 기본 Elastic Stack refresh interval은 일반적으로 1초다. 즉 현재 전체 지연은
"RDB 변경을 언제 ES에 쓸지"와 "쓴 문서가 언제 검색에 보일지"를 더한 값이다. 즉시
`refresh=true`는 작은 segment와 merge 비용을 늘리므로 동기 가시성이 꼭 필요하지 않은 이 흐름에서는
기본 비동기 refresh를 유지한다.

공식 근거:

- [Elasticsearch near-real-time search](https://www.elastic.co/docs/manage-data/data-store/near-real-time-search)
- [Elasticsearch refresh parameter와 비용](https://www.elastic.co/docs/reference/elasticsearch/rest-apis/refresh-parameter)
- [Elastic Connectors의 반복 full/incremental sync](https://www.elastic.co/docs/reference/search-connectors/connectors-ui-in-kibana)

#### 대표적인 RDB -> Elasticsearch 동기화 방식

| 방식 | 동작 | 장점 | 비용·한계 | 현재 판단 |
| --- | --- | --- | --- | --- |
| 주기적 전체/증분 polling | `updatedAt` 등 checkpoint 이후 변경을 조회하고 주기적으로 bulk 반영 | 단순, 원본 DB로 재구축 가능 | polling 지연, 삭제·시계·checkpoint 처리 필요 | **현재 채택** |
| application event + Outbox | 상태 변경과 Outbox를 같은 DB 트랜잭션에 저장하고 relay가 ES/Kafka 반영 | 낮은 지연, dual-write 유실 방지 | Outbox·relay·재시도·멱등성 운영 필요 | 현재 요구에는 보류 |
| log-based CDC | Debezium 등이 DB transaction log에서 commit된 insert/update/delete를 읽어 change stream 생성 | 낮은 지연, 삭제 포착, application 변경 최소 | Kafka Connect/connector/schema/offset 운영 필요 | 규모·SLA 상승 시 재검토 |
| 직접 dual-write | 요청 안에서 RDB와 ES를 차례로 갱신 | 처음 구현은 단순 | 두 시스템 사이 원자성 없음, 한쪽 실패 시 불일치 | 사용하지 않음 |

Debezium의 log-based CDC는 polling용 `lastUpdated` 컬럼이나 application dual-write 대신 PostgreSQL 등
DB의 transaction log에서 commit된 row 변경을 읽는다. 낮은 지연으로 insert/update/delete를 포착하고
connector offset부터 재개할 수 있어, 향후 데이터량 증가·다중 writer·수초 이내 검색 반영 요구가 생길
때 후보가 된다. 현재 규모와 수십 초 지연 허용 조건에서는 운영 구성요소를 추가하지 않는다.

공식 근거:

- [Debezium log-based CDC 기능](https://debezium.io/documentation/reference/2.7/features.html)
- [Debezium FAQ의 transaction log 변경 캡처](https://debezium.io/documentation/faq/)

#### 현재 워터마크의 안전장치와 한계

- `lastSucceededAt`은 JVM 메모리에 있고 성공한 사이클에서만 전진한다. 실패하면 이전 구간부터 다시
  읽는다.
- 매번 30초 overlap을 두어 commit 가시성 경계와 짧은 시계 차이를 중복 조회한다. 재대사는 멱등이라
  overlap 중복은 허용한다.
- 재시작하면 워터마크가 `null`이 되어 전체 재대사로 기준점을 다시 만든다.
- 하루 한 번 전체 스윕이 증분이 놓친 ES 고아 문서와 drift를 최종 보완한다.
- application의 `LocalDateTime.now()`를 `updatedAt`과 워터마크에 사용하므로 다중 파드 시계 차이가
  overlap 30초를 넘거나 transaction commit이 30초보다 오래 지연되면 증분 누락 가능성이 남는다.

#### `PRODUCT_CHANGED` 제거 전 필수 수정

1. `findFamilyRootIdsByProductUpdatedSince`의 `deletedAt is null`을 제거하거나 tombstone 변경 쿼리를
   별도로 둬 soft delete도 20초 증분 경로가 감지하게 한다. 현재 그대로 이벤트를 제거하면 삭제 문서는
   다음 일일 전체 스윕까지 ES에 남는다.
2. 리뷰 변경 감지의 `deletedAt is null`도 같은 이유로 수정해 리뷰 삭제 후 평균 평점을 갱신한다.
3. 리뷰가 child version에 연결될 수 있는지 확인한다. 가능하면 `r.product.id`가 아니라
   `coalesce(r.product.parentId, r.product.id)`를 반환해 family root 문서를 갱신한다.
4. `PRODUCT_CHANGED`의 생산·payload·검색 Consumer 분기·Handler와 관련 테스트를 함께 제거한다. 신규
   DRAFT 생성뿐 아니라 판매 중 PATCH도 같은 scheduler-only 정책으로 통일한다.
5. 승인·반려에는 새 `PRODUCT_CHANGED`를 추가하지 않는다. `updatedAt` 기반 증분 재대사가 처리한다.
6. 증분 실행 성공/실패, 소요 시간, 조회 family 수, upsert/delete 수와 마지막 성공 시각을 관측한다.
7. 삭제·승인·반려·판매 중 PATCH·리뷰 생성/수정/삭제가 정해진 지연 뒤 ES에 수렴하는 통합 테스트를
   추가한다.
8. 삭제·중지 증분 감지와 일일 전체 sweep의 ES 제거/재색인 테스트가 통과하면 검색 컨슈머의
   `PRODUCT_STOPPED`·`PRODUCT_DELETED` 분기도 제거한다. scheduler-only 최종 구조에서는 세 검색용
   변경 이벤트 모두 필요 없다.

#### 다중 파드 조건

현재 Kubernetes `product-service`는 `replicas: 1`이고 ShedLock·leader election·공유 checkpoint가 없다.
따라서 **현재 scheduler-only 설계는 product-service 단일 파드를 전제로 한다.** 여기서 replicas는
Elasticsearch shard replica가 아니라 product-service 애플리케이션 pod 수다.

`replicas > 1`이면 모든 pod가 자기 `@Scheduled`와 메모리 `lastSucceededAt`으로 실행한다. 그 결과
기동 시 전체 재대사, 같은 family bulk upsert/delete와 OpenAI embedding refresh가 중복되고, 서로 다른
시점의 결과가 ES를 덮을 수 있다. 지금 단일 파드에 미리 분산 제어를 넣지는 않되 scale-out 이슈의
선행 조건으로 다음 둘 중 하나를 선택한다.

**A. 분산 lock + 공유 checkpoint**

```text
모든 API pod에서 scheduler tick
  -> PostgreSQL/Redis의 같은 lock 획득 시도
  -> 한 pod만 lock 성공 후 재대사
  -> 마지막 성공 checkpoint를 DB에 저장
  -> lock 해제
```

ShedLock 같은 도구는 `@Scheduled` 실행 전에 공용 DB/Redis row를 선점해 동시 실행을 막는다. lock만
추가하고 워터마크를 JVM 메모리에 두면 실행 pod가 바뀔 때 checkpoint가 달라지므로, 마지막 성공 시각도
DB에 저장하거나 리더 변경 시 전체 재대사를 수행해야 한다. `lockAtMostFor`는 pod 장애 시 영구 lock을
막고, `lockAtLeastFor`는 너무 잦은 재실행을 제한한다. 긴 작업이 `lockAtMostFor`를 넘지 않도록 실행
시간을 관측해야 한다.

**B. 별도 단일 worker**

```text
product API Deployment (replicas N)
  -> scheduling profile 비활성화

product search-reconcile worker Deployment (replicas 1)
  -> 20초 scheduler와 checkpoint 소유
  -> RDB 조회 + ES bulk reconcile
```

API scale-out과 색인 worker를 분리해 scheduler 중복을 구조적으로 막는다. Kubernetes CronJob의 일반
cron 표현은 20초 간격에 적합하지 않으므로 현재 주기를 유지하려면 단일 replica worker Deployment가
더 자연스럽다. worker도 재시작하면 현재처럼 전체 재대사를 하거나 checkpoint를 DB에 저장한다. 처리량이
커지면 family 작업 큐와 여러 worker로 확장하되 family별 순서·중복을 별도로 설계한다.

현재 규모에서는 A/B를 구현하지 않고 `replicas: 1` 제약과 scale-out 선행 조건만 기록한다. 실제 API
수평 확장이 필요해지면 API와 색인 부하를 독립적으로 운영할 수 있는 **B 단일 worker**를 우선
추천하고, 배포 단위를 늘리고 싶지 않다면 **A 분산 lock + 공유 checkpoint**를 선택한다.

### 로드맵 항목을 GitHub 이슈로 확정하는 기준

대화에서 발견한 질문·코드 냄새·실패 가능성을 하나씩 GitHub 이슈로 만들지는 않는다. 지금은 분석
근거를 이 문서에 누적하는 단계이고, 전체 흐름을 확인한 뒤 아래 조건을 모두 만족하는 묶음만 이슈로
승격한다.

- 하나의 사용자 가치 또는 운영상 목적을 가진다.
- 완료 조건과 회귀 테스트를 독립적으로 설명할 수 있다.
- 다른 묶음과 분리해 배포하거나 되돌릴 수 있다.
- 동작 변경과 구조 변경을 한 PR에 섞지 않는 공통 규칙을 지킨다.
- 단순 학습 질문은 Study/Publish 근거로만 남기고 코드 변경 이슈로 만들지 않는다.
- 아직 실제 장애가 발생하지 않은 위험은 Troubleshooting으로 포장하지 않고 Decision 또는 Review의
  설계 근거로 남긴다.

현재 예상 묶음은 확정안이 아니다. 예를 들어 업로드 응답 계약·seller-scoped temp key는 동작/계약
이슈, `FileUploadService` 구조 정리는 후속 리팩터링 이슈, S3 Lifecycle은 인프라 이슈로
분리할 가능성이 높다. 검색은 먼저 scheduler 증분 정합성을 보완하는 이슈를 완료한 뒤
`PRODUCT_CHANGED` 제거 이슈를 진행해야 한다. 검수 흐름과 남은 테스트까지 확인한 후 최종 이슈
목록·선후관계·인수 조건을 확정하며, 사용자의 승인 전에는 GitHub 이슈를 생성하지 않는다.

### 검수 요청부터 AI 소비까지의 코드 근거

#### 1. 판매자의 명시적 검수 요청

`PATCH /api/v2/products/{productId}/inspection`은 API Gateway가 주입한 `X-User-Id`와 상품 ID를
`ProductSellerService.submitForReview`에 전달한다. 서비스는 판매자 소유 상품을 조회한 뒤 엔티티의
`Product.submitForReview()`를 호출한다. 두 메서드는 중복이 아니라 책임이 다르다.

- application service: 조회, 소유권 확인, 저장, 외부 이벤트 발행 순서를 조정한다.
- domain entity: `DRAFT` 또는 `REJECTED`에서만 `PENDING_REVIEW`로 갈 수 있다는 상태 규칙을 지킨다.
  재요청이면 기존 `rejectionReason`도 지운다.

따라서 상품 등록 시 DRAFT로만 저장하고 판매자가 준비를 마친 뒤 별도 요청하는 현재 제품 정책과
일치한다. 최초 요청뿐 아니라 판매 전 상품의 major 수정과 판매 후 새 major version 생성도
`publishReviewRequestedEvent`를 호출한다. 즉 “major 변경이면 재검수”도 실제 구현돼 있다.

#### 2. 중복 원본 상품 ID 계산

PROMPT 상품은 `ProductContentHash`로 만든 `contentHash`가 있을 때만 repository에서 동일 본문 상품을
찾는다. 결과인 `duplicateOfProductId`는 AI가 유사도를 다시 계산하도록 보내는 값이 아니다. exact hash
중복이 이미 확인됐다는 신호이며, AI 서비스는 이 값이 있으면 OpenAI를 호출하지 않고 즉시 중복
반려 판정을 만든다. 값이 없으면 정상 AI 검수로 진행한다. 이 빠른 경로 자체는 역할이 분명하므로
메서드를 없애기보다 이름과 테스트로 의도를 드러내는 편이 낫다.

#### 3. 이벤트에 상품 스냅샷과 URL을 넣는 이유

`PRODUCT_REVIEW_REQUESTED` payload는 productId, type, 이름, 설명, PROMPT 본문, tags, 썸네일/소개
이미지, 중복 원본 ID, 무료 여부를 담은 검수 시점 스냅샷이다. `free`는 고정값이 아니라
`product.amountType == FREE`의 계산 결과다. Reader Page 용도가 아니라 AI 검수 정책 입력이다.

이미지는 장식용 필드가 아니다. AI의 `SpringAiProductInspectionAgent.collectMedia()`가 썸네일과 소개
이미지를 실제 multimodal `Media`로 OpenAI 요청에 첨부한다. 그래서 S3 key가 아니라 AI가 읽을 수
있는 presigned GET URL을 발행한다. 현재 GET URL은 30분 유효하고 consumer 재시도는 1초 간격 2회라서
정상 검수와 자동 재시도에서 만료 문제는 없다. 오래된 이벤트를 수동 replay하는 기능은 현재
요구사항에 없으므로 URL 재발급 정책도 설계 범위에 넣지 않는다.

#### 4. 커밋과 발행 시점

서비스의 상태 저장과 이벤트 생성은 같은 `@Transactional` 호출 안에서 시작되지만
`ProductEventProducer`는 `TransactionSynchronization.afterCommit()`에 Kafka send를 등록한다. 따라서
DB commit이 실패하면 검수 이벤트를 보내지 않는 점은 보장한다. 반대로 DB commit 성공 후 send가
실패하면 복구 레코드가 없고, 현재 반환된 future의 실패 callback·구조화 로그도 없다. 이 문제는
AI consumer retry로 해결되지 않는다. consumer retry는 Kafka broker에 이벤트가 들어온 뒤 처리에
실패한 경우만 다룬다.

#### 5. AI consumer의 처리와 현재 재시도 보장

AI consumer는 `product-events`, consumer group `ai-service`에서 문자열 이벤트를 읽고 다음 순서로
처리한다.

1. `EventMessage<JsonNode>`로 역직렬화한다.
2. eventId/eventType 필수값을 검사한다.
3. `PRODUCT_REVIEW_REQUESTED`가 아니면 로그 후 acknowledge한다.
4. payload를 `ProductInspectionRequest`로 매핑한다.
5. exact 중복이면 OpenAI 없이 반려하고, 아니면 텍스트와 이미지를 OpenAI에 전달한다.
6. `PRODUCT_INSPECTION_COMPLETED`를 발행한 뒤 원 이벤트를 acknowledge한다.

예외가 나면 acknowledge하지 않고 `DefaultErrorHandler`가 1초 간격으로 2회 재시도한 뒤
`product-events.DLT`의 같은 partition으로 보낸다. 이는 최초 시도 포함 최대 3회 처리다. 그러나 현재
consumer에는 eventId 기반 processed-event 멱등성 저장이 없다. AI 호출 또는 결과 이벤트 발행 뒤
ack 이전에 실패하거나 동일 이벤트를 replay하면 OpenAI 호출과 결과 발행이 중복될 수 있다. 따라서
“로그에서 eventId를 찾아 한 번 더 전송”하기 전에 AI consumer 멱등성 또는 재처리 안전성을 먼저
갖춰야 한다.

#### 6. 이 단계에서의 잠정 판정

- 유지: DRAFT 등록과 명시적 검수 요청 분리, entity가 상태 전이를 소유하는 구조, exact-hash 중복의
  AI 호출 단축, 검수 스냅샷을 비동기로 전달하는 방향.
- 리팩터링/보완 후보: producer send 완료 실패 로그와 복구 가능한 발행 기록, AI consumer eventId
  멱등성, 이벤트 계약 테스트.
- 확정: 이번 product-service 범위에서는 Outbox를 도입하지 않고, send future 실패 로그와 장기
  PENDING_REVIEW 탐지 후 **최대 1회 자동 재발행**으로 제한한다.
- 후속: ai-service consumer 멱등성과 검수 회차 correlation은 이번 범위에서 구현하지 않는다.

### 보충 학습: 멱등성, S3/DB 실패, Builder

#### 멱등성 보완이 의미하는 것

멱등성은 같은 `eventId`의 이벤트를 두 번 받아도 비즈니스 처리는 한 번만 일어나게 만드는 성질이다.
단순히 eventId를 로그에 남기는 것만으로는 중복 OpenAI 호출을 막지 못한다. 전형적인 consumer 처리는
다음과 같다.

product-service에는 이미 `product_processed_event` 테이블과 `ProcessedEventRepository`가 있지만 모든
Kafka consumer에 자동 적용되는 공통 기능은 아니다. 현재 실제 사용처는 두 곳이다.

- `OrderEventHandler`: consumer group `product-service`로 `ORDER_PAID`/`ORDER_REFUND`의 판매량 중복
  증감을 막는다.
- `ProductSearchEventHandler`: consumer group `product-service-search`로 `PRODUCT_CHANGED`, 제거 후보
  이벤트의 ES 중복 반영을 막는다.

반면 `ProductInspectionResultConsumer`는 이 repository를 사용하지 않고, 이미 상태가 바뀌어
`IllegalStateException`이 나면 중복으로 간주해 acknowledge한다. ai-service의
`ProductReviewRequestedConsumer`에는 processed-event 테이블·repository 자체가 없다. 서비스별 DB를
분리하므로 product-service의 테이블을 ai-service가 직접 공유해서도 안 된다. AI 요청 소비 멱등성을
도입한다면 ai-service가 자기 스키마에 처리 이력을 소유해야 한다.

`product_processed_event` 테이블 전체를 삭제한다는 결론은 아니다. 검색을 scheduler-only로 전환해
Kafka 검색 handler를 제거하면 consumer group `product-service-search`의 처리 이력은 더 이상 필요하지
않고 관련 코드도 함께 제거할 수 있다. 그러나 `ORDER_PAID`/`ORDER_REFUND`는 판매량 증감이라는 비멱등
연산이므로 consumer group `product-service`의 처리 이력은 유지한다. Kafka는 consumer가 처리한 뒤
offset commit/ACK 전에 죽거나, ACK/offset commit이 실패하거나, 운영상 record가 재전송되면 이미 한
번 실행한 record를 다시 전달할 수 있기 때문이다. 이때 increment/decrement를 반복하면 판매량이
틀어진다.

따라서 최종 후보는 다음과 같다.

- 검색 이벤트 경로 제거 시 `ProductSearchEventHandler`의 processed-event 사용과 검색용 테스트 제거
- 주문 이벤트용 `product_processed_event` entity/table/repository 유지
- 테이블 이름을 inbox로 바꾸거나 범용 Inbox 패턴으로 확장하는 작업은 현재 요구사항에 포함하지 않음
- 장기적으로 주문 상태·주문별 적용 이력만으로 원자적 멱등성을 보장하도록 모델을 바꾸지 않는 한
  단순 테이블 삭제는 허용하지 않음

```text
이벤트 수신
  -> processed_event에서 eventId 조회
  -> 이미 완료됨: AI 호출 없이 acknowledge
  -> 없음: 처리 시작
  -> AI 검수 및 결과 이벤트 발행
  -> eventId 처리 완료 기록
  -> acknowledge
```

다만 `AI 호출 -> 완료 기록` 사이에 프로세스가 죽으면 외부 AI 호출 자체는 다시 일어날 수 있다. 완전한
exactly-once가 아니라 애플리케이션 수준의 중복 억제이며, 처리 상태(`PROCESSING/COMPLETED/FAILED`),
unique eventId와 결과 발행 상태를 함께 설계해야 한다. 현재 코드에는 이 저장소가 없으므로 consumer가
처리 후 ACK 전에 실패해 같은 record를 다시 받는 경우까지 방어할 것인지 도입 여부를 정해야 한다.

#### S3 copy가 실패할 수 있는 경우와 현재 결과

현재 `copyObject`는 동기 SDK 호출이므로 copy가 실패하면 예외가 즉시 발생하고 상품 저장까지 진행하지
않는다. 사용자는 전역 예외 handler가 매핑한 HTTP 실패 응답을 받고 FE가 "상품 등록에 실패했습니다"를
표시할 수 있다. 가능한 원인은 다음과 같다.

- temp source key가 없거나 이미 삭제됨
- product-service IAM role에 source read 또는 destination write 권한이 없음
- bucket/region/configuration 오류
- 네트워크 단절, AWS throttling 또는 일시적인 S3 장애
- 잘못된 source/destination key

현재 구현의 문제는 모든 copy 예외를 `S3_PRESIGN_FAILED`로 바꾼다는 점이다. presign 실패와 copy 실패는
원인·복구 방법이 다르므로 별도 error code가 필요하다. 또한 여러 파일 중 앞 파일 copy는 성공하고 뒤
파일이 실패하면 이미 복사된 영구 객체가 남을 수 있다. try/catch로 API 실패를 알리는 것과 부분 성공
객체를 정리하는 것은 서로 다른 문제다.

#### copy 성공 뒤 DB 저장이 실패할 수 있는 경우와 현재 결과

S3와 RDB는 하나의 트랜잭션으로 묶이지 않는다. 그래서 모든 파일 copy와 temp delete가 성공해도 다음
이유로 DB commit이 실패할 수 있다.

- unique/check/foreign-key/not-null 제약 위반
- 동시 수정에 따른 optimistic lock 또는 serialization/deadlock 충돌
- DB 연결 단절, timeout, failover, connection pool 고갈
- 트랜잭션 flush/commit 시점의 SQL 오류
- 애플리케이션 종료 또는 프로세스 장애

특히 `repository.save()` 호출이 돌아온 뒤 실제 transaction proxy의 commit/flush에서 실패할 수도 있어
service 메서드 내부의 단순 try/catch만으로 모든 실패를 잡고 정확히 보상하기 어렵다. 현재 순서는
`copy -> temp delete -> DB save/commit`이므로 이 경우 DB가 가리키지 않는 영구 S3 객체가 남는다. 이는
상품 DB 저장을 재시도하는 문제라기보다 영구 고아 객체 정리 문제다. 도메인 검증을 copy 전에 수행하고,
정상 rollback은 transaction completion callback으로 현재 요청 destination만 보상 삭제한다. copy 실패
error code와 구조화 로그도 분리한다. 애플리케이션 강제 종료처럼 callback이 실행되지 않아 남는 고아
객체의 실제 빈도를 관측한 뒤 영구 prefix reconcile job의 필요성을 결정한다.

#### Builder 문법과 Builder 패턴

Builder는 생성자 인자가 많은 객체를 이름 있는 단계로 조립하는 생성 패턴이다. AWS SDK의 request
객체와 Spring AI options가 이 패턴을 사용한다.

```java
GetObjectRequest request = GetObjectRequest.builder()
    .bucket(bucketName)
    .key(objectKey)
    .build();
```

- `builder()`: 아직 완성되지 않은 Builder 객체를 만든다.
- `.bucket(...)`, `.key(...)`: Builder 내부 필드를 설정하고 같은 Builder를 반환하므로 연쇄 호출할 수
  있다. 이를 method chaining이라고 한다.
- `.build()`: 설정값으로 최종 `GetObjectRequest` 객체를 만든다.

`GetObjectPresignRequest`는 이 완성된 `GetObjectRequest`와 서명 유효시간을 다시 감싸는 별도 요청이다.

```java
GetObjectPresignRequest presignRequest = GetObjectPresignRequest.builder()
    .signatureDuration(Duration.ofMinutes(30))
    .getObjectRequest(request)
    .build();
```

그 뒤 `s3Presigner.presignGetObject(presignRequest)`가 AWS 자격 증명으로 서명해
`PresignedGetObjectRequest`를 반환하며, `presigned.url()`에서 최종 URL을 얻는다. Builder의 장점은 긴
생성자의 인자 순서를 외울 필요가 없고 선택 필드를 읽기 쉽게 설정하며, 최종 request를 immutable하게
만들기 쉽다는 점이다. 단점은 클래스와 호출 코드가 늘고 필드가 적은 단순 객체에는 과할 수 있다는
점이다. 이 코드에서는 AWS SDK가 제공하는 공식 생성 API이므로 유지 대상이며 직접 생성자로 바꾸는
리팩터링 후보가 아니다.

### AI 판정 반환과 상품 상태 반영

#### 1. ai-service의 결과 이벤트 발행

`ProductInspectionService`는 exact 중복 반려 또는 OpenAI 판정으로 `InspectionVerdict`를 만든 뒤
`InspectionEventProducer.publish()`를 호출한다. producer는 새 eventId를 만들고 productId를 Kafka key로
사용해 `ai-events`에 `PRODUCT_INSPECTION_COMPLETED`를 보낸다. payload는 승인 여부, 반려 사유와 다음
7개 체크리스트를 포함한다.

- context가 있는가
- objective가 있는가
- nuance가 있는가
- tone이 있는가
- examples가 있는가
- execution 지시가 있는가
- role assignment가 있는가

ai-service는 이 과정에서 저장하는 도메인 DB 상태가 없으므로 AFTER_COMMIT 지연 없이 바로 발행한다.

#### 2. product-service consumer의 payload 변환

`ProductInspectionResultConsumer`는 consumer group `product-service`로 `ai-events`를 읽는다. eventId와
eventType을 검사한 뒤 지원 타입이면 payload를 productId, approved, rejectionReason,
`InspectionChecklist`로 변환해 현재 `ProductInspectionResultHandler.apply()`에 넘긴다. 누락된 boolean은
현재 `false` 기본값이 된다. 지원하지 않는 이벤트는 정상적으로 로그 후 ACK하고, 역직렬화·필수값
오류는 예외를 전파해 consumer retry/DLT 경로로 보낸다.

구조 리팩터링 시에는 `ProductInspectionUseCase`를 추가하고 구현체 이름을
`ProductInspectionService`로 바꾼다. consumer는 구현체가 아니라 usecase를 호출한다. 이 이름에서도
`application/service` 패키지가 계층을 나타내므로 `Application`을 붙이지 않는다. 공용 스킬·공용
규칙이나 다른 서비스 이름은 변경하지 않는 이 product-service 로드맵 내부 합의다.

#### 3. 승인·반려와 체크리스트 컬럼

handler는 상품을 조회하고 다음을 한 DB transaction에서 처리한다.

```text
approved=true
  -> Product.approve(checklist)
  -> PENDING_REVIEW -> ON_SALE
  -> 7개 has_* 컬럼 저장 + checklist_recorded=true
  -> 같은 ProductFamily의 기존 ON_SALE 버전을 SUPERSEDED로 교대

approved=false
  -> Product.reject(reason, checklist)
  -> PENDING_REVIEW -> REJECTED
  -> rejection_reason 저장
  -> 7개 has_* 컬럼 저장 + checklist_recorded=true
```

따라서 이전에 질문한 `hasContext`, `hasObjective`, `hasNuance`, `hasTone`, `hasExamples`,
`hasExecution`, `hasRoleAssignment`는 AI가 PROMPT 품질 요소별 충족 여부를 판정한 결과다.
`checklistRecorded`는 이 체크리스트가 실제 검수 결과로 한 번 기록됐는지를 구분한다. Java boolean의
기본값 `false`만 보면 “검수했는데 항목 미충족”과 “아직 검수하지 않음”을 구분할 수 없기 때문에
별도 플래그가 필요하다.

승인 시 과거 판매 버전을 `SUPERSEDED`로 바꾸는 이유는 같은 family에 `ON_SALE` 버전이 여러 개
누적되지 않게 하기 위해서다. 이 변경도 `updatedAt`을 갱신하므로 앞서 결정한 scheduler가 family를
다시 계산해 ES에 새 판매 버전을 반영한다.

#### 4. 현재 중복 처리 방식

이 consumer는 `product_processed_event`를 사용하지 않는다. 같은 결과가 다시 도착해도 첫 결과가
상품을 `ON_SALE` 또는 `REJECTED`로 바꾼 뒤에는 `Product.approve/reject()`의 PENDING_REVIEW guard가
`IllegalStateException`을 던지고 handler가 이를 정상 중복으로 로그 후 종료한다. 현재의 동일 결과
재전달에는 상태 기반 자연 멱등성이 동작한다.

다만 이벤트에는 검수 요청 ID나 상품 revision이 없고 productId만 있다. 현재 UI·consumer 순서와 즉시
검수 전제에서는 별도 correlation을 추가할 근거가 부족하므로 신규 이슈로 확정하지 않는다. 향후 같은
productId에 여러 검수를 병렬 허용하거나 오래된 결과를 재주입하는 요구가 생기면 그때 requestId/revision
일치 검증이 필요하다.

#### 5. 결과 발행 신뢰성의 실제 공백

`InspectionEventProducer`는 `kafkaTemplate.send()`가 반환하는 future를 무시한다. Kafka broker가 즉시
호출을 거부해 동기 예외를 던지면 AI consumer가 실패해 재시도할 수 있지만, send 호출이 반환된 뒤
비동기로 실패하면 실패 로그·재시도·복구 기록이 없다. `ProductInspectionService.inspect()`는 정상
반환하고 원래 `PRODUCT_REVIEW_REQUESTED`를 ACK할 수 있어 상품이 `PENDING_REVIEW`에 남을 수 있다.

이는 AI 처리 재시도와 별개다. 최소 보완 후보는 다음과 같다.

- send future 완료/실패를 관측하고 eventId·productId·eventType을 구조화 로그로 남김
- 원 이벤트를 ACK하기 전에 결과 전송 성공을 확인할지 결정
- 장기간 `PENDING_REVIEW` 상품 탐지와 재검수 요청 운영 방식을 결정
- Outbox는 ai-service가 현재 검수 상태 DB를 소유하지 않으므로 별도 저장소까지 추가할 가치가 있는지
  장애 빈도와 허용 지연을 보고 판단

#### 6. 현재 테스트가 보장하는 범위

- AI producer test: topic/key, EventMessage envelope, 승인·반려 payload와 체크리스트 필드
- product consumer test: 승인·반려 parsing, 체크리스트 7개 전달, 미지원 타입 ACK, eventId 누락 예외,
  handler의 `IllegalStateException` ACK
- handler test: 승인 ON_SALE, 반려 REJECTED와 사유, 체크리스트 저장, 이전 ON_SALE 버전 SUPERSEDED,
  이미 처리된 상태와 상품 없음의 no-op
- domain test: PENDING_REVIEW에서만 approve/reject 허용

아직 보장하지 않는 것은 실제 Kafka broker를 거친 end-to-end 전달, send future 비동기 실패, consumer
DB transaction과 ACK 경계, 동시 중복 delivery, scheduler가 승인·반려 후 ES에 수렴하는 통합 흐름이다.

### 상품 수정과 버전 유형 자동 판정 후보

#### 현재 API와 판정 방식

`PATCH /products/{productId}`의 `ProductUpdateRequest`는 전체 상품 필드, `changeReason`, 문자열
`versionType`을 받는다. 서버의 판정은 다음 한 줄뿐이다.

```java
boolean isMajor = "MAJOR".equalsIgnoreCase(request.versionType());
```

따라서 BE 계약상 client가 major/minor를 결정하며, `MINOR`, null뿐 아니라 오타와 알 수 없는 값도 모두
patch로 처리된다. enum validation이나 서버의 변경 필드 비교는 없다. 이 상태에서는 검수가 필요한
본문 변경도 client가 MINOR로 보내면 재검수를 건너뛸 수 있다.

실제 FE는 상태별로 다르게 동작한다. `app/edit/[id]/page.tsx`의 `isDraft`는 상태가 정확히 DRAFT인지
확인하며, DRAFT이면 버전 선택 UI와 변경 사유를 숨기고 PATCH 요청에서도 `versionType/changeReason`을
보내지 않는다. 따라서 정상 DRAFT UI에서 사용자가 MAJOR를 선택한다는 이전 분석은 잘못됐으며
정정한다. 이때 BE는 null을 minor로 보므로 DRAFT 저장마다 patchVersion은 증가하지만 상태는 DRAFT로
유지되고 검수 이벤트도 발행하지 않는다.

반면 REJECTED는 `isDraft=false`라서 FE에서 PATCH/MAJOR 선택 UI가 노출되고 두 필드를 전송한다. 최초
검수에서 반려된 상품은 한 번도 ON_SALE이 아니어도 REJECTED가 될 수 있다. 즉
`DRAFT -> PENDING_REVIEW -> REJECTED` 경로다. 이 REJECTED 편집에서 MAJOR를 선택하면 현재 BE가 같은
row를 PENDING_REVIEW로 전환하고 검수 이벤트를 즉시 발행하며, PATCH를 선택하면 REJECTED를 유지해
판매자가 내 상점의 `재요청` 버튼을 별도로 눌러야 한다.

#### 현재 상태별 수정 흐름

```text
family가 한 번도 판매되지 않음
  -> 기존 anchor row를 in-place update
  -> MAJOR면 major+1, patch=0, PENDING_REVIEW + 즉시 검수 요청
  -> MINOR면 patch+1, 현재 상태 유지

family에 ON_SALE/SUPERSEDED 이력이 있음
  -> 현재 ON_SALE을 기준으로 수정
  -> MAJOR: 새 child row, major+1.0, PENDING_REVIEW, 기존 판매본 유지, 즉시 검수 요청
  -> MINOR: 새 child row, 같은 major의 patch+1, 즉시 ON_SALE, 기존 판매본 SUPERSEDED
```

이미 `PENDING_REVIEW` major child가 있으면 새 major 생성을 거부한다. 가격이 바뀌면 버전 유형과 별개로
`PRODUCT_PRICE_CHANGED`도 발행한다. 판매 후 patch는 `PRODUCT_CHANGED`를 발행하지만 scheduler-only
결정이 구현되면 이 발행은 제거 대상이다.

가격 이벤트는 현재 별도 결함 후보가 있다. 메서드 마지막에서 모든 상태·버전 분기 뒤 가격 차이만
보고 발행하기 때문에 다음 경우에도 이벤트가 나간다.

- 아직 판매되지 않은 DRAFT 가격 수정
- 기존 ON_SALE 가격은 유지한 채 새 major child가 PENDING_REVIEW인 경우
- 새 child가 생성됐는데도 payload/aggregate ID는 요청 path의 기존 `productId`인 경우

확인 결과 order-service는 `PRODUCT_PRICE_CHANGED`를 파싱해 로그만 남기며 DB·장바구니·주문·캐시를
변경하지 않는다. 장바구니와 주문 생성은 product gRPC snapshot으로 현재 ON_SALE version과 가격을 다시
조회한다. 따라서 이 이벤트는 제거하기로 확정한다. version별 productId가 바뀌는 현재 모델에 억지로
family ID나 새 row ID를 넣어 계약을 연장하지 않는다.

`PRODUCT_STOPPED`와 `PRODUCT_DELETED`도 order-service에서는 로그만 남긴다. 현재는 product-service의
검색 컨슈머가 두 이벤트를 사용하지만 위 scheduler-only 전환이 완료되면 그 역할도 사라진다. 최종적으로
세 이벤트를 모두 삭제한다. 구현 순서는 **scheduler 삭제·중지 감지 보완 및 수렴 테스트 -> 검색 이벤트
분기 제거 -> order 로그 전용 consumer 제거 -> product producer/payload 제거 -> 공유 이벤트 문서 정리**다.
`PRODUCT_REVIEW_REQUESTED`는 AI 검수에 실제 사용되므로 유지한다.

#### 현재 제품 정책과의 불일치

최초 DRAFT는 FE에서 수정과 별도 검수 요청이 올바르게 분리돼 있다. 문제는 REJECTED 편집이 DRAFT와
다르게 버전 선택 UI를 노출한다는 점이다. 내 상점에는 별도 `재요청` 버튼도 있는데 REJECTED 편집에서
MAJOR를 선택하면 저장 자체가 재요청까지 수행하고, PATCH를 선택하면 저장 후 다시 재요청 버튼을 눌러야
한다. 같은 상태에서 선택값에 따라 UX가 달라져 일관되지 않다. 또한 FE를 우회한 직접 API 호출에서는
DRAFT에도 MAJOR를 보낼 수 있어 BE가 이를 허용한다.

자동 판정 도입 시에는 판매 전과 판매 후의 정책을 명시적으로 분리한다.

- 판매 전 DRAFT/REJECTED: 내용은 in-place 수정하되 상태를 유지하고 자동 검수 요청하지 않는다.
  판매자가 기존 `/inspection` API로 명시적으로 제출한다. FE의 REJECTED 버전 선택 UI도 제거한다.
- 판매 후: 서버가 변경 필드를 비교해 major/patch를 자동 결정한다. major는 새 PENDING_REVIEW 버전을
  만들고 기존 판매본을 유지하며, patch는 새 ON_SALE 버전으로 즉시 교대한다.

**확정 결정:** 이 불일치는 수정 대상이다. REJECTED row는 아직 판매본이 없는 최초 검수 반려와 판매 후
major 버전 반려에 모두 생길 수 있다. 다만 현재 seller 응답은 `currentForSeller()` 우선순위 때문에
기존 ON_SALE이 있으면 그 판매본을 대표로 반환한다. 따라서 상점에서 REJECTED 카드로 직접 드러나는
주요 경우는 최초 판매 전 반려다. 판매 후 반려 row는 version history에만 들어가며 현재 history 응답에는
product/version ID가 없어 그 row를 직접 편집할 수도 없다.

- 판매 이력 없음: DRAFT와 REJECTED 모두 편집 저장과 검수 요청/재요청을 분리한다. FE는 버전 유형
  선택을 노출하지 않고, 저장 후 내 상점의 검수 요청/재요청 버튼을 사용한다.
- 판매 이력 있음: 현재 판매본을 유지한 채 수정하며 서버가 major/patch를 자동 판정한다. 반려된 pending
  version을 다시 편집하는 세부 경로는 ProductFamily 분석에서 어느 row를 anchor로 삼을지 확정한다.
- BE는 FE가 보내는 `versionType`을 신뢰하지 않는다. 자동 판정 전 과도기에도 판매 이력 없는 상품의
  수정 API가 상태를 PENDING_REVIEW로 바꾸거나 검수 이벤트를 발행하지 않게 방어한다.
- 자동 판정 전 과도기에는 FE의 `isDraft` 단일 조건을 `DRAFT 또는 REJECTED`의 판매 전 편집 조건으로
  바꿀 수 있다. 최종 자동 판정에서는 버전 선택 UI 자체를 없애므로 별도 `hasEverBeenOnSale` 응답을
  UI 표시만을 위해 추가하지 않는다. BE는 내부 `ProductFamily.hasEverBeenOnSale()`로 분기한다.

이 변경은 FE-only가 아니라 BE 상태 정책·응답 계약·FE 표시를 함께 바꾸는 하나의 동작 변경 이슈
후보다. 구조 리팩터링과 같은 PR에 섞지 않는다.

### ProductFamily 구조와 현재 대표 버전 선택

#### root와 child는 버전 트리보다 한 묶음 식별자다

최초 Product는 `parentId=null`이고 자기 `id`가 family root다. 판매 후 새 version은 새 UUID를 받고
`parentId`에 최초 root ID를 직접 넣는다. child가 이전 child를 parent로 가리키는 연결 리스트가 아니다.

```text
Product 1.0  id=A, parentId=null       -> familyRootId=A
Product 1.1  id=B, parentId=A          -> familyRootId=A
Product 2.0  id=C, parentId=A          -> familyRootId=A
```

`Product.familyRootId()`는 `parentId != null ? parentId : id`이고, `isFamilyRoot()`는 `parentId == null`인지
확인한다. repository는 root ID들로 root/child row를 한 번에 조회하고 `ProductFamily.of(A, members)`로
묶는다. `ProductFamily`는 별도 DB entity가 아니라 이미 조회한 Product 목록에서 대표 상태와 버전을
고르는 도메인 객체다.

`familyRootId()`는 별도 컬럼 getter가 아니라 계산 메서드다. 이름은 family 전체의 동일 식별자를
정확히 표현하므로 유지한다. `isFamilyRoot()`도 boolean 질문으로 자연스럽다. 오히려 `parentId`가 직전
version ID처럼 보일 수 있지만 실제로는 모든 child가 최초 root ID를 저장한다. 지금 Java 필드·DB
컬럼을 바꾸면 repository query, migration, 테스트와 이벤트 계약까지 넓게 흔들리는 반면 동작 이점은
없으므로 이름 변경 이슈로 만들지 않는다. 대신 필드와 두 메서드에 “parent_id는 직전 버전이 아니라
family root 상품 ID”라는 주석과 예시를 추가하는 소규모 가독성 보완만 후보로 둔다.

#### Comparator와 stream.filter.max

`versionAscending()`은 major를 먼저 비교하고 같으면 patch를 비교한다.

```java
Comparator
    .comparingInt(Product::getMajorVersion)
    .thenComparingInt(Product::getPatchVersion)
```

개념상 `1.9 < 2.0 < 2.1` 순서다. `members.stream()`은 목록을 순차 처리 가능한 stream으로 만들고,
`filter(p -> p.getStatus() == status)`는 원하는 상태만 남기며, `max(comparator)`는 그중 가장 높은 버전을
`Optional<Product>`로 반환한다. 결과가 없을 수 있기 때문에 null 대신 Optional을 쓴다.

```text
members: 1.0 SUPERSEDED, 2.0 ON_SALE, 3.0 PENDING_REVIEW
filter(ON_SALE) -> 2.0만 남음
max(version)    -> Optional[2.0]
```

`versionDescending()`은 위 comparator를 `reversed()`해 history를 최신 버전부터 정렬한다.

#### 메서드별 의미

| 메서드 | 선택 규칙 | 실제 사용 |
| --- | --- | --- |
| `currentOnSale()` | ON_SALE 중 최고 버전 | 공개 조회, 주문/gRPC, ES 대표, 수정 기준 판매본 |
| `pendingReview()` | PENDING_REVIEW 중 최고 버전 | 동시에 두 major 검수가 생기는 것 방지 |
| `currentForSeller()` | PENDING_REVIEW → ON_SALE → REJECTED → DRAFT → STOPPED, 각 상태 최고 버전 | 판매자 목록·상세 대표 row |
| `currentForWishlist()` | ON_SALE → STOPPED → REJECTED → PENDING_REVIEW → DRAFT | 찜 목록에서 가능한 대표 선택 |
| `hasEverBeenOnSale()` | ON_SALE 또는 SUPERSEDED가 하나라도 존재 | 수정이 in-place인지 새 version인지 분기 |
| `publicHistory()` | ON_SALE/SUPERSEDED만 최신순 | 구매자에게 공개 가능한 버전 이력 |
| `sellerHistory()` | 모든 상태 최신순 | 판매자 상세 버전 이력 |

#### BE 응답과 FE 편집이 실제로 고르는 row

판매자 상세 API는 path의 productId로 anchor를 찾은 뒤 family 전체를 다시 조회하고
`currentForSeller()`를 대표로 반환한다. 응답의 `productId`, status, content, version은 path anchor가
아니라 이 대표 row 값이다. FE 편집 화면은 그 응답으로 폼을 채우지만 PATCH URL은 원래 route의 `id`를
그대로 사용한다.

```text
GET /products/{routeId}/sellers/me
  -> response는 currentForSeller 대표 row

PATCH /products/{routeId}
  -> BE는 routeId row를 anchor로 다시 family를 찾음
  -> 판매 이력이 있으면 실제 수정 기반은 anchor가 아니라 family.currentOnSale()
```

예를 들어 2.0 ON_SALE과 3.0 REJECTED가 함께 있으면 seller 대표는 우선순위상 2.0 ON_SALE이다. 반려
3.0은 `versions` 배열에만 보인다. 그런데 `SellerProductVersionResponse`에는 version 문자열과 상태만 있고
productId가 없어 FE가 3.0을 선택해 다시 편집할 수는 없다. 현재 수정 서비스도 판매 이력이 있으면
항상 `currentOnSale()`에서 새 version을 만들기 때문에 기존 반려 row를 수정하는 모델이 아니다.

#### 이 단계의 유지·보완 판정

- 유지: root에 모든 child가 직접 연결되는 family 모델, version comparator, `currentOnSale`, 판매 후
  새 row 생성과 기존 판매본 보존.
- 리팩터링 후보: 화면별 상태 우선순위가 코드에만 박혀 있으므로 이름·테스트·주석으로 대표 선택 계약을
  더 분명히 함. 현재 테스트는 주요 우선순위와 history 정렬을 보장하므로 대규모 분해는 불필요하다.
- **확정 — 판매 후 major 반려본 수정 후 재제출:** 반려된 major row를 버리고 현재 ON_SALE에서 또 새
  major를 만들지 않는다. 같은 반려 row의 콘텐츠만 수정하고 기존 변경 사유와 version 번호는 유지하며,
  판매자가 재요청하면 그 row를 REJECTED -> PENDING_REVIEW로 돌려 같은 major를 다시 검수한다.

#### 판매 후 major 반려본 재수정 확정 흐름

```text
2.0 ON_SALE
  -> 자동 판정 MAJOR 수정
  -> 3.0 PENDING_REVIEW 생성, 2.0은 계속 판매
  -> AI 반려
  -> 3.0 REJECTED, 2.0은 계속 판매
  -> 판매자가 3.0 수정(버전 증가 없음, 자동 검수 없음)
  -> 별도 재요청
  -> 3.0 PENDING_REVIEW
  -> 승인
  -> 3.0 ON_SALE, 2.0 SUPERSEDED
```

이를 위해 현재 구조에서 다음 최소 동작 변경이 필요하다.

- `currentForSeller()` 우선순위를 `PENDING_REVIEW -> REJECTED -> ON_SALE -> DRAFT -> STOPPED`로 바꿔
  판매자가 조치해야 하는 최신 반려 row를 대표로 선택한다. 그러면 기존 판매자 목록 응답의 productId가
  곧 3.0 REJECTED ID가 되므로 별도 workingProductId가 없어도 수정 버튼이 올바른 row로 이동한다.
- 기존 판매자 상세에는 이미 `liveVersion`이 있으므로 FE는 대표 3.0 REJECTED와 `liveVersion=2.0`을
  함께 표시할 수 있다. 목록 카드에도 “기존 버전 판매 중” 표시가 꼭 필요하면 boolean 또는 liveVersion
  하나만 추가한다. 전체 working-version DTO와 `versions[].productId`는 핵심 기능에 필수가 아니다.
- 수정 service는 anchor가 REJECTED working version이면 `currentOnSale().createNextVersion()`을 호출하지 않고
  해당 row를 in-place revise한다. major/patch 번호와 REJECTED 상태는 유지한다.
- 저장만으로 재검수를 발행하지 않는다. 기존 `/inspection` 재요청이 같은 3.0을 PENDING_REVIEW로
  전환하고 이벤트를 발행한다.
- 모호한 `Product.update(..., boolean isMajor)`는 제거한다. DRAFT 보정은
  `updateDraftContent(ProductContent)`, REJECTED 보정은
  `updateRejectedContent(ProductContent)`로 상태와 행위를 메서드 이름에 직접 드러낸다. 두 메서드는
  version과 변경 사유를 바꾸지 않으며 각각 허용 상태를 내부에서 검사한다.

이 선택은 현재 B 방식보다 분기와 테스트가 조금 늘지만 기존 응답·버튼·재요청 API를 대부분 재사용할
수 있어 큰 재설계는 아니다. 반려된 변경을 고쳐 재검수한다는 사용자 mental model과 version history도
일치한다. 자동 버전 판정과는 같은 제품 흐름이지만 검토 가능성을 위해 선행 동작 이슈로 분리한다.

#### PR 2 구현 인계 — 판매 후 REJECTED major row 동일 version 재편집

##### 현재 코드에서 실제로 끊기는 지점

- FE `app/shop/page.tsx`에는 REJECTED 카드의 `수정`과 `재요청` 버튼이 이미 있다. 카드 자체가 클릭
  불가능한 것이 핵심 문제가 아니다.
- `ProductFamily.SELLER_PRIORITY`가 `PENDING_REVIEW -> ON_SALE -> REJECTED` 순서라서 2.0 ON_SALE과
  3.0 REJECTED가 함께 있으면 판매자 목록·상세가 2.0을 대표로 반환한다. 결과적으로 3.0의 버튼이 화면에
  나타나지 않는다.
- 3.0 ID로 수정 요청을 보내도 현재 `ProductSellerService.updateProduct()`는 판매 이력이 있다는 이유로
  `family.currentOnSale()`인 2.0을 기준으로 새 row를 만든다. MAJOR 선택 시 4.0 PENDING_REVIEW,
  PATCH 선택 시 2.1 ON_SALE이 되어 반려된 3.0을 보정하지 못한다.
- FE 수정 화면은 `isDraft`만 구분하고 `REJECTED`를 별도로 분기하지 않는다. 그 결과 REJECTED가 실제로
  ON_SALE인 것은 아니지만, `!isDraft` 조건 아래 있는 ON_SALE 새 version용 PATCH/MAJOR 선택과
  `changeReason` 필수 UI가 REJECTED에도 의도치 않게 노출되고 저장 성공 문구도 새 version 생성처럼
  표시된다.

##### 확정 동작

```text
2.0 ON_SALE + 3.0 REJECTED
  -> 판매자 목록 대표는 3.0 REJECTED
  -> 수정 버튼 route는 /edit/{3.0 productId}
  -> GET seller detail도 3.0 내용 + liveVersion=2.0 반환
  -> PATCH는 3.0 row의 콘텐츠만 수정
  -> version=3.0, status=REJECTED, 기존 changeReason·rejectionReason 유지
  -> Kafka 검수 요청 발행 없음
  -> 2.0은 계속 ON_SALE
  -> 판매자가 별도 재요청
  -> 같은 3.0 row가 PENDING_REVIEW, rejectionReason 초기화, 검수 이벤트 1회 발행
  -> 승인 시 3.0 ON_SALE, 2.0 SUPERSEDED
```

`SELLER_PRIORITY`는 `PENDING_REVIEW -> REJECTED -> ON_SALE -> DRAFT -> STOPPED`로 바꾼다. 판매자가
조치해야 할 pending/rejected working row를 정상 판매본보다 먼저 보여주되, 공개 조회·주문·찜의 대표
선택 규칙은 변경하지 않는다.

`Product.updateRejectedContent(ProductContent)`는 REJECTED 상태만 허용하고 `applyContent()`와
`updatedAt`만 갱신한다. product ID, parent ID, major/patch version, status, 기존 changeReason과
rejectionReason은 유지한다. checklist는 직전 반려 결과로 남겨두고, 다음 AI 결과가 도착하면 기존
승인·반려 처리에서 덮어쓴다. 다른 상태에서 호출하면 기존 상태 가드 방식대로 거절한다.

`ProductSellerService.updateProduct()`는 소유권 확인 직후 anchor가 REJECTED인지 먼저 판단한다. 해당
분기에서는 요청 콘텐츠를 검증하고 PR 1의 `TempFilePromoter`로 변경된 temp object만 같은 rejected
product ID 경로에 승격한 뒤 `updateRejectedContent()`를 호출한다. `family.currentOnSale()`에서 새 row를
만들거나 기존 2.0을 SUPERSEDED로 바꾸거나 검수 이벤트를 발행하지 않는다.

FE는 `isRejected = prompt.status === 'REJECTED'`를 명시적으로 구분한다. REJECTED 수정에서는 버전 선택
UI와 changeReason 입력을 숨기고 두 값을 PATCH body에 보내지 않는다. 헤더는 `v3.0을 수정합니다`, 저장
성공은 `반려된 상품을 수정했어요 · 내 상점에서 다시 검수를 요청해 주세요`처럼 같은 version 보정과
별도 재요청을 드러낸다. 기존 shop의 `재요청` 버튼과 endpoint는 유지한다.

##### 변경 파일 경계

```text
product-service/src/main/java/com/prompthub/product/
  domain/model/entity/ProductFamily.java
  domain/model/entity/Product.java
  application/service/ProductSellerService.java

product-service/src/test/java/com/prompthub/product/
  domain/model/entity/ProductFamilyTest.java
  domain/model/entity/ProductTest.java
  application/service/ProductSellerServiceTest.java
  application/service/ProductInspectionResultHandlerTest.java

FE
  app/shop/page.tsx
  app/edit/[id]/page.tsx
```

Controller endpoint, request URL, Kafka payload, DB schema, seller history DTO와 공개 상품 API는 변경하지
않는다. PR 1에서 판매자 상세 object key 계약이 추가됐다면 PR 2는 그 타입을 그대로 사용한다.

##### 필수 테스트와 회귀 확인

- `ProductFamilyTest`: `PENDING_REVIEW > REJECTED > ON_SALE` 우선순위와 REJECTED가 없을 때 ON_SALE
  대표 선택 유지.
- `ProductTest`: REJECTED content 수정 후 ID·3.0·상태·changeReason·rejectionReason 유지, 다른 상태 호출
  거부.
- `ProductSellerServiceTest`: 2.0 ON_SALE + 3.0 REJECTED에서 seller 목록·상세가 3.0을 반환하고
  `liveVersion=2.0`; 3.0 수정 시 같은 객체 한 건만 저장하며 2.0과 이벤트 publisher는 변경하지 않음.
- 재요청 테스트: 수정 직후에는 REJECTED, 별도 `submitForReview(3.0 ID)` 후에만 PENDING_REVIEW가 되고
  검수 이벤트가 정확히 한 번 발행됨.
- 승인 회귀: 재요청된 3.0 승인 시 3.0 ON_SALE과 2.0 SUPERSEDED. 반려 시 다시 같은 3.0 REJECTED.
- FE 수동 회귀: 반려 탭에 3.0 카드와 사유·수정·재요청 표시, 수정 화면에는 3.0 내용과 2.0 판매 중
  정보 표시, version 선택·변경 사유 없음, 저장 후 재요청 전까지 판매 중인 공개 2.0 상세·구매 정상.
- 기존 DRAFT 수정·검수 요청, PENDING_REVIEW 수정 차단, ON_SALE 수정 동작은 baseline과 동일해야 한다.

##### 구현 후 검증 결과

상태: **MERGED · DEPLOYED** · 확인일: 2026-08-11 · 기준: `develop d0aacd91`(PR 1 머지 직후) · 이슈:
[#718](https://github.com/prgrms-be-adv-devcourse/beadv6_6_3JMT_BE/issues/718) · PR:
[#719](https://github.com/prgrms-be-adv-devcourse/beadv6_6_3JMT_BE/pull/719)

**PR·머지·배포**

- BE PR [#719](https://github.com/prgrms-be-adv-devcourse/beadv6_6_3JMT_BE/pull/719)
  (`fix/#718-rejected-major-same-version-edit` → `develop`), merge commit `bbd02001a`. 커밋 3건을
  목적별로 분리: `fix`(구현) `49e0ba16` · `test`(테스트) `72e0bcbf` · `docs`(이 절 갱신 전 버전)
  `0916555b`.
- FE는 이슈·PR 없이 `beadv6_6_3JMT_FE` `main`에 직접 push하는 레포 컨벤션에 따라 커밋
  `392902f`로 별도 반영.
- `develop` push로 CD(`Release - Develop`)가 자동 트리거돼 개발서버에 배포 완료(총 약 11분,
  Plan → Build&Test → CI Gate → Docker Build → Deploy Applications 전부 success). 과거 셀프호스티드
  러너 ruby 부재로 인한 `exit 127`(#679) 재발 없음 — 이번 실행은 정상 소요 시간이었다(직전 PR 1
  배포는 동일 원인으로 추정되는 지연으로 2시간 36분 걸렸다).

**BE/FE 테스트**

- `ProductFamilyTest`·`ProductTest`·`ProductSellerServiceTest`·`ProductInspectionResultHandlerTest` 타겟
  실행: 통과.
- `./gradlew.bat :product-service:build`(checkstyle + 전체 테스트): **BUILD SUCCESSFUL**. checkstyle 경고
  16건은 전부 `build/generated/sources/proto`(protobuf 자동 생성 코드)에서 나온 기존 이슈이며 이 PR과
  무관하다.
- FE `npm run lint`(`app/edit/[id]/page.tsx`, `app/shop/page.tsx`): 통과.
- FE `npm run build`: 통과.
- Codex 리뷰: No findings.
- PR CI(`product_service_ci / Build & Test`): pass(3분 18초), `ci-gate`: pass. `mergeStateStatus`:
  `CLEAN`.
- `app/shop/page.tsx`는 실제로는 변경하지 않았다 — REJECTED 카드의 수정·재요청 버튼은 이미 있었고,
  BE 대표 선택 우선순위만 고치면 그 카드가 노출되는 구조였다(§"현재 코드에서 실제로 끊기는 지점" 분석과
  일치).

**배포 환경 E2E (2026-08-11, 실제 개발서버)**

실제 판매 데이터를 건드리지 않기 위해 자체 생성한 테스트 상품으로 아래 전체 사이클을 재현했다.
승인/반려는 admin 수동 처리가 아니라 **ai-service가 Kafka 이벤트로 자동 판정**한다(설계 문서
"검수 요청부터 AI 소비까지의 코드 근거" 절과 일치) — E2E 도중 admin 콘솔의 "검수 대기" 큐를
확인했으나 AI가 수초 내에 먼저 처리해 항상 0건이었다.

```text
1.0 등록 → 검수 요청 → AI 승인 → 1.0 ON_SALE
  → 1.0에 저품질 콘텐츠로 MAJOR 수정 제출 → AI가 반려("의미 없는 문자열") → 2.0 REJECTED
  → 내 상점 목록: 2.0 REJECTED가 대표로 노출(사유·수정·재요청 버튼) — 1.0에 가려지지 않음
  → 2.0 수정 화면: "v2.0을 수정합니다 · 현재 v1.0이 판매 중이에요", 버전 선택·변경 사유 UI 없음
  → 저장 → 같은 2.0 row 그대로 REJECTED 유지(반려 사유도 그대로, 이벤트 미발행)
  → 공개 상세(productId=1.0): 정상 조회, 1.0 그대로 판매 중
  → 재요청 → 같은 2.0 row가 PENDING_REVIEW로 전환(새 row 생성 안 됨)
  → (재시도 1회차) AI가 테스트 문구 자체를 스팸으로 재반려 → 같은 2.0 row REJECTED 유지 확인
  → 정상 콘텐츠로 재편집 → 재요청 → AI 승인
  → 2.0 ON_SALE 전환, 공개 상세 `버전 기록: v2.0`으로 대표 교체(1.0 SUPERSEDED)
```

설계한 상태 전이(§"확정 동작")와 실제 배포 환경 동작이 전부 일치했다.

**CodeFlow Receipt**

PR #719 review-receipt: `LOC +70214` · `functions +1027` · `dead code 0` · `circular deps 0` ·
`blast radius 575 → 863 ▲` · `health C` · `avg coupling +0.4`. 실제 diff는 7개 파일 155줄
추가뿐이라 이 수치와 명백히 모순된다 — 이 레포 CodeFlow의 기존에 알려진 결함(점수가 코드 변화에
무반응하거나 카드 갱신이 깨지는 문제)의 연장으로 판단해 **신뢰하지 않았고 Publish 근거로 쓰지
않았다.** 실제 지표 개선을 신뢰성 있게 관측하기 전까지는 이 Receipt를 인용하지 않는다.

**품질(§측정 기준 14개 카테고리 rubric) 비교 — 변경 3개 main 파일 기준**

| 파일 | 지표 | PR2 이전 | PR2 이후 |
| --- | --- | --- | --- |
| `Product.java` | LOC | 321(이미 300줄 초과) | 330 |
| `ProductFamily.java` | LOC / 메서드 수 | 108 / 16개(이미 15개 초과) | 110 / 16개 |
| `ProductSellerService.java` | LOC / 메서드 수 | 264 / 13개 | 272 / 13개 |
| `updateProduct()` | 메서드 길이 | 약 54줄(이미 50줄 초과) | 약 62줄 |

신규 위반: High 0 / Medium 0 / Low 0. `updateRejectedContent()`의 `IllegalStateException`은
`domain-model.md`의 도메인 순수 예외 컨벤션과 형식은 다르지만, 같은 클래스의 `supersede()`·
`approve()`·`reject()`가 이미 쓰는 기존 패턴을 그대로 따랐을 뿐이라 이 PR이 새로 만든 격차는 아니다.

점수 산식(`100 − 5×High − 2×Medium − 0.5×Low − 비대클래스 − 긴메서드 − 중복`) 기준으로 이 PR이 새로
건드는 항목은 없다 — 비대클래스·긴메서드 감점은 PR2 이전부터 이미 적용되던 상태를 그대로 물려받았을
뿐이다. **diff 기준 품질 delta는 0**(신규 위반 없음, 기존 구조 부채도 고치지 않음 — 범위를 그대로 두라는
지시에 따라 의도적으로 손대지 않았다). `product-service` 전체 절대 점수는 이 PR 범위에서 다시 산정하지
않았다 — I-1~I-9 각 항목의 개별 회복분 추정치만 이 문서에 기록돼 있고 전체 합산 baseline 점수는 별도로
계산된 적이 없다. (Claude가 diff 기준으로 측정한 결과이며, 별도의 자동 채점 스크립트는 없다.)

**설계와 달라진 부분**

- 이 절 바로 앞 §"판매 후 major 반려본 재수정 확정 흐름"(브레인스토밍 단계 초안)은
  `Product.update(..., boolean isMajor)`를 없애고 `updateDraftContent()` /
  `updateRejectedContent()` 두 메서드로 쪼개자고 제안했었다. **실제 구현은 확정본인 이
  "PR 2 구현 인계" 절만 따라 `updateRejectedContent()`만 추가했고 `update()`는 손대지 않았다** —
  "변경 파일 경계"에도 `update()` 제거·`updateDraftContent()` 신설이 없어, 확정 단계에서 그
  초안보다 범위를 의도적으로 좁힌 것으로 판단했다. 초안대로 더 넓게 리팩터링할지는 별도 판단이
  필요하다.
- 승인/반려 주체를 처음엔 "admin 수동 처리"로 가정하고 admin 콘솔 접근을 시도했으나, 실제로는
  ai-service가 Kafka로 자동 판정한다는 걸 E2E 도중 재확인했다(§"배포 환경 E2E" 참고). 설계 문서
  자체의 "검수 요청부터 AI 소비까지의 코드 근거" 절과는 일치하므로 설계 오류는 아니고, 구현 시점의
  가정 착오였다.
- 나머지(SELLER_PRIORITY 순서, `updateRejectedContent()` 계약, `updateProduct()` 분기,
  Controller·API·Kafka payload·DB schema 미변경)는 설계 그대로 구현했다.

##### 이슈 본문 초안

**제목**: `fix(product): 판매 후 반려된 major 버전을 동일 version으로 재편집`

**배경**: 판매 중인 2.0과 반려된 3.0이 함께 있으면 판매자 대표 선택이 2.0을 우선해 3.0 수정 UI가
숨겨진다. 3.0 ID로 요청하더라도 수정 service가 2.0에서 새 version을 만들어 반려본을 고쳐 다시
검수받는 흐름이 성립하지 않는다.

**목표**: 판매자 화면에서는 조치가 필요한 3.0 REJECTED를 대표로 노출하고, 수정 시 같은 row·version을
보정한다. 저장과 재검수 요청은 분리하며 기존 2.0은 3.0 승인 전까지 계속 판매한다.

**범위**: seller 대표 우선순위, REJECTED 전용 domain 행위, seller update 분기, FE REJECTED 수정 UI와
관련 테스트. 자동 MAJOR/PATCH 판정, family-version unique index, Kafka 재발행 정책은 제외한다.

**완료 조건**: 3.0 동일 row 수정, 수정 시 이벤트 없음, 별도 재요청 1회, 승인 시에만 2.0 교대,
BE 전체 테스트와 FE lint/build·화면 회귀에서 baseline 대비 신규 실패 없음.

**확정:** Product는 생성 순간 `majorVersion=1`, `patchVersion=0`으로 시작한다. null인 것은 버전 값이
아니라 FE가 DRAFT 수정 요청에서 생략하는 `versionType`이다. 판매 전 DRAFT 수정은 같은 row의 1.0을
유지하고, 최초 검수 반려 후 REJECTED 수정도 재제출 전까지 같은 version 번호를 유지한다. 최초 승인된
1.0 이후의 판매 상품 변경부터 patch/major version을 올린다. 초안 편집 횟수와 반려 보정 횟수는 상품
version history로 만들지 않는다.

#### 자동 판정 정책의 추천안

자동 판정은 문자열 비교가 아니라 기존 판매본과 요청으로 만든 `ProductContent`의 필드 차이를 비교해
`MAJOR` 또는 `PATCH`를 결정한다. 의미 없는 저장은 별도 version 판정값으로 모델링하지 않는다. FE가
초기 값과 현재 폼을 비교하는 dirty check로 저장 버튼을 비활성화하고, BE도 동일 내용 요청이면 새 row,
이벤트, S3 승격 없이 조용히 no-op 처리한다.

별도 `ProductVersionPolicy` 클래스는 만들지 않는다. 기존 값과 상품 유형을 이미 소유한 `Product`에
`determineVersionType(ProductContent)`을 두고 `Optional<ProductVersionType>`을 반환한다. `MAJOR`와 `PATCH`만
enum으로 표현하며 `Optional.empty()`는 별도 NO_CHANGE 상태가 아니라 판정할 변경 자체가 없다는 뜻이다.
productType 불일치는 이 메서드에서 먼저 거절한다.

#### Product 엔티티 책임과 메서드 네이밍 확정

`Product`는 필드가 많지만 Repository 조회, Kafka, S3, HTTP, 다른 서비스 호출을 포함하지 않고 자신의
상태 전이·콘텐츠·버전·검수 결과만 소유한다. 가족 전체 선택 규칙도 이미 `ProductFamily`에 분리되어
있으므로 LOC만 줄이기 위해 `ProductStatusManager`, `ProductVersionManager`,
`ProductInspectionChecklistManager` 같은 service로 쪼개지 않는다. 이는 빈약한 도메인 모델과 규칙
분산을 만들 가능성이 더 크다.

대신 한 메서드가 여러 상태와 결과를 boolean으로 결정하는 모호함을 제거한다.

| 확정 메서드 | 책임 |
| --- | --- |
| `updateDraftContent(ProductContent)` | DRAFT 1.0을 같은 row·version에서 보정 |
| `updateRejectedContent(ProductContent)` | 반려된 working version을 같은 row·version에서 보정 |
| `determineVersionType(ProductContent)` | 기존 판매본과 새 내용을 비교해 MAJOR/PATCH 또는 무변경 판정 |
| `createNextVersion(UUID, ProductVersionType, ProductContent, String)` | application이 만든 ID로 다음 version Product 생성 |
| `markAsSuperseded()` | 기존 ON_SALE version을 교대 완료 상태로 변경 |
| `stopSelling()` | 판매 중단 상태 전이 |
| `approveInspection(InspectionChecklist)` | AI 검수 승인과 체크리스트 반영 |
| `rejectInspection(String, InspectionChecklist)` | AI 검수 반려 사유·체크리스트 반영 |

각 메서드는 허용 상태를 내부에서 검사한다. `applyContent()`와 `applyInspectionChecklist()`는 함께
변경돼야 하는 필드를 한곳에서 적용하는 private helper로 유지한다.

별도 소규모 구조 이슈로 `Product`가 infrastructure의 `TagsConverter`를 import하는 역방향 의존을
제거한다. 이 converter는 `tags`뿐 아니라 `imageUrls`에도 쓰이므로 `StringListConverter`처럼 실제
역할이 드러나는 이름으로 바꾸고 domain이 infrastructure 패키지를 참조하지 않는 위치로 이동한다.

수정 흐름은 S3 승격보다 판정이 앞선다. 요청 URL을 object key로 정규화하되 copy/delete하지 않은 상태로
기존 상품과 비교하고, 무변경이면 기존 결과를 바로 반환한다. ON_SALE 새 version이면 changeReason까지
검증한 후 변경된 temp 객체만 영구 key로 승격하고 최종 `ProductContent`를 만들어 저장한다. 이 순서로
no-op·검증 실패에서 불필요한 영구 객체가 생기는 것을 막는다.

ON_SALE 수정은 application이 새 productId를 먼저 생성한다. temp 객체를
`products/{nextProductId}/...`로 승격한 뒤 도메인의
`createNextVersion(UUID nextProductId, ProductVersionType, ProductContent, String changeReason)`에 전달한다.
현재처럼 `nextVersion()` 내부에서 UUID를 생성하고 이전 path productId 아래로 파일을 승격하지 않는다.
이로써 새 version의 DB row ID, S3 영구 경로와 `ProductUpdateResponse.productId`가 일치한다. DRAFT와
REJECTED는 같은 row를 보정하므로 기존 productId를 계속 사용한다.

FE의 기존 `submitting` ref도 유지해 더블클릭과 같은 tick의 중복 요청을 막는다. 그러나 두 HTTP 요청이
동시에 BE에 도착하면 둘 다 같은 현재 버전을 읽고 동일한 다음 버전을 만들 수 있으므로 FE 방어만
신뢰하지 않는다. 현재 Product에는 JPA `@Version`, family version unique 제약과 family root lock이 모두
없다.

최소 BE 방어로 PostgreSQL에 다음 의미의 unique expression index를 추가한다.

```sql
unique (coalesce(parent_id, id), major_version, patch_version)
```

같은 family에 같은 `1.1` 또는 `2.0` row가 두 개 생기는 것을 DB가 최종 차단한다. 충돌은 상품 버전
동시 수정 충돌 error code의 HTTP 409로 변환하고 FE는 “다른 수정이 먼저 반영됐습니다. 최신 내용을
다시 불러와 주세요”를 표시한다. migration 전에 기존 중복 version 데이터를 조회해 충돌 여부를
검증한다. Redis lock, 별도 Idempotency-Key 저장소와 전역 분산 lock은 이 요구에 비해 과하므로
도입하지 않는다.

S3 승격은 새 version ID 아래로 복사한 현재 요청의 object 목록을 추적한다. DB transaction이 commit되면
temp 원본을 삭제하고, unique 충돌을 포함해 rollback되면 이 요청이 만든 영구 object만 보상 삭제하며
temp 원본은 Lifecycle 또는 재시도를 위해 남긴다. 이는 별도 Saga·보상 테이블이 아니라 현재 transaction의
afterCommit/afterCompletion 결과에 맞춘 `TempFilePromoter`의 한정된 정리 책임이다. 다른 요청이나 기존
version의 prefix는 절대 삭제하지 않는다.

통합 테스트는 동일 family·동일 다음 version insert 두 건 중 한 건만 성공하는지, 다른 family의 같은
version은 허용되는지, 기존 row revise는 index에 막히지 않는지를 검증한다. 가능하면 동시 PATCH 테스트도
추가하되 DB unique 제약 테스트를 필수 인수 조건으로 둔다. S3 테스트는 commit 성공 시 영구 object 유지와
temp 삭제, unique 충돌/rollback 시 현재 요청의 영구 object 삭제와 temp 유지를 검증한다.

| 변경 필드 | 추천 판정 | 근거 |
| --- | --- | --- |
| productType | 변경 거절 | FE도 등록 후 읽기 전용이다. PROMPT/PPT/EXCEL/NOTION 전환은 버전 변경이 아니라 산출물 스키마 변경이므로 새 상품으로 등록 |
| PROMPT content | MAJOR | PROMPT의 핵심 구매 산출물이며 AI가 본문을 추가 검수 |
| NOTION externalUrl | MAJOR | 구매자가 받는 핵심 산출물 링크가 바뀜 |
| PPT/EXCEL fileKey | MAJOR | 구매자가 받는 핵심 산출물 파일이 바뀜 |
| model, title, description, tags, thumbnailKey, imageKeys | PATCH | 핵심 산출물은 유지되는 메타데이터·소개 정보 변경 |
| FREE ↔ PAID 전환 | MAJOR | AI 검수 prompt의 저작권 예외 기준이 바뀜 |
| 금액만 변경하되 FREE/PAID 구분 동일 | PATCH | 검수 대상 콘텐츠는 동일하고 가격 이벤트로 주문 측에 전달 가능 |
| 실질 필드 변화 없음 | 판정 안 함·no-op | FE 저장 차단 + BE 최소 방어, 별도 enum/DB 필드 없음 |

자동 판정의 기준은 “검수 payload에 포함되는 모든 필드”가 아니라 **상품 유형별 핵심 구매 산출물이
바뀌었는가**다. PROMPT는 content, NOTION은 externalUrl, PPT/EXCEL은 fileKey 변경만 자동 MAJOR다.
제목·설명·태그·이미지는 AI가 함께 보더라도 핵심 산출물은 유지되므로 PATCH로 즉시 반영한다.
예외적으로 FREE/PAID 전환은 AI가 받는 `free`와 저작권 판정 전제를 바꾸므로 MAJOR로 확정한다. 같은
PAID 안에서 금액만 바꾸는 것은 PATCH다.

동일성 비교는 저장될 의미를 기준으로 한다. S3 필드는 매번 달라지는 presigned query string이 아니라
추출한 object key로 비교한다. null image/tag 목록은 빈 목록으로 맞추고, 소개 이미지와 태그는 현재 UI
표시 순서를 포함한 exact list로 비교한다. PROMPT content는 공백·줄바꿈도 실제 산출물이므로 원문 그대로
비교한다. 제목·설명·model·externalUrl도 저장될 최종 문자열, 가격은 정수와 FREE/PAID 유형을 각각
비교한다. FE dirty check는 저장 버튼 UX만 담당하고 최종 판정은 BE가 수행한다.

현재 `PRODUCT_REVIEW_REQUESTED` payload에는 productType, name, description, PROMPT content, tags,
thumbnail/image, exact duplicate id, free 여부만 들어간다. 의도적으로 AI는 PROMPT에서만 content를
추가 판단하며 PPT/EXCEL `fileKey`와 NOTION `externalUrl`의 실제 내용은 읽지 않는다. 그럼에도 fileKey와
externalUrl 변경은 상품 유형별 핵심 산출물 교체이므로 MAJOR로 분류한다. 향후 파일·외부 링크 내용까지
검수하려면 AI 이벤트 계약과 검수 방식 자체를 별도 이슈로 확장해야 하며 이번 자동 판정 작업에 섞지
않는다.

`changeReason`은 판정 입력이 아니라 **새 version을 만들 때** 그 version에 저장할 설명이다. ON_SALE
상품을 수정해 새 MAJOR/PATCH row를 만들 때만 필수다. DRAFT와 REJECTED 수정은 같은 row·같은 version을
보정하므로 받지 않는다. 반려본에 기존 changeReason이 있으면 유지하고 최초 1.0 반려본처럼 없으면 null을
허용한다. 사용자는 versionType을 선택하지 않고 새 version 생성 시 변경 사유만 입력하며, 응답 또는 수정
완료 UI에서 서버가 결정한 버전을 보여주는 것이 명확하다.

수정 API는 기존 `ApiResult<Void>` 대신 다음 최소 결과를 반환하는 것으로 확정한다.

```java
public record ProductUpdateResponse(
	UUID productId,
	String version,
	String status
) {
}
```

`productId`는 요청 path의 anchor가 아니라 실제로 수정되거나 새로 생성된 결과 row ID다. FE는 서버가
판정한 `version`과 `status`로 성공 문구를 결정하며 자동 판정 로직을 중복 구현하지 않는다.
`PENDING_REVIEW`는 검수 시작, `ON_SALE`은 즉시 반영, `DRAFT`는 임시저장, `REJECTED`는 같은 반려
version 보정으로 표시한다. 별도 changeType/NO_CHANGE 응답 enum은 만들지 않는다.

#### 구현 영향과 예상 공수

난이도는 대규모 재설계가 아니라 **중간 규모**다. 기존 ProductFamily/버전 생성 구조는 유지한다.
다만 같은 family·version의 동시 생성을 최종 차단하는 unique expression index는 PR 3의 필수 DB
migration이다. 별도 revision/검수 이력 테이블이나 working-version 테이블은 만들지 않는다.

- BE API: `versionType` 제거, 새 version 생성 경로에서만 changeReason 검증,
  `ProductUpdateResponse(productId, version, status)` 반환
- BE domain/application: `Product.determineVersionType(ProductContent)`과 MAJOR/PATCH 판정 enum 추가,
  판매 전/후 분기 정리, application이 nextProductId를 생성하고
  `Product.createNextVersion(nextProductId, versionType, content, changeReason)`에 전달
- S3: 현재 파일을 먼저 영구 승격한 뒤 비교하는 순서를 조정해야 한다. 기존 key와 temp key를
  정규화해 변경 여부를 판단하고 동일 요청/validation 실패에서 불필요한 영구 객체가 생기지 않게 함
- 이벤트: 자동 major에서만 검수 요청, 가격 변경 이벤트 유지, scheduler-only 전환 후
  `PRODUCT_CHANGED` 제거
- FE 계약: major/minor 선택 UI와 `versionType` 전송 제거, ON_SALE 수정에서만 changeReason 입력 유지,
  DRAFT/REJECTED에서는 숨김, 서버 판정 결과 표시
- 문서/테스트: API spec, controller/service/domain policy 테스트, DRAFT/REJECTED/ON_SALE과 필드별
  parameterized test, S3 파일 변경 케이스, BE-FE 계약 테스트

S3 업로드 계약 리팩터링과 동시에 하면 변경 원인을 분리하기 어렵다. 권장 순서는 **파일 업로드
계약·승격 순서 정리 -> 반려본 재편집 -> 자동 버전 판정**이다. 판매 후 반려 major row 재편집은
`currentForSeller` 우선순위, REJECTED revise 분기와 FE 버전 선택 조건을 고치는 최소 경로로 구현하면
BE 약 1일, FE 반나절, 테스트 반나절~1일의 2~3 작업일 규모다. 자동 판정까지 합치면 BE·FE·회귀 검증
전체 약 3~5 작업일로 본다. DB migration이나 새 working-version DTO는 필수가 아니다. 실제 별도
revision/검수 이력 테이블을 도입할 때만 이 범위를 넘어 다시 산정한다.

#### 현재 테스트가 보장하고 새로 필요한 것

현재 테스트는 사용자가 넘긴 MAJOR/MINOR 각각에 대해 판매 전 in-place, 판매 후 새 row,
PENDING_REVIEW 중복 major 거부, 이전 판매본 SUPERSEDED를 보장한다. 자동 판정은 검증하지 않는다.
추가할 테스트는 다음과 같다.

- 각 필드 단독 변경의 MAJOR/PATCH 판정과 실질 변경 없음 no-op
- 서로 다른 presigned URL의 동일 object key, null/빈 목록, 이미지·태그 순서, PROMPT 공백·줄바꿈 비교
- DRAFT/REJECTED 수정이 상태를 바꾸거나 검수 이벤트를 내지 않음
- 판매 후 major가 기존 ON_SALE을 유지하고 pending child를 생성
- 판매 후 patch가 즉시 교대하고 가격 이벤트를 정확히 발행
- 잘못된 client versionType에 의존하지 않는 새 API 계약

#### PR 3 구현 인계 — 상품 수정 MAJOR/PATCH 자동 판정과 동시 version 방어

PR 1의 object key 계약과 PR 2의 REJECTED 동일 row 보정이 먼저 반영된 상태에서 착수한다. PR 3은
ON_SALE 상품의 다음 version 판정과 생성에 집중하며 DRAFT·REJECTED의 기존 row 수정 규칙을 되돌리지
않는다.

##### 현재 코드와 변경 목표

| 현재 | 문제 | 변경 |
| --- | --- | --- |
| FE가 `versionType` 문자열 전송 | 사용자가 도메인 판정을 선택하고 BE가 그대로 신뢰 | 요청에서 제거하고 Product가 필드 차이로 판정 |
| DRAFT의 null `versionType`을 minor로 처리 | 임시저장마다 1.1, 1.2처럼 version 증가 | `updateDraftContent()`로 1.0 유지 |
| `Product.update(..., boolean isMajor)` | 내용 보정·version 증가·상태 전이가 boolean 하나에 혼재 | 상태별 명시 메서드로 교체 |
| `nextVersion()` 내부 UUID 생성 | 새 DB ID를 알기 전에 S3가 기존 product ID 경로로 승격 | application이 ID를 먼저 만들고 도메인에 전달 |
| 동일 family 다음 version 동시 생성 방어 없음 | 두 요청이 같은 2.1 또는 3.0 row를 만들 수 있음 | DB unique expression index + HTTP 409 |
| PATCH 응답이 `Void` | FE가 서버 판정 결과를 알 수 없음 | 실제 row ID·version·status 반환 |

##### 상태별 수정 알고리즘

```text
소유 상품과 family 조회
  -> 요청 productType이 기존과 다르면 P004/400
  -> 요청 object key를 아직 copy하지 않은 후보 ProductContent 생성·검증

anchor=DRAFT
  -> updateDraftContent(candidate)
  -> 같은 ID, version 1.0, status DRAFT

anchor=REJECTED
  -> PR 2의 updateRejectedContent(candidate)
  -> 같은 ID/version/status, 이벤트 없음

판매 이력 있음
  -> family.currentOnSale()을 비교 기준으로 선택
  -> currentOnSale.determineVersionType(candidate)
      -> empty: S3 copy·DB insert·Kafka 발행 없이 현재 row 결과 반환
      -> PATCH/MAJOR: changeReason 검증
          -> nextProductId 선생성
          -> 변경된 temp object만 products/{nextProductId}/...로 승격
          -> 최종 ProductContent 생성
          -> createNextVersion(nextProductId, type, finalContent, changeReason)
          -> PATCH: 기존 ON_SALE을 SUPERSEDED, 새 row 즉시 ON_SALE
          -> MAJOR: 기존 ON_SALE 유지, 새 row PENDING_REVIEW + 검수 요청
```

PENDING_REVIEW·STOPPED·SUPERSEDED anchor에 대한 직접 수정은 기존 상태 충돌 정책대로 거절한다. seller
대표 route가 working row를 가리키더라도 family 전체 기준으로 한 개의 pending major만 허용한다.

##### 자동 판정 규칙

`ProductVersionType`은 `MAJOR`, `PATCH`만 갖는다. `NO_CHANGE`는 만들지 않고
`Optional<ProductVersionType>`의 empty로 표현한다.

| 변화 | 판정 |
| --- | --- |
| productType 변경 | 요청 거절 — 새 상품 등록 대상 |
| PROMPT `content` 변경 | MAJOR |
| NOTION `externalUrl` 변경 | MAJOR |
| PPT/EXCEL `fileObjectKey` 변경 | MAJOR |
| FREE ↔ PAID 변경 | MAJOR |
| title·description·model·tags·thumbnail·소개 이미지 변경 | PATCH |
| FREE 내 0원 유지 또는 PAID 내 금액 변경 | PATCH |
| 저장 의미가 모두 동일 | 판정 없음·no-op |

여러 필드가 함께 바뀌면 하나라도 MAJOR 조건이면 전체를 MAJOR로 판정한다. 비교는 presigned URL이 아닌
PR 1의 object key, null을 빈 목록으로 정규화한 tags/images, 순서를 포함한 exact list, 원문 PROMPT
content를 기준으로 한다. FE dirty check는 UX 최적화일 뿐 최종 판정은 항상 BE가 수행한다.

##### 요청·응답 계약

`ProductUpdateRequest`에서 `versionType`을 제거한다. `changeReason`은 유지하지만 ON_SALE에서 실제 변경이
있어 새 row를 만들 때만 service가 필수 검증한다. DRAFT·REJECTED·no-op에서는 없어도 된다.

```json
{
  "title": "...",
  "productType": "PROMPT",
  "amount": 1000,
  "content": "...",
  "thumbnailObjectKey": "products/...",
  "imageObjectKeys": [],
  "fileObjectKey": null,
  "externalUrl": null,
  "tags": [],
  "changeReason": "핵심 지시문 개선"
}
```

endpoint는 그대로 유지하고 응답 data만 다음으로 바꾼다.

```java
public record ProductUpdateResponse(UUID productId, String version, String status) {
}
```

no-op 중복 요청도 기존 row의 동일 응답을 반환한다. 사용자가 별도 `NO_CHANGE` 필드나 enum을 원하지
않으므로 FE는 dirty check로 일반 무변경 저장을 막고, BE는 새 row와 부수효과가 생기지 않는 최종 방어만
담당한다.

##### 동시 version 생성 방어

다음 available Flyway migration(현재 기준 `V10`)에 constraint 이름
`uk_product_family_version`으로 expression unique index를 추가한다.

```sql
CREATE UNIQUE INDEX uk_product_family_version
    ON product (coalesce(parent_id, id), major_version, patch_version);
```

migration 적용 전에 동일 family/version 중복을 조회하는 검증을 넣고, 데이터가 존재하면 임의 삭제·교정
하지 않고 migration을 실패시켜 확인하게 한다. 이 충돌만 `PRODUCT_VERSION_CONFLICT`(`P009`, HTTP 409,
"다른 수정이 먼저 반영되었습니다. 최신 상품을 다시 불러와 주세요.")로 변환한다. 다른 DB 제약 오류를
전부 P009로 오인하지 않도록 constraint 이름을 확인한다.

FE의 `submitting` ref는 같은 화면의 빠른 두 번 클릭을 계속 막는다. DB unique index는 서로 다른 탭·파드·
동시 HTTP 요청에서 같은 다음 version이 생성되는 것을 최종 차단한다. Redis lock과 별도 idempotency
저장소는 도입하지 않는다.

unique 충돌은 transaction rollback이므로 PR 1의 `TempFilePromoter`가 해당 요청에서 복사한 영구
object만 보상 삭제하고 temp 원본을 유지해야 한다. 성공한 다른 요청과 이전 version의 object는 건드리지
않는다.

##### FE 변경

- PATCH/MAJOR 라디오와 `nextVer()` 클라이언트 계산을 완전히 제거한다.
- DRAFT·REJECTED에서는 changeReason을 숨긴다.
- ON_SALE 수정에서만 변경 사유를 받되 “버전 유형”을 사용자가 선택하지 않는다.
- 저장 성공 후 응답의 `version`·`status`로 문구를 결정한다.
  - `PENDING_REVIEW`: `v3.0으로 저장됐어요 · 검수 후 판매에 반영됩니다.`
  - `ON_SALE`: `v2.1 수정사항이 바로 반영됐어요.`
  - `DRAFT`: `v1.0 임시저장이 수정됐어요.`
  - `REJECTED`: PR 2의 동일 version 보정 안내.
- productType은 등록 후 읽기 전용 상태를 유지하고 FE와 BE가 모두 변경을 거절한다.

##### 변경 파일 경계

```text
product-service/src/main/java/com/prompthub/product/
  presentation/dto/request/ProductUpdateRequest.java
  presentation/dto/response/ProductUpdateResponse.java              (신규)
  presentation/controller/ProductController.java
  application/usecase/ProductSellerUseCase.java
  application/service/ProductSellerService.java
  domain/model/entity/Product.java
  domain/model/enums/ProductVersionType.java                         (신규)
  exception/enums/ProductErrorCode.java
  exception/ProductExceptionHandler.java

product-service/src/main/resources/db/migration/
  V10__product_family_version_unique.sql                             (현재 기준 신규)

product-service/src/test/java/com/prompthub/product/
  domain/model/entity/ProductTest.java
  application/service/ProductSellerServiceTest.java
  presentation/controller/ProductControllerTest.java
  infra/persistence/ProductFamilyVersionUniqueMigrationTest.java    (신규)

FE
  app/edit/[id]/page.tsx
```

PR 1 구현 과정에서 DTO의 object key 이름이나 다음 migration 번호가 달라지면 실제 선행 결과를 따르되,
의미와 constraint 이름은 유지한다. Kafka event 종류 제거와 ES scheduler-only 전환은 PR 5 범위라 이
PR에서 producer 전체를 정리하지 않는다.

##### 필수 테스트와 회귀 확인

- `Product.determineVersionType()` parameterized test: 4개 productType 핵심 산출물, FREE/PAID 전환,
  가격, 각 metadata, 복합 변경의 MAJOR 우선, null/empty list, 순서, 공백·줄바꿈, no-op.
- DRAFT를 여러 번 수정해도 같은 ID·1.0·DRAFT이고 검수/검색 이벤트를 발행하지 않음.
- PR 2의 REJECTED 동일 row/version·이벤트 없음 회귀.
- ON_SALE PATCH는 새 patch row, 기존 row SUPERSEDED, MAJOR는 새 major PENDING_REVIEW와 기존 판매본 유지.
- no-op은 repository save/insert, S3 copy/delete, Kafka publisher를 호출하지 않음.
- application이 만든 nextProductId가 DB row ID, S3 prefix, 응답 productId에 동일하게 사용됨.
- 같은 family/version 동시 insert 중 한 건만 성공, 다른 family의 같은 version은 성공, REJECTED/DRAFT
  동일 row update는 unique index에 막히지 않음.
- unique 충돌은 P009/409이며 rollback 보상 범위가 현재 요청의 새 영구 object로 제한됨.
- FE에서 PROMPT·NOTION·PPT·EXCEL의 각 핵심 산출물 변경 결과가 서버 판정과 일치하고, 클라이언트는
  `versionType`을 보내지 않음.
- 전체 BE 테스트와 FE lint/build·등록/수정/검수/공개 상세 회귀에서 baseline 대비 신규 실패 없음.

##### 구현 후 검증 결과

상태: **IMPLEMENTED · PR_PENDING** · 확인일: 2026-08-11 · 기준: `develop`(PR 2 머지 직후) · 이슈:
[#720](https://github.com/prgrms-be-adv-devcourse/beadv6_6_3JMT_BE/issues/720) · 브랜치:
`feat/#720-auto-version-type-determination`

**구현 흐름**

"상태별 수정 알고리즘"·"자동 판정 규칙" 그대로 구현했다: anchor 조회 → productType 검증 → 승격 전
candidate `ProductContent` 생성 → DRAFT/REJECTED는 같은 row 보정 후 조기 반환 → ON_SALE만
`currentOnSale.determineVersionType(candidate)`로 판정 → empty면 파일 승격·저장·이벤트 없이 현재
row 응답 → MAJOR/PATCH면 changeReason 검증 → `nextProductId`를 application이 먼저 생성 →
temp object만 그 ID 경로로 승격 → `createNextVersion()` → PATCH는 기존 ON_SALE을 SUPERSEDED하고 즉시
발행, MAJOR는 기존 판매본을 유지한 채 검수 요청 이벤트 발행.

**BE/FE 테스트**

- `./gradlew.bat :product-service:build`(checkstyle + 전체 테스트, `ProductSellerServiceVersionConflictIntegrationTest`·
  `ProductFamilyVersionUniqueMigrationTest`·`ProductExceptionHandlerTest` 신규 케이스 포함): **BUILD
  SUCCESSFUL**.
- FE `npm run lint`(`app/edit/[id]/page.tsx`): 통과(기존 경고 3건 그대로, 신규 에러 0).
- FE `npm run build`: 통과.
- Codex 리뷰 3라운드: 1차 `/codex:adversarial-review`(구현 직후) — needs-attention, stale-anchor
  lost-update(두 요청이 같은 `currentOnSale`을 동시에 읽는 경우 unique index만으로 막을 수 있는지)
  지적. 2차 결함 리뷰 — 6건(P1×3, P2×3): FE dirty-check·changeReason 검증 소실 2건, 동시성 충돌 시
  실제 DB 예외 변환·S3 rollback 미검증 1건, FE의 P009 메시지 BE와 중복 하드코딩 1건,
  `determineVersionType()` 테스트 매트릭스 공백(엑셀·FREE↔PAID·단독 메타데이터·이미지·태그 순서·
  NOTION/PPT/EXCEL no-op) 1건, API 문서·Swagger 미반영 1건 — 전부 수정·검증 완료(아래 상세). 3차
  최종 리뷰 — **No findings**.

**Codex 2차 리뷰 대응**

| 지적 | 대응 |
| --- | --- |
| FE changeReason 필수 검증 소실 | `isDirty`(전 필드 비교) 계산 + `noteErr` 게이트 복원 |
| FE no-op 저장도 버튼 활성 | `isDirty` 기반 저장 버튼 비활성화 + "변경 없음" 라벨 |
| 동시성 충돌의 실제 DB 예외 변환·rollback 미검증 | `ProductExceptionHandlerTest`에 실제 Hibernate `ConstraintViolationException` 체인 테스트 추가, `ProductSellerServiceVersionConflictIntegrationTest` 신규(실제 Postgres + 실제 트랜잭션 프록시로 경쟁 충돌 재현, 영구 object만 보상 삭제·temp 원본 유지 확인) |
| FE P009 메시지 BE와 중복 | FE는 상태만 보고 재조회 필요 여부를 판단하지 않고, BE `message`를 그대로 표시하도록 단순화(메시지 소유권을 BE로 이관) |
| `determineVersionType()` 테스트 매트릭스 공백 | `ProductTest`에 11개 케이스 추가 |
| API 문서·Swagger 미반영 | `ProductUpdateResponse`에 `@Schema` 추가, `docs/api-spec/product.md` PATCH 섹션을 실제 계약(요청에서 `versionType` 제거, 자동 판정 규칙, 응답 바디, 에러 코드 P006·P009·V001)으로 재작성 |

**ponytail 자가 감사**

`/ponytail full` 셀프 감사로 과설계 1건을 찾아 고쳤다: `determineVersionType()` 안에서 호출 지점이
하나뿐이던 private 헬퍼 `coreOutputChanged()`/`metadataChanged()`를 로컬 변수로 인라인했다(동작 변경
없음, 메서드 2개 제거). 그 외 새 추상화(인터페이스·팩토리·불필요한 설정값)는 추가하지 않았다.

**verify-rules 게이트**

루트 공용 룰(clean-architecture, code-style, controller-exception, domain-model, git-convention,
kafka-event, security, swagger) + 서비스 룰(git-workflow, product-api, testing) 11개를
`rule-checker`로 병렬 검증했다.

- PASS: clean-architecture, code-style, git-workflow, product-api, security, testing.
- N/A: kafka-event(이번 diff에 이벤트 발행 구조 변경 없음).
- FAIL → 수정: git-convention(기존 PR2 문서 커밋 `bf08377d`의 본문이 불릿 목록이 아닌 서술형 문단 —
  `git commit --amend`로 불릿 형식 재작성), swagger(`ProductUpdateRequest`에 `@Schema`가 전혀 없음 —
  전 필드에 추가).
- FAIL → 기존 패턴으로 판단, 이번 PR에서 고치지 않음(둘 다 PR3가 새로 만든 위반이 아니라 이미 있던
  패턴을 그대로 따른 것):
  - domain-model §8: `Product.updateDraftContent()`가 도메인 순수 예외 대신 `IllegalStateException`을
    직접 던진다. 같은 파일의 `updateRejectedContent`·`supersede`·`submitForReview`·`approve`·`reject`
    5개가 이미 동일 패턴이라(도메인 전용 exception 패키지 자체가 없음), 이 메서드 하나만 새
    exception 클래스로 바꾸면 같은 파일 안에서 패턴이 갈린다. 6개를 한 번에 정리하는 건 PR3 범위 밖의
    별도 리팩터링으로 판단해 손대지 않았다.
  - controller-exception §1/§7: `updateProduct()`가 다중 필드 명령인데도(§7의 "입력이 식별자뿐" 예외
    조건에 문자 그대로는 안 맞음) application 서비스가 `~Result` 없이 presentation `~Response`를 직접
    반환한다. 같은 서비스의 `createProduct()`가 이미 이 PR 이전부터 동일 패턴이라 PR3가 새로 만든
    선례는 아니다. `ProductUpdateResponse` 직접 반환은 "PR 3 구현 인계"에서 사용자가 이미 확정한
    설계이기도 하다. `~Result` 계층을 새로 넣는 건 두 메서드(`createProduct`·`updateProduct`)를 함께
    건드리는 별도 리팩터링이라 이번 PR 범위로 보지 않았다.

**메서드 순서 정리(로직 변경 없음)**

`ProductSellerService`·`ProductSellerUseCase`의 public 메서드를 실제 호출 흐름 순서로 재배열했다:
`createProduct → submitForReview → updateProduct → deleteProduct → getMyProducts → getMyProduct →
getProductCount`. 공용 private 헬퍼(`createDownloadUrl` 등)는 하단에 그대로 둔다.

**설계와 달라진 부분**

- 동시성 방어는 설계대로 DB unique index(P009)만 쓰고 애플리케이션 레벨 락(`SELECT FOR UPDATE`,
  Redis lock)은 추가하지 않았다. 1차 `/codex:adversarial-review`가 지적한 stale-anchor 창(두
  트랜잭션이 동시에 같은 `currentOnSale`을 읽고 각자 다음 patch/major를 계산하는 경우)은 이론적으로는
  여전히 존재하지만, unique index가 그중 하나만 커밋을 통과시키고 나머지를 P009로 확정 거절하므로
  데이터 정합성은 깨지지 않는다 — 지는 쪽이 실패 후 재시도해야 하는 사용자 경험상의 열세이지 정합성
  결함은 아니라고 판단해 이번 PR 범위에서는 애플리케이션 레벨 락을 추가하지 않았다. 이 판단은 3차
  최종 리뷰가 No findings로 통과하며 재확인됐다. `ProductSellerServiceVersionConflictIntegrationTest`가
  이 경합을 실제 Postgres로 재현해 P009 응답과 rollback 보상 범위를 고정한다.
- FE changeReason 필수 판정은 로드맵 원안("실제 변경이 있을 때만 필수")을 `isDirty` 계산으로
  구체화했다 — 설계 의도와 동일하고 표현만 명시적으로 코드화했다.
- 그 외(요청·응답 계약, 판정 규칙, 파일 승격 순서, migration, FE 버전 선택 UI 제거)는 설계 그대로
  구현했다.

##### 이슈 본문 초안

**제목**: `feat(product): 상품 변경 내용으로 MAJOR/PATCH 버전을 자동 판정`

**배경**: 현재 판매자가 PATCH/MAJOR를 직접 선택하고 BE가 문자열을 신뢰한다. DRAFT 수정도 version이
증가하며, 동시에 수정하면 같은 family/version row가 중복될 수 있다. 새 row ID가 S3 승격 전에 정해지지
않아 DB ID와 파일 경로의 기준도 어긋난다.

**목표**: 상품 유형별 핵심 산출물과 FREE/PAID 전환으로 MAJOR를 자동 판정하고 나머지 변경은 PATCH로
처리한다. DRAFT·REJECTED는 같은 version을 보정하고, ON_SALE만 새 version을 만든다. DB unique index로
동시 생성 중복을 최종 방어한다.

**범위**: update 요청·응답, Product 판정·버전 생성 메서드, seller update 흐름, family-version unique
migration과 409 오류, FE 버전 선택 제거·서버 결과 표시, 관련 테스트. Kafka 발행 신뢰성, ES 이벤트
제거, 업로드 계약 자체 변경은 제외한다.

**완료 조건**: 필드별 자동 판정, no-op 무부수효과, DRAFT/REJECTED version 유지, ON_SALE 새 version,
동시 중복 차단과 S3 rollback 보상, BE·FE baseline 대비 신규 실패 없음.

### 상품 리뷰 흐름 판단

리뷰 row는 Product root row 안에 컬렉션으로 저장되는 것이 아니라 별도 `review` 테이블에 쌓인다. 모든
version 요청 ID는 `anchor.familyRootId()`로 정규화되고 각 review의 `product_id` FK가 최초 family root
상품 ID를 가리킨다. 따라서 1.0 root가 SUPERSEDED가 되고 2.0·3.0 child가 판매돼도 리뷰와 평균 평점은
하나의 상품 family에 이어진다. `(product_id, user_id)` unique 제약으로 사용자당 family 리뷰는 하나이며
재요청은 같은 review row의 rating을 수정한다.

현재 FE Reader Page는 주문 내역에서 상품을 찾은 사용자에게만 별점 UI를 노출하지만 BE
`ProductReviewService`와 `PurchasedProductQueryService.verifyPurchase()`는 실제 구매 여부를 검증하지
않는다. 이는 API 문서의 #550/#440 알려진 한계다. 사용자 결정에 따라 이번 품질 리팩터링에는
order-service 구매 검증을 추가하지 않고 현재 설계를 유지한다. 따라서 이를 해결 완료 또는 보안 강화
성과로 Publish하지 않으며, FE 우회 가능성은 알려진 제한으로만 남긴다.

2026-08-10 관련 테스트를 직접 실행했다. `ProductReviewServiceTest` 3건, `ReviewControllerTest` 4건,
`PurchasedProductQueryServiceTest` 7건은 XML 결과 기준 failure 0으로 통과했다. family root 귀속, 기존
별점 update, 사용자별 별도 리뷰, 1~5 요청 검증과 구매 상세의 myRating 조회를 보장한다.
첫 실행에서 `ProductJpaRepositoryTest` 14건은 Testcontainers가 Docker를 찾지 못해 class
initialization에서 중단됐으나, Docker 기동 후 같은 테스트를 재실행해 **14건, failure 0, error 0,
skipped 0, BUILD SUCCESSFUL**을 확인했다. 실제 PostgreSQL에서 review `(product_id, user_id)` unique,
rating check와 평균 평점 query를 포함한 repository 계약이 통과한다. 리뷰 변경이 scheduler를 거쳐 ES
평균 평점에 수렴하는 전용 통합 테스트는 여전히 확인되지 않아 I-2 인수 테스트로 추가한다.

### 2026-08-14 기준 공수와 완료 가능성

산정 기준은 2026-08-10 현재까지 분석한 BE·FE 변경, 단위·통합 테스트, API 문서, Quality 재측정과
PR 검토를 포함한다. AI가 코드를 빠르게 생성하는 시간보다 계약 확인·회귀 실패 수정·리뷰가 병목이므로
밤샘을 생산성 배수로 계산하지 않는다. 아래는 겹치는 I-4/I-8 등은 중복 계산하지 않은 범위다.

| 작업 묶음 | 포함 범위 | 예상 |
| --- | --- | ---: |
| Kafka 신뢰성 | I-1, product/AI producer future 실패 관측, 포트·테스트, PENDING_REVIEW 탐지 최소안 판정 | 1~2일 |
| ES scheduler 정합성 | I-2 핵심, 삭제·리뷰 watermark, bulk item 실패, PRODUCT_CHANGED 제거, scheduler 테스트 | 2~3일 |
| 파일 업로드 계약·구조 | I-4+I-8 겹침, BE·FE 필드/key, seller scope, 정책/승격 분리, S3 실패 코드, 테스트 | 2~3일 |
| 반려 major 재편집 | ProductFamily 우선순위, rejected revise, FE 상태/버튼, 재요청·승인 교대 테스트 | 2~3일 |
| 자동 버전 판정 | versionType 제거, MAJOR/PATCH 정책과 무변경 no-op, 가격 이벤트 시점, BE·FE·필드별 테스트 | 1.5~2.5일 |
| 소규모 정리 | I-3, I-5, I-9 중 독립 항목 | 1~2일 |
| 조회 구조·성능 정리 | I-7 검색/공개 조회 2분할, ES 예외, 일괄 집계와 회귀 | 1.5~2.5일 |
| 최종 통합 | 전체 product test/build, FE test/build, 계약 문서, Quality, diff/PR 회귀 수정 | 1.5~2.5일 |

위 합계는 사람이 직접 구현할 때의 복잡도 참고치이고 실제 달력 일정으로 사용하지 않는다. 사용자는
2026-08-10에 전체 설계를 끝낸 즉시 Claude 구현을 시작하고, 한 PR씩 구현·테스트·Quality를 닫은 뒤
다음 PR로 진행한다. 이 방식의 실제 목표는 **설계와 첫 구현 시작 8/10 + 전체 구현·PR 2~3일 + 8/14
전체 테스트·실패 수정·마지막 PR 완료**다.

#### 8월 14일까지 전체 완료 실행 순서

I-6은 scheduler-only 전환에 따른 검색 consumer 제거로 자연 해소되어 별도 구현에서 제외한다. 그 밖의
I-1~I-5·I-7~I-9, 반려 major 재편집, 자동 버전 판정과 이번 분석에서 확정한 S3·Kafka·ES 보완을
대상으로 한다.

```text
8/10  남은 전체 흐름 설계, 최종 이슈·인수조건 확정, 즉시 첫 이슈 구현 시작
8/11  I-1·I-2 운영 결함 PR, I-3·I-5·I-9 소규모 PR
8/12  I-4 업로드 계약 BE·FE PR, I-8 SellerService 구조 PR
8/13  반려 major 재편집 PR, 자동 버전 판정 PR, 검수 consumer의 usecase 의존 정리
8/14  I-7 QueryService 가독성·집계 최적화 PR, BE·FE 전체 테스트/build, Quality, 실패 수정, 마지막 PR 완료
```

각 PR은 `코드 구현 -> 관련 단위·통합 테스트 -> product-service 전체 테스트 -> 영향이 있으면 FE
테스트/build -> Claude Quality -> diff 검토 -> PR` 순서로 닫는다. 다음 PR은 앞 PR이 반영된 최신 base에서
시작한다. 8/14의 “전체 테스트·실패 수정”은 쉬는 날이나 제외 범위가 아니라 남은 구현과 모든 PR의
최종 검증 작업이다.

일정은 공격적이므로 구현 중 새 확장 설계를 추가하지 않고 오늘 확정한 최소안만 적용한다. 특정 PR에서
테스트가 실패하면 그 실패를 해결한 뒤 다음 PR로 넘어가며, 테스트를 생략해 날짜만 맞추지 않는다.

#### 과도한 설계 방지 기준

각 이슈 확정 전 다음 순서로 최소 변경 감사를 수행한다.

1. 기존 도메인 메서드·응답·FE 버튼으로 이미 가능한 부분을 찾는다.
2. 우선순위·조건 분기·이름 변경만으로 해결 가능한지 확인한다.
3. 새 DTO·테이블·endpoint는 기존 계약 재사용으로 해결되지 않을 때만 추가한다.
4. 최소안과 확장안을 분리하고, 현재 요구사항에는 최소안 공수만 반영한다.
5. BE 허용 경로와 실제 FE UX를 함께 재현한 뒤 인수 조건을 쓴다.
6. 실제 장애가 아닌 미래 가능성 때문에 replay·Saga·Outbox·분산 lock을 선제 도입하지 않는다.

### 후속 적용 범위와 아직 확정하지 않은 사항

- temp S3 Lifecycle은 현재 공유 AWS 버킷에 적용하지 않는다. 사용자가 저장소를 fork해 본인 AWS
  계정·버킷으로 배포한 뒤 `products/temp/` prefix의 생성 1일 후 만료 정책을 버킷에 직접 적용한다.
  Git fork는 S3 설정을 복사하지 않으므로 개인 배포 인프라 작업으로 분리한다.
- 영구 key에 sellerId를 어떤 순서로 포함할지와 기존 key 마이그레이션/호환 범위.
- unique 충돌을 포함한 현재 요청 rollback은 `TempFilePromoter`가 자신이 복사한 영구 object만 삭제하고
  temp는 유지하는 것으로 확정했다. 그 밖의 과거·운영 고아 객체 정리 배치는 로그로 빈도를 관측한 뒤
  결정하며 Saga는 도입하지 않는다.
- 파일 내용 magic number·악성 파일·크기 제한 검증 수준. 현재 확장자와 Content-Type은 실제 바이트를
  증명하지 않으며 Spring multipart 10MB 제한은 FE의 S3 직접 PUT에 적용되지 않는다.
- `ProductContent` 순수 도메인 예외 전환과 내부 `*Url` 필드의 `*Key` rename을 I-4/I-8에 포함할지
  별도 이슈로 나눌지.
- 동작·보안·FE 계약 변경과 구조 리팩터링을 저장소의 "동작 변경과 구조 변경을 같은 PR에 섞지 않는다"
  규칙에 맞춰 어떤 이슈/PR 단위로 분리할지.
- ai-service를 함께 변경할 수 있는 개인 fork 후속 작업에서는 `inspectionRequestId`를 검수 요청마다
  생성하고 `PRODUCT_REVIEW_REQUESTED`와 `PRODUCT_INSPECTION_COMPLETED`에 왕복시켜, product-service가
  현재 대기 중인 검수 회차와 일치하는 결과만 반영하도록 검토한다. ai-service consumer의 eventId
  멱등성도 이 후속 작업에서 함께 판단한다. product-service 단독 재발행은 이 보장을 대신하지 못한다.

### 기존 CodeFlow Receipt에서 품질 변화가 확인될 때의 Review 기록

- 이 리팩터링에서는 `.github/workflows/codeflow-card.yml`, `.github/codeflow-card.json`,
  `.github/codeflow-card.svg`와 기존 Receipt 생성 방식을 수정하지 않는다.
- 현재 CodeFlow의 저장소 전체 기준은 health `C`, score `72`다. product-service PR에 기존 CodeFlow
  Receipt가 생성되면 health·score와 `dead code`, `circular deps`, `blast radius`, `avg coupling` 등 실제
  측정 결과를 확인한다.
- product-service 변경 후 **실제로 점수 또는 세부 품질 지표가 개선된 PR이 하나라도 있을 때만**, 그 PR의
  변경 전·후 수치와 개선을 만든 코드 결정을 하나의 `Review` 소재로 남긴다. 모든 PR이나 중간 수정본을
  별도 소재로 만들지는 않는다.
- 전체 score가 `72`에서 움직이지 않더라도 세부 지표가 실제 개선됐다면 Review 근거로 사용할 수 있다.
  반대로 코드가 정리돼 보인다는 이유만으로 측정값을 추정하거나 점수가 올랐다고 쓰지 않는다.
- CodeFlow는 저장소 전체를 측정하므로 product-service의 작은 개선이 전체 등급에 반영되지 않을 수 있다.
  따라서 Receipt는 테스트·쿼리 수·제거된 dead code·의존성 변화처럼 해당 PR에서 직접 확인한 근거와 함께
  해석한다.
- 최종 Publish에서는 실제 변화가 확인된 Receipt 하나만 선택해 `무엇을 변경했는가 → 어떤 지표가
  변했는가 → 왜 그 변화가 의미 있는가` 순서로 작성한다. 변화가 없으면 CodeFlow 성과 항목은 Publish에
  넣지 않는다.

### Publish 초안 재료

| Publish 종류 | 초안에 사용할 근거 |
| --- | --- |
| Reviews | Controller 정책 혼재, URL 재파싱, SellerService 파일 책임, 검증보다 이른 S3 승격, copy 부분 성공·DB commit 실패의 고아 객체, 검색 실시간·재대사 이중 경로, 검수 결과 producer 비동기 실패 관측 부재, consumer/handler 직접 의존과 테스트 공백, product-service PR 중 실제 CodeFlow score 또는 세부 지표 개선이 확인된 Receipt 하나와 그 개선을 만든 코드 결정 |
| Decision | 세 URL/key 계약, ObjectStorageGateway 경계, seller scoped temp key, Lifecycle 우선·Saga 보류, 패밀리 version unique index와 transaction 결과별 현재 요청 S3 보상 정리, RDB 원본·ES projection의 scheduler-only 선택, 검색용 processed-event 제거·주문용 유지, scheduler 전환 후 STOPPED/DELETED/PRICE_CHANGED 로그·검색 이벤트 계약 제거, 검색 consumer 삭제로 Jackson 혼재가 사라져 공통 Kafka parser는 만들지 않음, 검수 발행 유실은 product-service의 실패 로그와 장기 PENDING_REVIEW 최대 1회 재발행으로 최소 방어, ai-service 계약 변경은 개인 fork 후속 범위, `ProductInspectionUseCase`와 짧은 `ProductInspectionService` 이름 |
| Study | Presigned PUT/GET 객체 구조, Duration과 Content-Type 서명, AWS SDK request Builder와 도메인 `ProductContent` Builder의 차이, 12개 위치 인자를 이름 기반 조립으로 바꿔 동일 String 타입 오배치를 줄인 근거, Builder 이후에도 canonical constructor 검증이 필요한 이유, `List.copyOf` 방어적 복사와 값 객체 불변성, JPA AttributeConverter의 List↔DB 문자열 변환과 `TagsConverter` 이름·의존 방향, copy+delete와 부분 성공, DB/S3 트랜잭션 경계, Kafka ACK·재전달·processed-event 멱등성, send future와 consumer 재시도의 차이, product-service 단독 재발행이 보장하는 범위와 검수 회차 correlation의 한계, ES NRT·refresh, polling·Outbox·CDC 비교, 다중 파드 lock/worker |
| Quality | 구현 전에는 예상만 기록하고 Claude 구현 후 테스트·점수·diff로 전후 결과 확정 |
| Troubleshooting | 아직 재현된 운영 장애가 아니므로 현재 실패 가능성만으로 작성하지 않음 |

#### 구현 전 Publish 서사 초안

1. **문제 발견**: 상품 등록·수정 흐름은 S3 직접 업로드, DB 상태 전이, Kafka 검수 요청, ES 색인까지
   연결되지만 Controller와 `ProductSellerService`에 업로드 정책·키 파싱·승격·버전 판정이 섞여 있었다.
   사용자 선택 문자열 하나로 MAJOR/PATCH가 결정되고, S3 승격이 검증보다 앞서며, Kafka 비동기 실패와
   scheduler 다중 파드 조건은 코드만 읽어서는 드러나지 않았다.
2. **검증 방법**: BE Controller·application·domain·adapter·테스트를 실제 FE의 업로드 PUT 완료 대기,
   임시저장 POST 후 edit 이동, 상태별 버튼, 수정 payload와 함께 추적했다. BE가 허용하는 호출과 정상
   UI 경로를 구분하고 S3·DB·Kafka·ES의 트랜잭션 경계를 실패 시나리오별로 나눴다.
3. **설계 판단**: 업로드는 `tempObjectKey`와 presigned PUT/GET URL의 역할을 분리하고 정책·승격·외부
   저장소 포트를 분리한다. 검색은 RDB를 원본으로 둔 scheduler-only eventual consistency를 택하되
   watermark 누락과 단일 파드 전제를 명시한다. 반려 major는 새 row를 계속 만들지 않고 같은 반려
   version을 보정·재요청한다. 판매 후 버전은 사용자가 고르지 않고 서버가 필드 차이로 판정한다.
   상품 유형은 등록 후 불변으로 두고 PROMPT content, NOTION externalUrl, PPT/EXCEL fileKey처럼
   유형별 핵심 산출물이 바뀔 때만 자동 MAJOR로 분류한다. 그 밖의 메타데이터·소개 정보는 PATCH다.
   판정은 별도 service가 아니라 기존 값을 소유한 Product가 수행하고, key 정규화·검증·무변경 판정을
   S3 승격보다 먼저 실행해 실패 요청이 영구 객체를 남기지 않게 한다. Product는 LOC를 줄이기 위해
   상태·버전 manager로 분해하지 않는다. 대신 `update(..., boolean isMajor)`를 제거하고
   `updateDraftContent`, `updateRejectedContent`, `determineVersionType`, `createNextVersion`처럼 상태와
   결과가 호출부에서 읽히는 도메인 행위로 바꾼다. 검수 전이도 `approveInspection`과
   `rejectInspection`, 판매 교대는 `markAsSuperseded`로 의도를 명확히 한다.
   새 version ID는 application이 먼저 생성해 DB row·S3 경로·수정 응답의 식별자를 하나로 맞춘다.
   presigned URL은 object key로 정규화하고 PROMPT 공백·이미지/태그 순서처럼 실제 저장 의미는 보존해
   서명 재발급을 변경으로 오판하지 않으면서 의도한 콘텐츠 변화는 놓치지 않는다.
   FE ref는 같은 화면의 중복 클릭을 막고, DB unique index는 여러 탭·동시 요청에서도 family version
   유일성을 보장한다. 충돌 rollback에서는 현재 요청이 승격한 S3 object만 지워 고아 파일을 막는다.
   `changeReason`은 새 version의 이력 설명이므로 ON_SALE 수정에서만 받고, 같은 version을 보정하는
   DRAFT·REJECTED 편집에서는 요구하거나 덮어쓰지 않는다.
   수정 API는 결과 row의 productId·version·status를 반환해 FE가 서버 판정을 다시 구현하지 않게 한다.
   order-service에서 로그만 남기는 STOPPED·DELETED·PRICE_CHANGED와 scheduler 전환 후 중복되는 검색
   이벤트 경로는 제거하고, 실제 비즈니스 소비자가 있는 REVIEW_REQUESTED만 유지한다.
   조회 계층은 점수를 위해 네 개의 작은 service로 쪼개지 않는다. 합의된 product API URL과 공개
   usecase 메서드는 유지하고, 단일 `ProductQueryService` 안에서 실제 API 흐름 순서·private 메서드
   이름·짧은 정책 주석을 정리한다. ES 전용 실패만 RDB로 폴백하도록
   `ProductSearchUnavailableException`으로 장애 경계를 명시한다. wishlist/order 목적별 ID 조회는
   기존에 N건마다 판매량·평점을 각각 조회해 N=100이면 최대 200회였던 집계 쿼리를
   `getSalesCounts` 1회와 `getAverageRatings` 1회, 총 2회로 고정한다. 조회수는 별도 service를
   만들지 않고 DB의 `view_count = view_count + 1` 원자 갱신으로 동시 요청의 lost update를 막는다.
   현재 계약 문서는
   `docs/api-spec/product.md`만 코드 변경과 동기화하며 과거 records 문서는 이력으로 보존한다.
   판매자 상품 응답 DTO가 `StorageClient`를 받아 S3 GET URL을 생성하던 구조는 application service가
   URL을 먼저 완성해 DTO에 값만 전달하도록 바꾼다. SellerService를 query·URL·mapper service로 다시
   쪼개지는 않고 파일 승격만 `TempFilePromoter`로 옮긴다. I-8은 응답 필드와 URL 의미를
   보존하므로 FE 변경 없이 동작해야 하며, 판매자 카드·수정 화면·공개 상세의 실제 이미지 표시를
   회귀 테스트로 확인한다. 신규 업로드 계약이 바뀌는 I-4에서만 `presignedGetUrl` 미리보기와
   `tempObjectKey` 저장을 BE·FE 같은 배포 단위로 반영한다.
   이 검색 consumer 제거로 Kafka consumer 중 유일하게 Jackson 2를 쓰던 경로도 함께 사라진다. 남은
   두 consumer의 짧은 parse 메서드를 없애기 위해 공통 parser를 새로 만들지 않는다. 또한 Product가
   infrastructure의 `TagsConverter`를 import하던 역방향 의존은 동일 DB 형식을 유지한 채 converter를
   `StringListConverter`로 이름·위치만 바로잡는다. 이 converter는 Java의 `List<String>`과 쉼표로
   연결된 DB 문자열 사이를 JPA가 저장·조회 시 자동 변환한다.
   `ProductContent`는 같은 String 타입이 연속된 12개 위치 인자를 Builder의 이름 기반 조립으로 바꿔
   description·model·content·fileUrl·externalUrl 오배치 위험과 선언부 왕복 읽기를 줄인다. Builder를
   검증 대체 수단으로 쓰지는 않고 record canonical constructor의 유형별 필드 검증을 유지한다.
   imageUrls와 tags도 `List.copyOf`로 방어적 복사해 외부 list 변경이 값 객체와 content hash 의미를
   뒤늦게 바꾸지 못하게 한다.
   검수 이벤트 신뢰성은 ai-service까지 동시에 고치지 않는다. product-service가 send future 실패를
   eventId·eventType·productId 기준으로 관측하고, 설정 시간 이상 PENDING_REVIEW인 상품을 DB
   스냅샷으로 최대 한 번 재발행한다. 이 방식은 Outbox나 별도 이벤트 본문 저장소 없이 최초 요청
   발행 실패와 AI 결과 유실로 인한 영구 대기를 실용적으로 줄인다. 반면 반려 후 재요청처럼 서로
   다른 검수 회차의 오래된 결과를 완전히 구분하지는 못한다. `inspectionRequestId` 왕복과 AI consumer
   멱등성은 ai-service를 변경할 수 있는 개인 fork의 후속 설계로 분리한다.
4. **과설계 배제**: UI 더블클릭은 기존 `submitting` ref가 막고, 최초 임시저장 뒤 같은 DRAFT를 PATCH한다.
   동일 POST의 서버 멱등성은 관측된 문제가 없어 별도 key/table을 만들지 않는다. S3 고아 객체에 Saga,
   Kafka에 무조건 Outbox, 검색 scheduler에 선제 분산 lock도 도입하지 않고 실패 관측과 scale-out 시점의
   조건으로 남긴다. 검수 재발행도 무제한 반복하지 않고 한 번으로 제한해 Kafka 장애 중 OpenAI 중복
   호출이 누적되는 위험을 막는다.
5. **구현 후 채울 성과**: 각 PR의 BE·FE 테스트와 build, Claude Quality 전후 점수, 클래스 책임·LOC,
   이벤트 실패 관측 가능성, 누락 방지 테스트 수를 실제 결과로 기록한다. 구현 전 예상치를 성과처럼
   쓰지 않으며 최종 Publish는 검증된 diff와 수치로 교체한다. 집계 쿼리는 설계상 `2N → 2`이며
   최종 문구의 “100건 기준 최대 200회 → 2회”는 구현 후 repository 호출 검증과 실제 쿼리 로그가
   확인됐을 때만 확정 성과로 사용한다.

#### 임시저장 중복 요청 최종 판단

- FE의 신규 상품 임시저장은 `POST /products`로 DRAFT 1.0을 만들지만 `submitting.current`가 같은 화면의
  연속 클릭을 차단하고, 성공 즉시 `/edit/{productId}`로 이동한다.
- 이후 저장은 동일 productId를 PATCH하므로 DRAFT row가 늘어나지 않는다.
- 우회된 동일 POST 두 건은 서로 다른 family root를 만들 수 있으나 실제 장애가 확인되지 않았고, 이를
  막기 위한 client request id·멱등성 table은 현재 요구보다 크다.
- 따라서 기존 FE 방어를 유지하고 신규 리팩터링 이슈를 만들지 않는다. 중복 사례가 관측될 때 요청
  멱등성을 별도 decision으로 재평가한다.

다음 재개 지점은 **PR 2 판매 후 REJECTED major row 동일 version 재편집**이다. PR 1의 object key
계약·`TempFilePromoter`·Swagger 규칙과 실제 merge 결과를 선행 조건으로 유지하고, PR 2~7을 한 번에
하나씩 구현·검증한다.

---

## 고치지 않기로 한 것

점수는 잃지만 손대지 않는다. 이유를 남겨 다음 점검에서 다시 논쟁하지 않게 한다.

| 항목 | 손실 | 이유 |
| --- | --- | --- |
| `ProductQueryGrpcService.java:44-53,71-81` 스냅샷 매핑 중복 | −2 | `GetOrderSnapshots`/`GetCartSnapshots`는 order-service 소비자 전환이 끝날 때까지 유지하는 **의도된 과도기**다 (`.claude/rules/product-api.md`에 명시) |
| `ProductJpaRepository`의 `findPublicProducts` ↔ `findProjectionsByIds` JPQL 중복 | −0.5 | JPQL에 조각을 공유할 수단이 마땅치 않다 — 구조적 제약 |
| `Product` 엔티티 비대 (248 LOC·17메서드) | −1 | 상태 전이 가드마다 작고 단순한 메서드가 많아 **응집도는 오히려 높다.** 쪼개면 도메인 규칙이 흩어진다 |

→ 전부 반영해도 상한은 **약 91~93점**이다.

---

## 이 로드맵 이후

운영 검색에서 확인된 정확성 결함은 잔여 품질 리팩터링보다 먼저 해결한다. 그 밖의 후속은 지금 착수하지
않고 순서와 이유만 적어둔다.

### 최우선 후속 — #737 하이브리드 검색 후보 재랭킹과 관련성 차단

2026-08-13 운영 웹사이트에서 `취업`은 0건, `자기소개서`는 관련 상품 1건이지만
`취업 자기소개서`는 관련 상품과 함께 스터디 운영 노션 템플릿, 프로젝트 종료 보고서, 주식 분석
프롬프트까지 4건을 반환했다. 프로젝트 종료 보고서에는 검색어·태그 일치가 없으므로 BM25가 아니라
의미 검색 후보로 들어온 뒤 RRF 병합에서 살아남은 검색 정밀도 결함으로 판단한다.

#### 원인과 폐기한 접근

- kNN은 관련 상품의 존재를 판정하지 않고 전체 문서 중 상대적으로 가까운 Top-K를 찾는다. 상품 수가
  적고 도메인이 다양하면 관련 상품이 거의 없는 질의에서도 덜 무관한 상품이 상위 후보가 될 수 있다.
- 현재 코드는 kNN 원본 코사인 `similarity >= 0.35`를 이미 병합 전에 적용한다. 병합 후 제거 후보가
  재합류하는 구현 결함은 없었다.
- RRF는 BM25와 kNN의 원점수를 버리고 각 목록의 등수만 합친다. 따라서 kNN에서 상대적으로 높은
  무관 상품은 판매량 0이어도 최종 인기순 상위에 들어갈 수 있다.
- 코사인 하한을 0.35에서 0.40으로 올리는 시도는 폐기한다. 실제 문제 상품의 점수를 측정하지 않은
  데이터 맞춤 상수이고, 0.40 이상이면 오탐이 남으며 0.35~0.40의 정상 의미 결과는 새로 잃는다.
  이 시도의 코드·합성 경계 테스트·0.40 문서 변경은 구현 작업을 재개할 때 제거한다.
- BM25 일치를 모든 결과의 필수 조건으로 만드는 방식도 채택하지 않는다. 그러면 `이력서 작성`으로
  `자기소개서 첨삭`을 찾는 의미 검색의 목적이 사라진다.

구현 재개 시 첫 단계에서 다음 폐기 변경을 모두 제거하고 `git diff`로 잔존 여부를 확인한다.

- `ProductSearchQueryBuilder.MIN_SEMANTIC_SIMILARITY`를 0.40에서 기존 0.35로 복원한다.
- `ProductSearchQueryBuilderTest`의 `similarity=0.40` 단정은 기존 안전 하한 검증으로 복원한다.
- `ElasticsearchProductSearchQuerierIntegrationTest`의 합성 코사인 0.39 제외·0.41 유지 테스트와
  해당 벡터 helper를 삭제한다.
- `docs/architecture/search-vector-flows.md`의 0.40 보정값·근거를 제거하고 최종 reranker 계약으로
  교체한다.

#### 확정 검색 파이프라인

```text
BM25 후보 + kNN 후보
        ↓
RRF 후보 병합
        ↓
상위 후보를 cross-encoder reranker가 질의와 상품 원문을 함께 평가
        ↓
reranker 최소 관련성 점수 미달 제거
        ↓
통과한 후보에 인기순·평점순·낮은 가격순 적용
```

각 단계의 책임은 다음과 같이 고정한다.

| 단계 | 책임 | 관련성 탈락 판단 |
| --- | --- | --- |
| BM25 | 상품명·태그·소개글·모델명의 정확한 단어 일치 후보 수집 | 기존 `minimum_should_match` 유지 |
| kNN | 표현이 달라도 의미가 가까운 후보를 넓게 수집 | 기존 0.35는 먼 벡터를 줄이는 안전 하한으로만 유지 |
| RRF | 서로 다른 점수 체계의 두 후보 목록을 하나로 합침 | 사용하지 않음. RRF 점수로 관련성을 판정하지 않음 |
| reranker | 질의와 상품 텍스트를 함께 읽고 실제 검색 의도 관련성을 재평가 | 모델별 검증으로 정한 `min_score` 미달 제거 |
| 정렬 | 관련성을 통과한 후보 안에서 사용자가 고른 정렬 적용 | 후보 추가 금지 |

**외부 Jina Reranker v3**를 사용한다. 최초 설계의 Elasticsearch native
`text_similarity_reranker`는 self-managed Basic 구독에서 사용할 수 없어 구현에서 제외했다. 기존 앱
내 RRF 결과 50건을 JDK `HttpClient` 기반의 작은 Jina adapter가 한 번에 재평가하고, API key는 Kubernetes
Secret에서만 주입한다. 별도 SDK·새 의존성·ES mapping 필드는 추가하지 않는다.

모델 선택 근거는 다음과 같다.

| 후보 | 판단 | 근거 |
| --- | --- | --- |
| Elastic 내장 `.rerank-v1` | 제외 | 공식 지원 범위가 영어 전용이라 한국어 상품 검색의 핵심 조건을 만족하지 않는다 |
| Elastic Inference Service의 `.jina-reranker-v3` | 현재 제외 | 모델은 적합하지만 Elastic 구독·EIS 가용성에 종속된다. 현재 self-managed 클러스터의 전제와 맞지 않는다 |
| Jina AI의 `jina-reranker-v3` | **선택** | 다국어 listwise reranker이며 최대 64개 문서를 함께 비교한다. Basic 라이선스 제약 때문에 앱에서 API를 한 번 직접 호출한다 |
| Cohere multilingual rerank | 예비 대안 | 다국어 rerank는 가능하지만 현재 선택안보다 추가 이점이 확인되지 않았다. Jina 품질·가용성이 판정 기준을 통과하지 못할 때만 같은 판정표로 비교한다 |
| 직접 배포한 다국어 cross-encoder | **용량상 제외** | 현재 ES 파드는 1 CPU·메모리 1GiB·JVM heap 512MiB이며 사용자가 현재 배포 용량으로 모델을 운영할 수 없음을 확인했다. 검색 클러스터에 모델 추론 자원과 운영 책임을 함께 얹지 않는다 |

Jina v3가 다국어 모델이라는 사실만으로 한국어 품질을 확정하지 않는다. 아래 판정표와 P95·오류율·비용은
배포 후 운영 확인 항목으로 남긴다. 기준을 충족하지 못할 때만 Cohere multilingual rerank를 동일 조건으로
비교하며, 모델을 직접 호스팅하는 방향으로 자동 확대하지 않는다.

이 선택에서 상품 검색 요청의 모델 추론은 Jina 외부 인프라가 수행한다. 현재 Kubernetes의 ES·product
파드에는 모델 메모리나 추론 CPU를 추가하지 않는다. 로컬 자원 증설을 전제로 한 구현은 #737 범위가
아니며, 외부 API 비용·지연·장애는 아래 실패·성능 정책으로 제한한다.

reranker 입력은 색인 문서의 상품명·태그·소개글을 조립하고, PROMPT에는 모델명을 추가한다. 본문은
placeholder와 템플릿 변수가 오탐을 만들었던 이력이 있어 제외한다. KNN 임베딩 원문에도 PROMPT 모델명을
추가하되 `productType=all`은 유지해 관련된 NOTION·PPT·EXCEL도 함께 검색한다.

#### 관련성 판정 데이터와 선택 기준

현재 공개 상품 수가 적으므로 대표 상품 일부만 고르는 대신 공개 상품 전체를 대상으로 사람이 판정한
질의-상품 표를 만든다. 판정은 `0=무관`, `1=부분 관련`, `2=관련`, `3=매우 관련` 네 단계로 기록한다.

| 질의 유형 | 필수 예시 | 기대 |
| --- | --- | --- |
| 정확 일치 | `자기소개서 첨삭` ↔ 자기소개서 첨삭 | 3 |
| 표현 변환 | `이력서 작성` ↔ 자기소개서 첨삭 | 2 이상 |
| 현재 오탐 | `취업 자기소개서` ↔ 프로젝트 종료 보고서 | 0 |
| 현재 오탐 | `취업 자기소개서` ↔ 주식 분석·스터디 운영 | 0 |
| 다른 상품의 양성 | `프로젝트 회고` ↔ 프로젝트 종료 보고서 | 2 이상 |
| 다른 상품의 양성 | `종목 분석` ↔ 주식 분석 | 2 이상 |
| 결과 없음 | 상품군과 무관한 질의 | 전부 0, 검색 결과 0건 |

BM25 단독, 현재 RRF, reranker 후보를 같은 판정표로 비교한다. Elasticsearch `_rank_eval` 또는 동일한
고정 테스트 데이터로 `Precision@5`, `nDCG@5`, 첫 관련 상품 순위와 0건 질의의 오탐 수를 기록한다.
모델과 `min_score`는 특정 한 질의가 아니라 전체 판정표에서 다음 조건을 만족하는 조합으로 확정한다.

- 판정 2 이상인 기존 BM25 양성 상품을 누락하지 않는다.
- `이력서 작성`처럼 BM25가 놓치는 의미 양성을 최소 한 건 이상 유지한다.
- 판정 0인 현재 세 오탐을 모두 제외한다.
- 0건 질의에는 추천 상품을 검색 결과로 채우지 않는다.
- 동률이면 외부 비용·P95 지연·운영 메모리가 작은 모델을 선택한다.

#### 정렬 의미

후보 관련성 판정과 사용자의 정렬 선택을 분리한다. 인기순은 현재처럼 BM25 인기 점수 목록과 kNN
유사도 목록의 RRF 순서를 그대로 노출하지 않는다. reranker를 통과한 후보 전체에 판매량·조회수·평점·
신선도 기준을 동일하게 적용한다. 평점순과 낮은 가격순도 같은 통과 후보 집합을 각각 평점·가격으로만
재정렬한다. 정렬 변경은 후보를 추가하거나 탈락한 상품을 복원하지 않는다.

#### 실패·성능 정책

- reranker 정상 응답: `min_score` 통과 결과만 반환한다.
- reranker timeout·endpoint 장애: 관련성 검증을 건너뛰고 무관한 semantic-only 후보를 노출하지
  않는다. BM25 결과만 반환하는 보수적 폴백을 사용한다.
- 질의 임베딩 실패: 기존처럼 BM25 결과만 반환한다.
- ES 전체 장애: 기존 RDB 폴백 정책은 #737에서 변경하지 않는다.
- 최초 후보 창은 각 레그 50건, rerank 창 50건을 기준으로 측정한다. 상품 증가 시 20건은 recall과
  페이지 수를 과도하게 제한하므로 50건으로 확정했다. Jina 토큰 사용량과 P95는 배포 후 확인한다.

#### 필수 검증과 완료 조건

- 검색 요청 조립 테스트: BM25+kNN → RRF → Jina와 `min_score`가 한 파이프라인으로 구성된다.
- 관련성 회귀 테스트: 위 판정표의 양성 유지·현재 오탐 제외·0건 허용을 검증한다.
- 실패 테스트: reranker timeout·오류 시 BM25-only로 축소되고 semantic-only 후보가 노출되지 않는다.
- 정렬 테스트: 인기순·평점순·가격순 모두 동일한 통과 후보 ID 집합을 유지한다.
- `:product-service:test`와 `:product-service:build`를 통과한다.
- 배포 후 운영 웹사이트에서 `취업`, `자기소개서`, `취업 자기소개서`, `이력서 작성`, `프로젝트 회고`,
  `종목 분석`을 확인하고 판정표와 결과가 일치해야 한다.
- 모델명·endpoint·`min_score`·후보 창은 구현값을 기록하고, 실제 P95와 판정표 결과는 배포 후 기록한다.

#### 실제 구현·검증 기록 (2026-08-13)

- 범위: 기존 BM25+kNN msearch와 앱 RRF는 유지하고 후보 창을 50으로 제한했다. Jina v3가 상품명·태그·
  소개글·PROMPT 모델명을 재평가해 `min-score=0.5` 미만을 제거하며, 통과 ID만 기존 인기·평점·가격
  쿼리로 다시 정렬한다. PROMPT 모델명은 KNN 임베딩 원문에도 추가했다.
- 설계 차이: ES native reranker는 self-managed Basic 라이선스 제약 때문에 application Gateway와 JDK
  `HttpClient` adapter로 대체했다. 별도 `rerankText` mapping과 ES inference endpoint는 만들지 않았다.
- 장애 정책: Jina key는 배포 시 권장하지만 Kubernetes와 설정 바인딩에서는 optional이다. key 미설정·
  timeout·비정상 응답은 BM25-only, ES 실패는 기존 RDB fallback을 유지한다.
- 검증: Jina 요청/점수 필터/본문 제외/장애 fallback, PROMPT 모델 임베딩, 통과 ID 재정렬,
  Elasticsearch 하이브리드·정렬 통합 테스트를 추가했다. `:product-service:test`,
  `:product-service:build`, Kubernetes Secret 계약 검증과 `git diff --check`가 통과했다. FE 계약과
  파일은 변경하지 않아 FE lint·build는 대상이 아니다. build의 기존 generated protobuf checkstyle
  warning 152건은 남지만 이번 변경 Java 파일의 새 warning은 없다.
- Quality: 새 SDK 없이 application Gateway 1개와 JDK adapter·설정 record로 외부 SaaS 경계를 분리했다.
  API key·상품 본문을 로그에 남기지 않으며, 어려운 fallback 규칙에만 짧은 주석을 남겼다.

#### 배포 후 E2E 회귀와 후속 수정 (2026-08-13)

- 운영 확인: `취업`은 자기소개서·이력서·면접 3건을 반환했지만 `취업 자기소개서`는 자기소개서 1건만
  반환했다. `이력서 작성`, `프로젝트 회고`, `종목 분석`은 각 구성 단어의 단독 검색에서 관련 상품이
  존재하는데도 모두 0건이었다. `동물`은 동물 이미지 가이드만 반환하고 의미상 하위 개념인 `펭귄 생성
  프롬프트`를 누락했다.
- 원인: 과거 BM25 오탐을 막기 위해 추가한 `minimum_should_match=2<75%`가 reranker 앞의 BM25 후보
  수집에도 그대로 적용됐다. 두 단어 질의는 두 단어가 모두 일치한 상품만 Jina에 전달되어, reranker가
  한 단어만 일치한 관련 상품을 재평가할 기회가 없었다.
- 설계 변경: 정상 하이브리드 경로의 BM25 후보 수집은 Elasticsearch 기본 OR를 사용해 한 단어 일치
  상품까지 넓게 수집하고 최종 관련성은 Jina가 판단한다. Jina key 미설정·timeout·오류 시 실행하는
  BM25-only 폴백은 기존 `2<75%`를 유지해 과거의 한 단어 오탐 회귀를 막는다.
- 범위 제한: `동물`처럼 한 단어 질의에서 의미상 관련 상품이 누락되는 문제는 이 변경으로 해결되지
  않는다. 해당 상품이 kNN `similarity=0.35`, 상위 50건, Jina `min-score=0.5` 중 어느 단계에서
  탈락하는지 후보 점수 관측 후 별도로 조정한다. 이번 후속 PR에는 임계값 변경과 운영 로그 추가를
  포함하지 않는다.
- 검증: 정상 하이브리드에서는 두 단어 중 하나만 일치한 BM25 후보가 reranker에 전달되고, reranker
  실패 시에는 같은 후보가 엄격한 BM25 폴백에서 제외되는 통합 테스트를 추가했다. 검색 쿼리 집중
  테스트, `:product-service:cleanTest :product-service:test`, `:product-service:build`,
  `git diff --check`가 통과했다. build에는 기존 generated protobuf checkstyle warning 152건만 남았다.
- Publish 인계: 과거 오탐 방지용 BM25 정책이 reranker 도입 후 재현율을 제한한 원인과, 정상 경로는
  recall 우선·장애 경로는 precision 우선으로 분리한 결정을 Troubleshooting·Decision 후보로 남긴다.

#### 머지 후 E2E 체크리스트

- `취업 자기소개서`: 자기소개서·이력서 첨삭 상품 유지, 보고서·주식·스터디 상품 제외
- `이력서 작성`: 자기소개서 첨삭 상품 유지
- `프로젝트 회고`: 프로젝트 종료 보고서 노출
- `종목 분석`: 주식 분석 상품 노출
- `동물`: 동물 이미지 가이드 유지. 펭귄 상품 누락은 별도 의미 검색 관측 대상으로 기록
- `gpt`, `claude`: 해당 모델의 PROMPT 상품 노출
- `productType=all`: 관련된 여러 상품 유형 유지
- 인기순·평점순·가격순: 동일 후보 ID 집합 유지
- 무관 검색어: 검색 결과 0건
- Jina 장애: 2초 이내 BM25-only 결과 제공
- ES 장애: 기존 RDB 폴백 확인
- 응답 시간 P95와 Jina 호출 오류율 확인

`min-score=0.5`는 배포 전 자동 테스트의 mock 점수로 확정하지 않는다. 위 고정 질의와 실제 운영 상품을
사용해 양성 누락과 오탐을 기록한 뒤 조정하며, 그 결과와 P95는 머지 후 이 절에 추가한다.

진행 순서는 **#737 검색 정확성 수정 → PR8(I-10) → 나머지 후속 이슈**다. #737은 검색 결과의 신뢰성
결함을 고치는 작업이므로 행동 로그 기반 고도화 #381보다 먼저 처리한다.

현재 상태: `IMPLEMENTED · PR_PENDING`.

| 이슈 | 작성자 | 판단 | 이유 |
| --- | --- | --- | --- |
| #411 ProductQueryService 책임/CQS | `git-mesome` | **PR 6에서 #731과 함께 close** | 4개 service 분리 대신 목록·자동완성을 검색 service/usecase로 분리하고, 나머지 공개 조회는 응집된 단일 service로 유지한다. 조회수는 atomic update로 동시성 위험을 제거하며 GET의 부수 효과는 테스트와 이름으로 명시한다 |
| #684 반려 상품 삭제 | `git-mesome` | **유지·직접 close 금지** | REJECTED를 실제 소프트 삭제할지, 현 설계(STOPPED 전이 후 목록 유지)가 맞고 FE 버튼만 고칠지 정책 결정이 먼저다 |
| #555 Kafka consumer DLT | `Jinpyo-An` | **유지·변경 금지** | 현재 코드에 DLT가 이미 있어도 다른 작성자의 이슈다. 이번 로드맵에서 댓글·close·재범위화를 수행하지 않는다 |
| #560 판매자 목록 페이징 | `gfkmkl` | **로드맵 이후 진행** | I-7 이후 `PageResponse` 계약과 FE를 함께 변경한다. 해당 후속 구현 PR이 완료되면 close 가능하다 |
| #508 주간 트렌딩 랭킹 | `gfkmkl` | **로드맵 이후 진행** | 신규 테이블 + JPQL 분기를 얹는 작업이라 현재 조회 순서·집계 구조를 먼저 정리한다. 해당 후속 구현 PR이 완료되면 close 가능하다 |
| #381 행동 로그 파이프라인 | `gfkmkl` | **#737·PR8 이후 검색 고도화 1순위** | 행동 데이터가 #382·#383·#650의 재개 판단 근거다. 독립 대형 작업으로 별도 구현·검증 후 close한다 |
| #582 ES 보안 전환 | `gfkmkl` | **CLOSED · NOT_PLANNED** | PR #659로 애플리케이션의 HTTPS·인증 연결 지원과 가이드는 유지하되, 클러스터 보안 활성화와 계정·권한 분리는 진행하지 않기로 2026-08-13 결정했다 |

#518은 사용자가 직접 정리한 이슈이므로 Claude 인계와 이 로드맵의 close 작업 대상에서 제외한다.
작성자가 다른 #555·#684는 자동 close하거나 본문을 바꾸지 않는다. #411은 사용자의 2026-08-12
명시적 결정에 따라 PR 6 완료 시 사용자 소유 #731과 함께 close한다.

### closed 상태지만 구현되지 않은 검색·ES 고도화 후속

아래 이슈는 모두 `gfkmkl` 작성이며 `NOT_PLANNED`로 닫혔지만, 구현 완료가 아니라 당시 근거 부족으로
보류한 작업이다. 현재 품질 리팩터링 PR이 끝났다는 이유만으로 자동 reopen하지 않고 재개 조건을 먼저
검증한다.

| 기존 이슈 | 후속 판단 | 재개 조건·순서 |
| --- | --- | --- |
| #382 검색 히스토리·인기 검색어 | **#381 이후 조건부 재개** | 실제 사용자 검색이 일 수십 건 이상 쌓이면 인기 검색어부터 검토한다. 최근 검색어는 브라우저 autofill로 부족하다는 요구가 있을 때만 진행한다 |
| #383 개인화 홈 추천 | **#381 이후 장기 고도화** | 사용자별 행동이 최소 3건 이상 쌓여 콜드스타트 외 결과를 검증할 수 있을 때 취향 벡터·다양성 규칙을 재설계한다 |
| #650 자동완성 상품 바로가기 | **행동 지표 확인 후 조건부 재개** | 제안 클릭 후 검색 결과 이탈 또는 상품 재탐색 문제가 확인될 때 문자열 응답을 `{id, name, productType}`으로 변경한다 |
| #594 크리에이터 이름 검색 | **별도 다중 서비스 기능** | 크리에이터 페이지·탐색 요구가 생기면 user-service 검색 API와 FE 결과 섹션을 우선 검토한다. product ES에 이름을 복제하는 방안은 이름 변경 동기화 비용 때문에 후순위다 |
| #602 PPT·EXCEL·NOTION 본문 텍스트 추출 | **검색 품질 측정 후 조건부 재개** | 현재 제목·설명·태그 임베딩만으로 검색 변별력이 부족하다는 측정이 있을 때 POI/S3 다운로드/외부 URL 수집/용량·실패 정책을 별도 설계한다 |

#583 nori 커스텀 ES 이미지는 후속 고도화에 포함하지 않는다. 실제 토큰 분석에서 사전 없는 nori가
도메인 어휘를 잘못 분리했고 standard 분석기로 전환해 원래 문제 전제 자체가 사라졌기 때문이다.

---

## 공통 규칙

- 항목당 **1 이슈 = 1 브랜치 = 1 PR**. 하나의 브랜치에 관련 없는 항목을 섞지 않는다.
- 동작 변경과 구조 변경을 같은 PR에 섞지 않는다.
- PR 전 `.\gradlew.bat :product-service:build --no-daemon`으로 컴파일·checkstyle·테스트를
  함께 확인한다. `test` task만 단독 실행하지 않는다.
- 계약이 바뀌는 항목은 **I-3과 I-4**다. I-3은 FE가 해당 필드를 optional로 받고 있어 안전함을
  확인했다. I-4는 presigned URL 응답 필드를 변경하므로 BE·FE를 연결된 이슈로 관리하고 같은 배포
  단위에서 반영한다. gRPC·이벤트 payload는 이번 로드맵에서 바꾸지 않는다.
- `docs/api-spec/product.md`·`docs/error-codes.md` 영향이 있으면 같은 PR에서 동기화한다.

---

## 개인 fork 후속 적용 — 현재 리팩터링 구현 범위 제외

### 카카오 프로필 이미지 HTTP URL로 인한 Mixed Content

- **확인된 현상**: HTTPS로 배포된 FE에서 `http://*.kakaocdn.net` 프로필 이미지를 직접 요청하면
  Mixed Content 경고가 발생한다.
- **재현 및 원인 확인**: 사용자 DB의 `profile_image_url` 값을 동일 경로의 `https://` URL로 직접
  변경한 뒤 다시 조회했을 때 경고가 사라졌다. 따라서 상품 이미지 문제가 아니라 기존 카카오 프로필
  이미지 URL의 HTTP 스킴이 원인이다.
- **현재 결정**: product-service 품질 리팩터링의 구현·이슈 생성 범위에는 포함하지 않는다. 현재
  저장소의 user-service 및 FE 코드도 이 로드맵에서 수정하지 않는다.
- **후속 작업 시점**: S3 Lifecycle 적용과 마찬가지로 사용자가 저장소를 개인 fork한 뒤 별도 작업으로
  검토한다.
- **후속 검토 후보**: 기존 DB의 카카오 프로필 URL 정리와 FE 표시 경계에서의 HTTPS 정규화다.
  user-service 변경 여부는 아직 확정하지 않는다.

#### PR4 재검증 메모 (2026-08-11)

- PR4 결함 수정 후 `:product-service:test` 전체 통과. 패키지 이동으로 깨진 테스트 참조도 함께 정리했다.
- #723 패키지 구조를 적용해 application 기능별 하위 패키지, infra 관심사별 persistence/batch, presentation 기능별 controller로 정리했다.
- FE는 변경 파일이 없으며 `npm run build`는 통과했다. `npm run lint`는 기존 FE 전역 오류 22건으로 실패했으며 PR4 회귀로 보지 않는다.
- 현재 상태: `IMPLEMENTED · PR_PENDING`.

---

#### PR4 후속 구조·가독성 반영 메모 (2026-08-12)

- `search/application`을 `query`, `indexing`, `embedding` 기능 패키지로 분리하고, `search/infra/es`를
  `config`, `query`, `indexing`으로 나눴다. 검색 application은 product의 사용자 요청을 직접 받는 계층이
  아니라 검색 기능을 제공하는 내부 모듈이므로 별도 `usecase/service` 계층은 추가하지 않았다.
- 검색 경계 이름을 `ProductSearchQueryPort`, `ProductSearchIndexPort`로 명확히 하고, 이벤트 처리 클래스는
  `ProductSearchEventProcessor`로 이름을 바꿨다. 이벤트 분기 메서드는 `routeProductEvent`·`routeRemovalEvent`,
  family 색인 입력 조립 메서드는 `buildFamilyUpsertInput`으로 이름을 정리했다.
- 이 구조와 이름 변경은 동작 변경이 아닌 패키지·명명 정리다. ES bulk item별 실패 검증과 회귀 테스트는
  기존 PR4 변경으로 유지한다.
- 검증: `:product-service:test` 전체 통과, `git diff --check` 통과.
- 현재 상태: `IMPLEMENTED · PR_PENDING`.

### I-10 · PR4 품질 재검증 잔여 부채 — 예외 규율·타임아웃·응답 DTO 값객체 — `refactor` — +18

PR4 구현 완료 후 품질 재검증(2026-08-11, 오늘 게시된 35.0점 스냅샷의 findings 29건을 코드로
재대조)에서 확인한 잔여 위반 중, PR5~PR7(I-2·I-3·I-5·I-7·I-9)의 기존 범위에 들지 않는 것만
모은다. I-2·I-3·I-5·I-7·I-9와 겹치는 항목(ES bulk·alias·size(10000), 항상 null인 응답 필드,
ProductType 분기, ProductQueryService 비대, ProductFamily 죽은 메서드 2개·`ProductContent`
Builder)은 각자의 PR에 그대로 둔다.

| 항목 | 위치 | 회복 |
| --- | --- | --- |
| ES 조회 실패를 광범위한 `catch (RuntimeException)`으로 감싸 RDB 폴백 | `ProductQueryService.java:66` | +2 |
| "이미 처리된 이벤트" 판별이 범용 `IllegalStateException` catch에 의존 — 나중에 같은 타입의 다른 실패가 추가되면 조용히 중복으로 오판될 수 있다 | `ProductInspectionResultHandler.java:40`, `ProductInspectionResultConsumer.java:65` | +2 |
| 공용 `ForkJoinPool`로 임베딩 조회, 동시 중복 요청을 막는 single-flight 장치 없음 | `QueryEmbeddingCache.java:71` | +2 |
| S3/ES 클라이언트에 명시적 호출 타임아웃 없음 | `S3Config.java:14-19,21-26`, `ElasticsearchClientConfig.java:65-76` | +2 |
| `ProductFamily.hasEverBeenOnSale()` — 테스트에서만 호출(I-9가 잡은 죽은 메서드 2개 외 3번째) | `ProductFamily.java:69` | +2 |
| presign null-safe 래퍼 중복 — PR4에서 `ObjectStorageGateway.presignIfPresent()`로 2곳(`ProductSellerService`·`ProductInspectionRequestPublisher`)은 정리했지만 3곳이 남음 | `ProductQueryService.java`, `PurchasedProductQueryService.java`, `ProductGrpcService.java` | +2 |
| 공개 조회 응답 DTO가 27필드(`ProductDetailResponse`)·15필드(`ProductListItemResponse`) 포지셔널 record 생성자 — I-9의 `ProductContent` Builder 적용과 별개 대상 | `ProductDetailResponse.java`, `ProductListItemResponse.java` | +2 |
| `promoteKeys`/`promoteKey`가 호출부가 채우는 출력 파라미터(`List<String>`) 2개를 받는다 — `PromotionRecord(tempKey, permanentKey)` 값객체 리스트 반환으로 정리 가능 | `TempFilePromoter.java:70-102` | +2 |
| `IllegalStateException`→409(`PRODUCT_INVALID_STATUS`) 일괄 매핑이 ES 인프라 장애(`ElasticsearchProductSearchIndexer` 4곳)까지 같은 상태로 위장시킨다 — `Product.java` 도메인 순수 예외 부재와 얽힌 문제라 과거 스냅샷에서도 "의도 미확인" 설계 질문으로만 남아 있었다 | `ProductExceptionHandler.java:33-44` | +2 |

**제외한 것(범위 밖)**: `ProductQueryGrpcService`의 order/cart snapshot 빌더 중복은 order-service
소비자 전환 완료까지 유지하기로 이미 결정된 의도된 과도기 상태다(`docs/records/plan/product-api.md`
참고 — 임의로 정리하지 않는다). `ProductJpaRepository`의 JPQL 프로젝션 중복은 JPQL에 조각 공유
수단이 마땅치 않은 구조적 제약에 가까워 별도 정리 대상으로 잡지 않는다.

**검증**: `ProductQueryService`/검수 컨슈머 예외 케이스가 여전히 의도한 동작(ES 폴백, 중복
이벤트 스킵)을 유지하는지, single-flight 도입 후 동시 동일 keyword 요청이 OpenAI 호출을 중복
발생시키지 않는지, S3/ES 타임아웃 설정 후 정상 응답 시간 내 회귀가 없는지, 응답 DTO 필드 값이
Builder 도입 전후 동일한지, `promoteKeys` 리팩토링 후 기존 파일 승격·보상 삭제 테스트가 그대로
통과하는지 확인한다.

#### PR 8 구현 결과 (2026-08-13) — `IMPLEMENTED · PR_PENDING`

이슈 #738, 브랜치 `refactor/#738-post-pr4-quality-debt`(`fix/#737-hybrid-search-relevance-filter`
최신 커밋 기준). 착수 전 코드 재확인(Codex 교차검증 포함)으로 원안 9개 중 이미 해소된 2개(ES
조회 catch 구체화 — PR6에서 `ProductSearchUnavailableException`으로 이미 구체화됨, presign
래퍼 통일 — 3곳 중 2곳은 이미 `presignIfPresent()`로 통일되어 있었고 나머지 1곳(`ProductGrpcService`)은
애초에 이 패턴이 아니었음)을 제외했다.

**실제 구현 범위 — 5개**

1. `ProductInspectionResultHandler`/`ProductInspectionResultConsumer` — 중복 이벤트 판별을
   `catch (IllegalStateException)`에서 `product.getStatus() != PENDING_REVIEW` 사전 체크로
   바꿨다. 예외를 제어 흐름으로 쓰지 않아, 다른 원인의 실패가 "중복이라 스킵"으로 오판되지
   않고 그대로 전파돼 Kafka 재시도/DLT 경로를 탄다.
2. `ProductFamily.hasEverBeenOnSale()` 삭제(프로덕션 호출 없음, 테스트도 함께 삭제).
3. `ProductDetailResponse`(25필드)·`ProductListItemResponse`(13필드) — record와 `from()`은
   그대로 두고(저장소 컨벤션상 Response DTO는 Builder 대상이 아님), 각 필드에 서로 다른 값을
   채워 위치 인자 순서 실수를 잡아내는 테스트를 추가했다. 프로덕션 코드 diff는 0.
4. `S3Config`(`copyObject`/`deleteObject`가 쓰는 `S3Client`만 해당, 로컬 서명이라 네트워크
   호출이 없는 `S3Presigner`는 제외)와 `ElasticsearchClientConfig`에 명시적 connect/call
   timeout을 추가했다.
5. `Product.java`의 상태 가드 6곳(`updateDraftContent`·`updateRejectedContent`·`supersede`·
   `submitForReview`·`approve`·`reject`)이 던지던 범용 `IllegalStateException`을 도메인 순수
   예외 `ProductInvalidStatusException` 하나로 통일했다. `ProductExceptionHandler`는 이제 이
   타입만 409(`PRODUCT_INVALID_STATUS`)로 매핑하고, 그 외 `IllegalStateException`(예: ES
   인프라 코드의 것)은 기존 범용 `Exception` 핸들러의 500 폴백으로 간다.

**원안과 달라진 점(범위 축소)**: single-flight 임베딩 캐시는 보류했다 — 실제 동시 중복 요청이
관측된 적이 없어 지금 넣는 건 추측성 최적화(YAGNI)라고 판단했다. 응답 DTO는 Builder 대신 값
비교 테스트로 대체했다 — `domain-model.md` §10이 Response DTO에는 `record`+`from()`만
명시하고 `@Builder`는 Command/Search Condition/Test Fixture에만 허용해, Builder 도입이 오히려
컨벤션 이탈이었다. `ProductInvalidStatusException`은 (인계 문서가 암시한 것과 달리) 6개 가드마다
별도 클래스를 만들지 않고 메시지만 다른 단일 클래스로 통일했다 — 전부 "현재 상태로는 이 동작을
할 수 없다"는 같은 모양의 규칙이라 클래스를 늘릴 이유가 없었다.

**`TempFilePromoter`(원래 4번 항목) 전체 제외** — Codex adversarial review 2회차에서 발견.
`promoteKeys`/`promoteKey`의 출력 파라미터 2개를 `PromotionRecord` 값객체 반환으로 바꾸는
구조 리팩터링 자체는 실제 버그를 고치는 게 아니라 가독성 목적이었다. 1회차 리뷰에서 "S3
copy 타임아웃 후 성공한 영구 객체가 보상 정리에서 누락된다"는 medium 지적을 받아 "copy 실패
시 목적지 permanent key를 방어적으로 delete"하는 코드를 추가했는데, 2회차 리뷰에서 이게 오히려
**high 등급 데이터 유실 버그**라는 게 드러났다 — 목적지 key는 `productId+purpose+파일명`으로
결정되는 고정 경로라, 이전 요청이 이미 성공해 DB가 참조 중인 파일에 대해 같은 요청이 재시도(예:
클라이언트가 타임아웃으로 실패했다고 오판해 재전송)되면 원본 temp가 이미 삭제된 상태라 copy가
실패하고, 이 방어적 delete가 **살아있는 정상 파일을 지워버린다.** 근본 원인을 고치려면 요청별
고유 경로로 복사하거나 "이번 시도가 실제로 만든 객체"만 증명 가능하게 추적해야 하는데, 이는
버그 하나 없던 파일에 리팩터링 목적만으로 들어가기엔 과한 범위라 판단해 `TempFilePromoter`와
그 테스트를 전부 원상 복구했다. I-10 항목표의 이 행은 향후 실제 필요(예: 관측된 고아 객체
누적)가 생기면 별도 이슈로 재검토한다.

**검증 결과**: `.\gradlew.bat :product-service:build --no-daemon` (checkstyle + 전체 테스트)
`BUILD SUCCESSFUL`, 482개 테스트 전부 통과(신규/수정 테스트 약 9개 포함 — DTO 필드 위치 안전성
2건, 검수 결과 실패 전파 1건, consumer DLT 전파 1건, `ProductInvalidStatusException` 409 매핑
1건, `Product` 도메인 6개 가드 예외 타입 갱신). `git diff --check` 통과. API URL·요청/응답
필드·gRPC·Kafka payload 계약을 하나도 바꾸지 않아 FE 영향이 없음을 확인했고, 이번 PR만으로는
FE lint/build를 실행하지 않았다.

**Quality**: 이번 세션에서 CodeFlow/Quality 재측정은 수행하지 않았다 — 근거 없는 점수를 기록하지
않기 위해 구현 결과와 테스트 통과 사실만 남긴다.

commit·push·PR은 아직 하지 않았다.
