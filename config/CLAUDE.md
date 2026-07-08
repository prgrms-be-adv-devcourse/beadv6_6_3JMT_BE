# Config Server

Spring Cloud Config Server. **native(classpath) 백엔드**로 모든 서비스의 설정 파일을
중앙에서 관리한다. git 백엔드·브랜치 라벨·`GIT_TOKEN`은 사용하지 않는다
(`docs/adr/0004-centralized-config.md` 2차 개정 — 팀 결정으로 git 백엔드 폐기).

- **포트**: 8888
- **기동 순서**: 가장 먼저 (다른 서비스들이 이 서버에서 설정을 읽어옴)
- **상세 규칙·마이그레이션 절차**: `docs/adr/config-management.md` (이 문서가 원본,
  아래는 요약)

---

## 핵심 파일

| 파일 | 역할 |
|------|------|
| `src/main/resources/application.yml` | Config Server 자체 설정 (포트, `native.search-locations: classpath:/configs/`) |
| `configs/application.yml` | 모든 서비스 공통 설정 (Eureka client, actuator 노출만 — 최소주의) |
| `configs/{service}.yml` | 서비스별 설정 (**현재 파일명 — 프로파일 접미사 없음**) |

> ⚠ **마이그레이션 미완료**: `docs/adr/config-management.md`가 정한 목표 파일명은
> `configs/{service}-dev.yml`(profile 명시)이지만, 아직 이 리네이밍과 짝을 이루는
> `docker-compose.yml`의 `SPRING_PROFILES_ACTIVE=dev` 주입이 어느 서비스에도 돼 있지 않다.
> **두 변경은 반드시 같은 PR에서 동시에 이뤄져야 한다** — 파일명만 먼저 바꾸면 config
> server가 `default` 프로파일 요청에 그 파일을 더 이상 매칭하지 못해 개발서버가 즉시
> 깨진다. `docs/adr/config-management.md` §9 마이그레이션 순서 참고.

---

## 설정 파일 관리 규칙

- **"커밋 ≠ 반영"**: `configs/`는 config server 이미지에 구워진다. develop 머지 → CD가
  config 모듈 재빌드·재배포 → 값을 쓰는 서비스 재시작까지 거쳐야 실제로 반영된다.
- 환경별로 달라지는 값(DB 접속 정보, 포트, API 키 등)은 `configs/{service}.yml`에서 관리한다
  (목표 상태에서는 `{service}-dev.yml` — 위 마이그레이션 미완료 참고).
- 민감한 값(비밀번호, 시크릿 키)은 절대 커밋하지 않는다 — `${ENV_VAR}` 플레이스홀더만 두고
  실제 값은 각 서비스 프로세스가 환경변수에서 해석한다.
- 파일명 규칙이 계약이다: config server는 `{spring.application.name}-{profile}.yml`로
  파일을 찾고, 오타 난 파일명은 **에러 없이 무시**한다. 허용 패턴은
  `application(-dev)?.yml` 또는 `{실존 서비스명}-dev.yml`뿐.
- `configs/`는 config 모듈 안에 있지만 **전원 공유 영역**이다 — 각 서비스 담당자가 자기
  `{service}-dev.yml`을 직접 수정한다("다른 모듈을 수정하지 않는다" 관례의 명시적 예외,
  `configs/` 디렉토리 한정).
- **머지 전 로컬 확인**: config 모듈만 `bootRun`하면 로컬 classpath의 `configs/`를 그대로
  서빙하므로, `curl localhost:8888/{service}/dev`로 커밋 없이 병합 결과를 확인할 수 있다.

---

## 참고

Spring Cloud 전체 흐름은 `docs/architecture/spring-cloud.md` 참조.
