# Admin Settlement Test — Codex 지침

## 적용 범위

- 이 문서는 `admin-service/src/test/java/com/prompthub/admin/settlement/**`에만 적용한다.
- 저장소 루트 지침과 대응 main 패키지의 `AGENTS.md`를 함께 적용한다.
- Admin의 다른 테스트 패키지로 작업 범위를 자동 확장하지 않는다.

## 계층별 검증

- Controller 테스트는 요청·헤더 검증, HTTP 상태와 응답 매핑, Service 위임을 검증한다.
- Service 테스트는 상태 전이, 합계, 예외, 트랜잭션 경계와 Repository·외부 Client 협력 호출을 검증한다.
- Entity 테스트는 허용·금지 상태 전이와 불변식을 검증한다.
- Repository 테스트는 쿼리 필터, 기간 경계, 정렬, 상태별 집계와 스키마 매핑을 검증한다.
- Kubernetes adapter 테스트는 Job 이름·환경 변수·중복 Job·API 실패 변환을 실제 클러스터 없이 검증한다.

## 테스트 데이터와 회귀

- Repository 테스트는 현재 테스트 설정의 H2를 사용하고 외부 PostgreSQL에 의존하지 않는다.
- 테스트는 실행 순서에 의존하지 않게 데이터를 직접 준비하고 격리한다.
- 버그 수정은 재현 테스트가 기존 구현에서 실패하는지 먼저 확인하고 최소 변경으로 통과시킨다.
- 금액은 필요하면 `isEqualByComparingTo`로 비교하고 시간·기간 경계는 명시적인 값으로 고정한다.

## 실행 순서

- 먼저 영향받은 테스트 클래스를 실행한다. 예: `./gradlew :admin-service:test --tests "com.prompthub.admin.settlement.service.SettlementServiceTest"`.
- 해당 테스트가 통과하면 `./gradlew :admin-service:test`로 Admin 모듈 회귀를 확인한다.
- 실행하지 못한 외부 환경 검증은 성공으로 표현하지 않고 남은 위험을 명시한다.
