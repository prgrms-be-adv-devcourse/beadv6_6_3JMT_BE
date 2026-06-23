# API Gateway

Spring Cloud Gateway (reactive / WebFlux 기반). 모든 외부 요청의 진입점.

- **포트**: 8000
- **기동 순서**: Discovery 다음 (세 번째)

---

## 핵심 파일

| 파일 | 역할 |
|------|------|
| `src/main/resources/application.yml` | 라우트 규칙, Eureka 연동 설정 |
| `GlobalFilter` 구현체 | 인증 결과(JwtAuthenticationToken)에서 claim 추출 → X-User-Id, X-User-Role 헤더 주입 (서명 검증은 Resource Server가 수행) |

---

## 동작 방식

1. 요청 수신 → JWT 검증 필터 실행
2. 공개 경로(`/auth/signup`, `/auth/login` 등)는 검증 우회
3. 검증 성공 → 토큰에서 userId, role 추출 → 헤더에 주입
4. `lb://USER-SERVICE` 형태로 Eureka에서 인스턴스 조회 후 라우팅

---

## 개발 규칙

- **WebMVC 의존성 추가 금지**: WebFlux 기반이므로 `spring-boot-starter-web`과 충돌한다.
- **JWT 검증 필터**: `GlobalFilter`와 `Ordered`를 함께 구현한다.
- **화이트리스트**: 인증이 불필요한 경로는 `SecurityConfig`의 `WHITE_LIST`에서 관리한다.
- **헤더 이름 고정**: 다운스트림 서비스와의 계약이므로 임의 변경 금지.
  - `X-User-Id`: 사용자 UUID
  - `X-User-Role`: 사용자 권한 (BUYER / SELLER / ADMIN)

---

## 참고

Spring Cloud 전체 흐름은 `docs/architecture/spring-cloud.md` 참조.
