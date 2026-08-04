# User Service — Codex 지침

## 적용 범위

- 이 문서는 `user-service/**`에 적용한다.
- 저장소 루트 `AGENTS.md`를 먼저 적용하고 이 문서의 사용자 모듈 규칙을 추가한다.
- 이 문서는 다른 서비스나 패키지에 대한 수정 권한을 부여하지 않는다.

## 도메인과 아키텍처

- `auth`, `user`, `seller`, `sellersettlement`, `wishlist`의 도메인 경계를 유지한다.
- 각 도메인의 `domain`은 핵심 규칙과 모델을 소유하며 presentation, application, infrastructure 구현에 의존하지 않는다. 기존 엔티티의 JPA 매핑은 유지하되 Web이나 외부 Client 책임을 도메인 모델에 넣지 않는다.
- `application`은 유스케이스와 port를 정의하고 트랜잭션 단위의 흐름을 조정한다.
- `infrastructure`와 `presentation`은 바깥 계층에서 application과 domain을 연결한다.
- 한 도메인의 내부 구현을 다른 도메인이 직접 사용하기보다 공개된 유스케이스나 계약을 사용한다.

## 인증과 서비스 경계

- 로그인, 토큰 발급·재발급·폐기와 사용자 인가 데이터는 `auth` 경계의 책임으로 유지한다.
- Gateway를 거쳐 들어오는 사용자 API는 검증된 `X-User-Id` 등 전달 헤더를 사용하며 JWT 검증 책임을 중복 구현하지 않는다.
- 다른 서비스의 데이터베이스, 엔티티, Repository에 직접 의존하지 않는다. 서비스 간 통신은 gRPC, 이벤트 또는 공개 API 계약을 사용한다.
- `grpc/user/`, `common-module/`, API·이벤트 문서를 바꾸면 생산자·소비자 호환성을 함께 확인한다.

## API와 문서 동기화

- Controller는 요청·헤더 검증과 DTO 변환 뒤 application 유스케이스에 위임한다.
- API DTO, 에러 응답, 인증 흐름, 판매자 정산 계약을 바꾸면 대응 테스트와 OpenAPI·API 명세를 함께 확인한다.
- 사용자 식별자와 판매자 식별자의 의미를 혼용하지 않고 메서드·DTO 이름으로 드러낸다.

## 검증과 공용 스킬

- 먼저 영향받은 테스트 클래스를 실행하고, 모듈 변경을 마치면 `./gradlew :user-service:test`를 실행한다.
- gRPC나 공용 계약을 바꾸면 직접 영향을 받는 소비 모듈 테스트도 실행한다.
- 커밋, 브랜치, GitHub 이슈, PR, 전체 diff 검증은 루트 `AGENTS.md`가 라우팅하는 공용 스킬을 사용한다.
- 실행하지 못한 검증은 완료로 표현하지 않고 이유와 남은 위험을 명시한다.
