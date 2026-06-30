# Config Server

Spring Cloud Config Server. 모든 서비스의 설정 파일을 중앙에서 관리한다.

- **포트**: 8888
- **기동 순서**: 가장 먼저 (다른 서비스들이 이 서버에서 설정을 읽어옴)

---

## 핵심 파일

| 파일 | 역할 |
|------|------|
| `src/main/resources/application.yml` | Config Server 자체 설정 (포트, 설정 파일 경로) |
| `configs/application.yml` | 모든 서비스 공통 설정 |
| `configs/user-service.yml` | user-service 전용 설정 |

---

## 설정 파일 관리 규칙

- 환경별로 달라지는 값(DB 접속 정보, 포트, API 키 등)은 `configs/`에서 관리한다.
- 민감한 값(비밀번호, 시크릿 키)은 환경변수로 주입하고 `${ENV_VAR}` 형태로 참조한다.
- 서비스 공통 설정은 `configs/application.yml`, 서비스 전용 설정은 `configs/{서비스명}.yml`에 둔다.

---

## 참고

Spring Cloud 전체 흐름은 `docs/architecture/spring-cloud.md` 참조.
