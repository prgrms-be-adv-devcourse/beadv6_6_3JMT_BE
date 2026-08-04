# Admin Settlement Main — Codex 지침

## 적용 범위

- 이 문서는 `admin-service/src/main/java/com/prompthub/admin/settlement/**`에만 적용한다.
- 저장소 루트 `AGENTS.md`를 먼저 적용하고 이 문서의 Admin 정산 규칙을 추가한다.
- Admin의 다른 도메인이나 패키지로 작업 범위를 자동 확장하지 않는다.

## 3-tier 경계

- `controller`는 요청과 Gateway 전달 헤더를 검증하고 DTO를 변환한 뒤 `service`에 위임한다.
- `service`는 트랜잭션, 조회 조합, 지급·취소·재시도 유스케이스와 협력 객체 호출을 조정한다.
- `entity`는 가능한 상태 전이와 불변식을 스스로 검증한다.
- `repository`는 영속화와 조회·집계만 담당하며 비즈니스 상태 전이를 구현하지 않는다.
- Controller에서 Repository를 직접 호출하지 않는다. Service가 현재 패키지 관례에 따라 응답 DTO를 반환할 때도 HTTP 상태나 헤더 같은 Web 책임은 Controller에 남긴다.

## 인증과 서비스 경계

- JWT 검증은 Gateway 책임이다. 이 패키지는 `X-User-Id` 등 실제 전달 헤더의 형식과 해당 관리자 유스케이스에 필요한 권한 경계를 검증한다.
- 다른 서비스의 데이터베이스나 내부 코드에 직접 의존하지 않고 공개 API, 이벤트와 공유 계약을 사용한다.
- Kubernetes 재전송 Job Client는 infrastructure 경계에 두고 Service는 인터페이스를 통해 호출한다.

## 변경 동기화

- API 요청·응답, 표시 상태, 지급·취소 전이, 합계·집계, 전달 상태 또는 재전송 Job 규칙을 바꾸면 대응 테스트를 함께 수정한다.
- Query Repository 변경은 필터, 정렬, 기간 경계와 상태별 집계를 검증한다.
- 예외는 유스케이스 실패 원인을 보존하고 전역 오류 응답 계약과 일치시킨다.

## 검증

- 먼저 영향받은 테스트 클래스를 실행한다. 예: `./gradlew :admin-service:test --tests "com.prompthub.admin.settlement.service.SettlementServiceTest"`.
- 패키지 변경을 마치면 `./gradlew :admin-service:test`를 실행한다.
- 외부 Kubernetes 환경이 필요한 검증을 실행하지 못하면 테스트 대역으로 확인한 범위와 남은 검증을 구분해 보고한다.
