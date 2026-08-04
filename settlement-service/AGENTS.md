# Settlement Service — Codex 지침

## 적용 범위

- 이 문서는 `settlement-service/**`에 적용한다.
- 저장소 루트 `AGENTS.md`를 먼저 적용하고 이 문서의 정산 모듈 규칙을 추가한다.
- 다른 서비스의 내부 코드나 데이터베이스에 직접 의존하지 않는다. 서비스 간 연동은 공개 API, Kafka 이벤트, gRPC와 공유 계약으로 처리한다.

## 아키텍처와 의존 방향

- `domain`은 정산 불변식과 상태 전이를 소유하며 presentation, application, infrastructure 구현에 의존하지 않는다. 기존 엔티티의 JPA 매핑은 유지하되 Repository나 외부 Client 책임을 도메인 모델에 넣지 않는다.
- `application`은 유스케이스와 port를 정의하고 도메인 동작을 조정한다.
- `infrastructure`는 영속성, Batch, 외부 Client, 시간, 트랜잭션 port의 adapter를 제공한다.
- `presentation`은 요청·헤더 검증과 DTO 변환 뒤 application 유스케이스에 위임한다.
- 의존은 바깥 계층에서 안쪽 계층으로 향하게 하고 domain이나 application에서 infrastructure 구현을 직접 참조하지 않는다.

## 서비스 경계와 계약

- 주문·사용자 서비스 연동은 `grpc/`, `common-module/`, `docs/api-spec/`와 관련 이벤트 문서의 공유 계약을 기준으로 한다.
- 다른 서비스의 내부 엔티티나 Repository를 가져오거나 다른 서비스 스키마를 직접 조회하지 않는다.
- 공유 계약을 바꾸면 생산자·소비자와 호환성, 관련 문서와 계약 테스트의 영향을 함께 확인한다.
- Gateway가 JWT를 검증하고 사용자 헤더를 주입한다. 정산 서비스는 JWT를 다시 해석하지 않고 전달받은 헤더의 형식과 유스케이스 권한을 경계에서 검증한다.

## 정산 규칙과 변경 동기화

- 금액 계산에는 `BigDecimal`을 사용하고 scale·반올림·부호 규칙을 테스트로 고정한다.
- 상태 전이, 재시도, 멱등성, 중복 방지와 Batch 재시작 규칙은 도메인 또는 application 계층에서 명시적으로 검증한다.
- API 요청·응답, 예외, 상태, 금액, 이벤트 또는 gRPC 계약을 바꾸면 대응 테스트와 관련 명세를 함께 확인한다.
- Controller는 도메인 규칙을 직접 구현하지 않고 application 유스케이스 결과를 API 응답으로 변환한다.

## 테스트 우선 스킬

정산 계산, 상태 전이, 중복, 권한, 예외, 금액 규칙의 기능 구현이나 버그 수정에는 `test-settlement-first`를 사용한다.

- 스킬 위치: `settlement-service/.agents/skills/test-settlement-first/SKILL.md`
- 스킬의 RED→GREEN 절차를 구현 코드보다 먼저 적용한다.
- 스킬 본문을 이 문서에 복사하지 않는다.

## 검증

- 먼저 영향받은 테스트 클래스를 실행한다. 예: `./gradlew :settlement-service:test --tests "com.prompthub.settlement.application.service.SettlementCalculationApplicationServiceTest"`.
- 구현 또는 테스트 변경을 마치면 `./gradlew :settlement-service:test`를 실행한다.
- gRPC나 공용 계약을 바꾸면 직접 영향을 받는 생산자·소비자 모듈 테스트도 실행한다.
- 실행하지 못한 통합 테스트나 외부 환경 검증은 완료로 간주하지 않고 남은 위험을 명시한다.
