# ADR-0012: admin-service 도메인 순수 예외 핸들러는 도메인 패키지 안에 분리한다

- 상태: accepted
- 날짜: 2026-08-06
- 관련: admin-service settlement 도메인, `GlobalExceptionHandler`, ADR-0010(3-tier 단순화)

## 컨텍스트

정적 분석에서 admin-service에 순환 의존성 1건(CRITICAL)이 발견됐다.
`global.exception.GlobalExceptionHandler`가 `settlement.exception`의 도메인 순수 예외 3종
(`SettlementInvalidStateException`, `SettlementAlreadyPaidException`,
`SettlementAlreadyCancelledException`)을 타입별로 잡느라 import하고(`global → settlement`),
반대로 `SettlementService`/`Settlement` 엔티티는 `global.exception.AdminErrorCode`·
`AdminException`, `global.common.BaseEntity`를 import한다(`settlement → global`). 두 방향이
겹쳐 패키지 순환이 생겼다.

ADR-0010에서 admin-service를 클린아키텍처 4계층에서 도메인 단위 평면 3-tier 구조
(`{domain}/{controller,service,entity,repository,exception,...}`)로 전환한 이후 생긴
구조다. 다른 4개 서비스(user/payment/product/settlement-service)는 `domain`이 다른 계층을
참조하지 않는 방향 규칙이 있어 이 순환이 애초에 성립하지 않는다. admin-service만 "도메인
패키지가 서비스·엔티티·예외를 다 포함 + 단일 `GlobalExceptionHandler`가 여러 도메인의 순수
예외를 타입별로 잡는" 조합이라 이 문제가 생긴다. order/product/user 도메인도 나중에 순수
예외를 도입하면 같은 패턴이 재발할 수 있다.

## 결정

1. `GlobalExceptionHandler`(`global/exception`)는 `BusinessException` 제네릭 처리, 프레임워크
   검증 예외(`MethodArgumentNotValidException` 등), 최종 `Exception` 폴백만 전담한다. 어떤
   도메인 패키지도 import하지 않는다.
2. 도메인 순수 예외(`RuntimeException`을 직접 상속하는 타입)를 타입별로 잡아야 하는 도메인은,
   그 매핑을 `global`이 아니라 **자기 패키지 안에 별도 `@RestControllerAdvice`**로 둔다. 이름은
   `<Domain>ExceptionHandler`, 위치는 해당 도메인의 `exception` 패키지(예:
   `settlement.exception.SettlementExceptionHandler`).
   - **주의(구현 중 발견)**: Spring은 여러 `@ControllerAdvice` 빈이 있어도 "전체에서 가장
     구체적인 핸들러"를 찾지 않는다. `ExceptionHandlerExceptionResolver`는 advice 빈을
     순회하다 **해당 예외를 처리할 수 있는 메서드가 하나라도 있는 첫 번째 빈에서 멈춘다.**
     그래서 `GlobalExceptionHandler`의 `@ExceptionHandler(Exception.class)` 폴백이 먼저
     평가되면, `SettlementExceptionHandler`의 더 구체적인 핸들러는 아예 호출되지 않고
     정산 도메인 예외가 500으로 떨어진다(실제로 테스트에서 재현됨 — 409 기대, 500 발생).
     따라서 `GlobalExceptionHandler`에 `@Order(Ordered.LOWEST_PRECEDENCE)`를,
     `SettlementExceptionHandler`(및 이후 추가될 `<Domain>ExceptionHandler`)에
     `@Order(0)`을 명시해 도메인 핸들러가 항상 먼저 평가되게 한다. 새 `<Domain>ExceptionHandler`를
     추가할 때는 이 `@Order` 지정을 빠뜨리지 않아야 한다.
3. `AdminErrorCode` enum은 쪼개지 않고 admin-service 전역 공유로 유지한다. "코드·메시지·상태는
   한 곳에서 관리"(`controller-exception.md` §2-2) 규칙은 핸들러 클래스 개수가 아니라 enum
   얘기라 그대로 지켜진다.
4. 도메인 예외가 공통 마커 인터페이스를 구현해 `GlobalExceptionHandler`가 구체 타입 없이 잡는
   방식은 채택하지 않는다. 마커 인터페이스가 매핑에 쓸 정보(에러코드 등)를 들고 있으려면
   도메인이 그 정보를 알아야 하는데, 이는 `domain-model.md` §8 "도메인은 `ErrorCode`·
   `HttpStatus`를 모른다" 규칙과 충돌한다.
5. 공용 룰 문서(`.claude/rules/controller-exception.md`)는 수정하지 않는다. 이 패턴은
   admin-service처럼 도메인이 계층이 아니라 애그리거트 단위 평면 구조를 쓰는 서비스에서만
   필요하고, 다른 4개 서비스는 겪지 않는 문제라 서비스 전역 룰 변경까지는 필요 없다고
   판단했다. 배경은 이 문서로만 남긴다.
6. 재발 방지용 회귀 테스트를 추가한다: `GlobalExceptionHandler`의 `@ExceptionHandler` 파라미터
   타입 중 도메인 패키지(`.settlement.`, `.order.`, `.product.`, `.user.` 등) 소속이 하나라도
   있으면 실패하는 리플렉션 기반 단위 테스트. ArchUnit 등 새 의존성은 추가하지 않는다.

## 결과

- 정산 도메인 순수 예외 3종의 ErrorCode 매핑은 `SettlementExceptionHandler`로 이동하고,
  `GlobalExceptionHandler`에서는 제거된다.
- order/product/user 도메인이 나중에 자기 도메인 순수 예외를 타입별로 잡아야 하는 상황이
  오면, 같은 방식으로 `<Domain>ExceptionHandler`를 그 도메인 패키지 안에 만든다. `global`에
  추가하지 않는다. 이때 `@Order(0)`(또는 `GlobalExceptionHandler`의
  `Ordered.LOWEST_PRECEDENCE`보다 낮은 값) 지정을 빠뜨리면 `GlobalExceptionHandler`의
  `Exception.class` 폴백이 먼저 매칭돼 새 핸들러가 조용히 무시된다.
- `global`은 순수하게 횡단 관심사(`BusinessException`, 프레임워크 예외, 폴백)만 남아, 어떤
  도메인 패키지도 참조하지 않는 상태가 유지된다.
- 4계층 구조를 쓰는 다른 서비스에는 이 ADR이 적용되지 않는다 — 그쪽은 애초에 이 순환이
  성립하지 않는 구조다.
