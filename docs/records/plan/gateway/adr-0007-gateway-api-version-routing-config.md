# ADR-0007: 서비스별 API 버전 라우팅을 게이트웨이 로컬 설정의 "버전 리스트"로 관리한다

- 상태: accepted
- 날짜: 2026-07-09
- 관련: `apigateway/src/main/resources/application.yml`, `apigateway/src/main/java/com/prompthub/apigateway/config/SecurityConfig.java`, 티켓 "[Gateway] 서비스별 API 버전 라우팅 Config화 (v1/v2 병행 지원)"

## 컨텍스트

`/api/v1`이 두 곳에 하드코딩돼 있다 — 라우트 predicate(`application.yml`)와 인증
화이트리스트(`SecurityConfig.WHITE_LIST`, 그리고 별도의 `GET /api/v1/products` permitAll).
티켓의 완료 기준은 "config 값만 바꿔서 v1/v2 **전환 또는 병행** 가능"이며, 병행(같은 서비스가
v1·v2를 동시에 서빙)이 실제로 필요하다고 확인됐다 — 어떤 서비스가 될지는 아직 모른다.

다운스트림 서비스(order/product/user-service 등) 전 컨트롤러가 `@RequestMapping("/api/v1/...")`로
고정돼 있고 v2 컨트롤러는 어디에도 없다. `docs/records/plan/infra/adr-config-management.md` §10도 "api/v1→v2 전환은
게이트웨이·컨트롤러·API 문서가 함께 움직여야 하고 별도 이슈로 다룬다"고 이미 명시하고 있어, 이
티켓은 **실제 v2 트래픽을 만드는 게 아니라 전환 메커니즘만 먼저 까는 것**으로 범위를 잡았다.

같은 문서 §6은 "라우트 정의는 Gateway 모듈에 유지, 라우트와 인증 화이트리스트는 한 PR·한 CI로
움직여야 한다"고 확정해뒀다. 또한 `settlement-service`(`/admin/settlements/batch/**`)와
`admin-service`(`/admin/settlements/**`)의 라우트 우선순위가 지금은 YAML 선언 순서에 암묵적으로
의존하고 있어, 라우트 생성 로직을 바꾸면 조용히 깨질 위험이 있다. 현재 `apigateway`에는
`contextLoads()` 스모크 테스트 하나뿐이라 회귀를 검증할 기존 테스트가 없다.

## 결정

1. **버전 매핑은 게이트웨이 모듈 로컬 설정에 둔다.** 중앙 Config Server(`configs/`)로 보내지
   않는다 — ADR-0004 §6("라우트는 Gateway 모듈에 유지")을 그대로 적용한 것뿐, 새로운 예외를
   만들지 않는다.
2. **서비스당 활성 버전은 리스트다**, 단일 값이 아니다. 예: `gateway.api-versions.order-service:
   [v1]`, 병행이 필요해지면 `[v1, v2]`로 바꾼다. 단일 값으로는 "전환"만 표현되고 "병행"을
   표현할 수 없다.
3. **비활성 버전 경로는 라우트가 없어 그냥 404다.** 게이트웨이가 v1↔v2를 조용히 rewrite하지
   않는다 — v2는 보통 v1과 실제 동작이 달라지는 게 목적이라, 게이트웨이가 임의로 우회시키면
   "v2가 열렸다"는 착각을 주면서 실제로는 v1 그대로 동작하는 상태가 된다.
4. **`SecurityConfig.WHITE_LIST`(및 `GET /api/v1/products` permitAll)도 같은
   `gateway.api-versions` 설정을 소스로 버전 프리픽스를 생성한다.** 경로 접미사(`/auth/signup`
   등)와 소속 서비스는 코드에 고정하되, 버전 프리픽스만 설정에서 읽어 조합한다. 이렇게 안 하면
   어떤 서비스가 병행 상태가 됐을 때 v2 로그인/가입 경로가 화이트리스트에 없어 인증을 요구하는
   self-lock 버그가 생기고, "config 값만 바꿔서 전환 가능"이라는 완료 기준도 깨진다.
5. **각 라우트에 명시적 `order:` 필드를 부여**해 `settlement-service` > `admin-service` 우선순위를
   YAML 선언 순서/Map 순회 순서와 무관하게 보존한다.
6. **위 동작(회귀·비활성 404·활성화·화이트리스트·우선순위)을 검증하는 자동 테스트를 이번 티켓에
   포함한다.**

## 결과

- 다른 서비스 담당자는 자기 서비스의 v2 컨트롤러가 준비되면 `gateway.api-versions.{service}`
  리스트에 `v2`를 추가하는 것만으로 전환/병행이 열린다 — 게이트웨이 코드 수정이 필요 없다.
- 라우트·화이트리스트 생성에 버전 리스트를 반영하는 로직(커스텀 predicate 또는 동등한 메커니즘)이
  필요해, 단순 문자열 치환보다 구현 범위가 커진다. "기존 라우팅 로직 리팩터링"이라는 티켓 성격과
  일치한다.
- `docs/adr/`는 작성 시점(2026-07-09)엔 팀 결정으로 git에 커밋하지 않는 로컬 전용 문서였다.
  이 ADR의 5번 결정처럼 **다른 서비스 담당자의 실제 작업에 영향을 주는 내용**은 당시엔 이
  파일만으로 전달되지 않아 티켓 설명이나 팀 공유 채널에 별도로 옮겨 적어야 했다. (이후
  `docs/adr/`는 전부 `docs/records/plan/{도메인}/adr-*.md`로 이동해 git에 커밋되므로, 이
  제약은 더 이상 유효하지 않다 — 이 문단은 작성 당시 상황을 남긴 기록이라 그대로 둔다.)

## 현황 업데이트 (2026-07-22)

작성 시점의 핵심 전제("v2 컨트롤러는 어디에도 없다, 이 티켓은 메커니즘만 까는 것")가
이후 뒤집혔다. 실제로는 **v1→v2 전환이 이미 전면 완료**됐다 — `grep` 기준 전 서비스
컨트롤러의 `/api/v1` 매핑은 0개, `/api/v2`는 10개. `gateway.api-versions`도 대부분
`[v1, v2]` 병행 설정이다. 이 ADR이 깔아둔 메커니즘(버전 리스트, 비활성=404, 화이트리스트
연동, order 필드)은 정확히 의도한 대로 실제 전환에 쓰인 것으로 확인됨 — 설계 자체는
유효하고 재작업 불필요. 다만 `notification-service`(`[v2]`만 활성) 같은 신규 서비스
추가 사례가 본문에는 반영되어 있지 않다.
