# 정산 취소 시 원천 라인 해제

관리자가 정산을 취소하면, 거기 묶여 있던 원천 라인(source line)을 해제한다. 각 라인을 불러와
도메인 메서드로 `settlementId`를 비우면, JPA dirty checking이 행마다 `UPDATE` 하나씩을 날린다.
단일 벌크 `UPDATE`가 아니다.

> **현황(이관됨):** 이 취소 흐름(정산 취소 → 원천 라인 해제)은 settlement 본체에서 **admin-service
> 로 이관**됐다(#234). 정산 본체(settlement-service)엔 도메인 메서드 `Settlement.cancel()`·
> `SettlementSourceLine.release()` 가 남아 있으나 이를 호출하는 애플리케이션 서비스가 없어 **현재
> 실행 경로가 없다(고아 코드).** 어드민 정산 취소는 admin-service `settlement` 패키지가 수행한다 —
> `Settlement`(→`seller_settlement`) 를 CANCELLED 로 전이하고 `SettlementSourceLine`(→`settlement_source_line`)
> 을 해제한다. 즉 운영 상태는 유저 DB, 원천 라인 해제는 정산 DB 다(모듈 분리 설계와 일치).
> 아래는 이 흐름이 정산 본체에 살아 있던 시점의 코드 기준이며, 트레이드오프(행 단위 dirty checking
> vs 벌크 UPDATE)는 취소가 실제로 도는 곳(admin-service) 기준의 설계 근거로 보존한다. 세부 구현
> 방식(dirty checking/벌크)이 admin 코드에서 정확히 어느 쪽인지는 admin-service 기준으로 확인한다.
> (어드민 데이터 접근 배경: `admin-data-access.md`, 모듈 분리: `../architecture/admin-module-separation.md`)

## 원래 구조 (정산 본체 기준 — 현재 admin-service 로 이관)

정산 본체에 취소가 살아 있던 시점, `SettlementApplicationService.cancel()`은 하나의
`@Transactional` 안에서 돌았다. (현재 이 서비스는 정산 본체에 없고 admin-service 에 있다.)

```java
@Override
@Transactional
public SettlementResponse cancel(UUID settlementId) {
    Settlement settlement = findSettlement(settlementId);
    settlement.cancel(LocalDateTime.now());          // status -> CANCELLED (soft)

    List<SettlementSourceLine> lines = settlementSourceRepository.findBySettlementId(settlementId);
    lines.forEach(line -> line.release(settlementId));   // clears the FK in memory

    settlementRepository.save(settlement);
    return SettlementResponse.from(settlement);
}
```

실제 해제는 원천 라인의 도메인 메서드다.

```java
public void release(UUID settlementId) {
    if (this.settlementId != null && this.settlementId.equals(settlementId)) {
        this.settlementId = null;   // only releases lines bound to *this* settlement
    }
}
```

그래서 흐름은 이렇다.

- `findBySettlementId`는 **관리(영속) 상태 엔티티**를 반환한다 — 취소된 정산에 묶인 라인만.
- `release()`는 메모리에서 필드를 바꾼다. 라인마다 **명시적 `save`가 없다.** 엔티티가 관리
  상태라 필요 없다.
- 커밋(flush) 시점에 **dirty checking이 바뀐 라인마다 `UPDATE ... WHERE id = ?` 하나씩**을 낸다.
- 결과: **1 SELECT + N UPDATE**, N은 그 정산에 묶인 라인 수다.

해제된 라인은 `settlement_id = NULL`이 되는데, 이게 바로 재정산 reader가 찾는 조건이다
(`findSettleableLines`가 `settlementId is null`로 거른다). 그래서 다음 실행의 후보 집합으로 다시
떨어진다. `Settlement` 행도 원천 라인 행도 삭제되지 않는다.

## 무엇을 견주는가

선택은 **행 단위 dirty checking(현재)** 대 **단일 벌크 `UPDATE`**다.

```java
@Modifying(clearAutomatically = true, flushAutomatically = true)
@Query("update SettlementSourceLine l set l.settlementId = null where l.settlementId = :settlementId")
int releaseAllBySettlementId(@Param("settlementId") UUID settlementId);
```

벌크는 N번의 UPDATE를 한 문장으로 바꾸고, N개 엔티티를 영속성 컨텍스트에 올리는 일을 건너뛴다.
하지만 벌크 업데이트는 DB로 곧장 가면서, 지금 방식이 공짜로 얻는 세 가지를 우회한다.

- **감사(`updatedAt`).** `SettlementSourceLine extends BaseEntity`라 `AuditingEntityListener`를
  통해 `@LastModifiedDate updatedAt`을 갖는다. 벌크 업데이트는 리스너를 **건드리지 않으므로**
  `updated_at`이 옛 값으로 남는다. 유지하려면 쿼리에서 직접 set해야 하고
  (`set l.settlementId = null, l.updatedAt = :now`) 메서드로 타임스탬프를 넘겨야 한다.
- **영속성 컨텍스트 정합성.** 벌크 업데이트는 1차 캐시를 건드리지 않는다. 같은 트랜잭션에서
  이미 불러온(또는 나중에 읽는) 라인은 여전히 **옛** `settlementId`를 보인다.
  `clearAutomatically = true`가 이걸 고치지만 컨텍스트 *전체*를 비우므로, `settlement.cancel()`
  변경이 먼저 flush돼야 하고(`flushAutomatically = true`) 그렇지 않으면 잃을 수 있다. 순서를
  따져야 하는 일이 생긴다.
- **도메인 검증.** 지금은 해제가 `release()`를 거치는데, 그 `equals` 가드가 "이 정산에 묶인
  라인만 해제한다"는 규칙을 **도메인 모델 안에** 두고 단위 테스트 가능하게 한다. 벌크는 그
  규칙을 인프라 계층의 JPQL 문자열로 옮긴다 — 결과는 같지만, 비즈니스 규칙이 모델 밖으로 새고
  이제 `@DataJpaTest`로 덮어야 한다.

벌크가 여기서 **건드리지 않는 것**: 엔티티에 **`@Version`이 없으므로** 우회할 낙관적 락 경로가
없다. 그리고 재정산 동작은 어느 쪽이든 같다. 둘 다 `settlement_id = NULL`로 끝나기 때문이다.

## 선택지

| 선택지 | 장점 | 단점 |
| --- | --- | --- |
| 행 단위 dirty checking (선택) | `updatedAt` 감사가 공짜다, 영속성 컨텍스트가 정합하게 유지된다, 해제 규칙이 도메인에 살고 단위 테스트가 된다 | 1 SELECT + N UPDATE, N개 엔티티를 메모리에 올린다 |
| 벌크 `UPDATE` | 한 문장이다, 엔티티를 안 올린다, flush가 가장 가볍다 | 감사를 건너뛴다(`updatedAt`을 손으로 set해야 한다), `clear`/`flush` 순서를 챙겨야 한다, 해제 규칙이 JPQL 문자열로 샌다, 통합 테스트가 필요하다 |

## 언제 벌크로 바꾸나

지금은 정산 하나에 묶이는 원천 라인이 많지 않다(한 판매자의 한 기간 PAID/REFUND 이벤트). 그래서
N이 작고 행 단위 비용은 무시할 만한 반면, 감사·정합성·도메인 검증 이득은 실재한다. 다음 중 하나가
사실이 되기 전까지는 지금 방식을 유지한다.

- **N이 커진다.** 정산 하나가 일상적으로 **수백~수천** 원천 라인을 묶는다(예: 거래량 많은
  판매자, 더 긴 정산 기간). N번의 왕복이나 flush 비용이 취소 지연으로 드러난다.
- **취소가 무더기로 일어난다.** 관리자가 정산을 한꺼번에 많이 취소하기 시작한다(대량 재정산,
  잘못된 배치 재처리). N UPDATE가 여러 정산에 걸쳐 곱절로 는다.
- **flush·메모리 비용이 추측이 아니라 측정된다.** 라인 로딩과 행 단위 업데이트가 진짜 병목이라는
  프로파일링 증거가 있다 — 짐작이 아니라.

바꿀 때 마이그레이션은 반드시 이렇게 한다.

1. JPQL에서 `updatedAt`을 set하고(`set l.settlementId = null, l.updatedAt = :now`) 타임스탬프를
   넘겨, 감사가 조용히 빠지지 않게 한다.
2. `@Modifying(clearAutomatically = true, flushAutomatically = true)`를 쓰고, 컨텍스트가
   비워지기 전에 `settlement.cancel()` 변경이 flush됐는지 확인한다.
3. "이 정산에 묶인 라인만 해제한다"는 규칙을 다시 자리 잡게 한다 — JPQL `where settlementId
   = :id`가 강제하지만, 잃어버린 `release()` 단위 테스트를 대신할 `@DataJpaTest`로 덮는다.

## 지금 우리에게 맞는 이유

- 취소는 작은 라인 집합을 대상으로 한 저빈도 관리자 작업이라, N-update 비용은 드물게 치르고
  호출당 아주 작다.
- 해제를 `release()`에 두면 규칙이 단위 테스트 딸린 도메인 메서드 하나다. 쿼리 문자열에 통합
  테스트가 아니라 — 올바르게 유지하기가 더 싸다.
- `updatedAt`과 1차 캐시 정합성이 자동으로 따라온다. 벌크라면 둘 다 손으로 다시 벌어야 하고,
  미묘하게 틀리기 쉽다.
- 위 전환은 나중에 하기 싸고 트리거가 구체적이다(라인 수 / 무더기 취소). 그러니 숫자가 실제로
  요구할 때까지 미뤄도 손해가 없다.
