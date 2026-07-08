# 보안 컨벤션

PR에 시크릿·민감정보가 새어 들어가는 것을 막는 규칙을 정의한다. 이 룰은 `rule-checker` 가
verify-rules 게이트에서 검증하며, 위반이 있으면 PR 생성을 막는다.

> 관련 문서: 민감정보 노출 금지의 표현 계층 관점은 `controller-exception.md` §2-3 참고.

## 0. 검사 방법 (검사기 지침)

이 룰은 변경 파일 목록뿐 아니라 **이번 브랜치가 추가한 diff 라인**과 `.gitignore` 내용을 본다.
검사기(`rule-checker`)는 INPUT 파일 목록을 통째로 읽는 대신, 아래를 직접 수행한다.

```bash
git diff <base>...HEAD --name-only    # §1: 추가/변경된 파일 경로
git diff <base>...HEAD                 # §2·§3: 추가 라인('+'로 시작하는 줄)만 본다
cat .gitignore                         # §4: 무시 패턴 확인
```

- `<base>` 는 작업 브랜치(`feat/*`·`fix/*` 등)면 `develop` 을 쓴다(verify-rules 와 동일 기준).
- §2·§3 은 **`+` 로 시작하는 추가 라인만** 검사한다. 기존 코드에 있던 값으로 PR 을 막지 않는다.

## 1. 시크릿 파일 커밋 금지

다음 패턴의 파일이 이번 변경으로 **추가**되면 위반(FAIL)이다.

- `.env`, `.env.*`
- `*.pem`, `*.key`, `*.p12`, `*.keystore`, `*.jks`
- `id_rsa`, `id_dsa`, `*.ppk`
- 파일명에 `credential` / `secret` 이 들어간 `*.yml` · `*.yaml` · `*.json` · `*.properties`

판단은 `git diff <base>...HEAD --name-only` 의 경로로 한다.

## 2. 코드 내 하드코딩 시크릿 금지

diff 추가 라인에서 다음이 코드에 평문으로 박히면 위반이다.

- API 키 / 액세스 토큰 / 시크릿 키 (예: `AKIA...` AWS 액세스 키, `sk-...` 류)
- 비밀번호 리터럴 (예: `password = "p@ssw0rd"`)
- `Authorization: Bearer <긴 토큰>` 형태의 하드코딩 토큰
- `-----BEGIN ... PRIVATE KEY-----` 블록

## 3. 민감정보 로깅·노출 금지

diff 추가 라인에서 다음이 보이면 위반이다.

- 비밀번호·토큰·주민등록번호·카드번호 등을 `log.*(...)` 로 출력
- 위 정보나 시스템 내부 상세(스택 트레이스·SQL·드라이버 메시지)를 예외 메시지·HTTP 응답으로
  그대로 클라이언트에 노출 (`controller-exception.md` §2-3 과 연결)

## 4. .gitignore 누락 점검

`.gitignore` 에 아래 민감 파일 패턴이 빠져 있으면 위반이다. (지금 커밋되지 않았더라도 예방 차원)

- `.env` (또는 `.env.*`)
- `*.pem` · `*.key` (개인 키 류)

## 5. 예외 — 로컬 설정값은 위반이 아니다

`application*.yml` · `application*.properties` 의 **로컬·개발용 설정값은 위반이 아니다.**

- `localhost`, `127.0.0.1`, 로컬 DB URL/계정, 포트 번호, 개발용 더미 값 등
- 위반은 **실제 운영 시크릿**(외부 서비스 API 키, 운영 DB 비밀번호 등)이 평문으로 박힌 경우만이다.

설정 키 이름에 `password`·`secret` 이 있어도, 값이 로컬/개발용이거나 환경변수 치환
(`${DB_PASSWORD}`)이면 위반이 아니다.
