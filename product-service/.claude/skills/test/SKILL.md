---
name: product-test
description: Product Service 변경사항에 맞는 테스트 범위를 판단하고 테스트 추가, 수정, build 검증까지 진행한다.
---

# Product Test Skill

Product Service 테스트 작성 또는 테스트 확인 요청을 받으면 이 절차를 따른다.

## 언제 사용하나

사용자가 아래처럼 요청하면 이 skill을 사용한다.

- `product-service 테스트 만들어줘`
- `product-service test skill 시작`
- `ProductControllerTest 추가해줘`
- `테스트 확인해줘`
- `PR 전에 테스트 기준 확인해줘`

## 먼저 읽을 문서

1. `product-service/CLAUDE.md`
2. `product-service/.claude/rules/testing.md`
3. `product-service/.claude/rules/architecture.md`
4. `product-service/.claude/rules/product-api.md`
5. `product-service/.claude/rules/git-workflow.md`

Product API 테스트라면 추가로 읽는다.

- `docs/api-spec/product.md`
- `docs/erd/schema.md`
- `docs/domain-glossary/product.md`
- 프론트 프로젝트 `C:\programmers_prj\beadv6_6_3JMT_FE`의 관련 화면과 API 호출 흐름

## 절차

1. 현재 branch와 변경 파일을 확인한다.

   ```bash
   git branch --show-current
   git status --short
   git diff --stat
   ```

2. 변경 계층을 분류한다.

   - `presentation/controller` 변경: Controller 테스트 필요
   - `application` 변경: Service 테스트 필요
   - `domain` 변경: Domain 테스트 필요
   - `infra/persistence` 변경: Repository 또는 persistence 테스트 필요
   - 문서만 변경: 테스트 추가 없이 build 확인

3. 기존 테스트 스타일을 확인한다.

   ```bash
   rg --files product-service/src/test
   rg "@SpringBootTest|MockMvc|Mockito|DataJpaTest|DisplayName" product-service/src/test
   ```

4. 필요한 테스트 파일을 결정하고 사용자에게 짧게 알린 뒤 수정한다.

5. 테스트 작성 기준:

   - Controller 테스트는 요청 URL, parameter, status, 공통 응답 포맷을 검증한다.
   - Service 테스트는 repository/client mock과 business rule을 검증한다.
   - Repository 테스트는 custom query나 Querydsl이 있을 때 추가한다.
   - public API 테스트에는 인증 헤더를 넣지 않는다.
   - 인증 필요 API 테스트에는 `X-User-Id`, `X-User-Role`을 직접 넣는다.

6. product-service build를 실행한다.

   Windows:

   ```powershell
   cd C:\programmers_prj\beadv6_6_3JMT_BE\product-service
   .\gradlew.bat clean build --no-daemon
   ```

   CI와 유사하게 확인할 때:

   ```powershell
   cd C:\programmers_prj\beadv6_6_3JMT_BE\product-service

   $env:DB_HOST="localhost"
   $env:DB_PORT="5432"
   $env:DB_NAME="prompthub_test"
   $env:DB_USERNAME="test"
   $env:DB_PASSWORD="test"

   .\gradlew.bat clean build --no-daemon
   ```

7. 실패하면 원인을 분류한다.

   - compile 실패
   - checkstyle 실패
   - 테스트 assertion 실패
   - Spring context 실패
   - DB 연결 또는 datasource 실패

8. 최종 보고에 포함한다.

   - 추가/수정한 테스트 파일
   - 검증한 케이스
   - build 실행 결과
   - 남은 테스트 공백

## 금지 사항

- `test` task만 실행하고 PR 전 검증이 끝났다고 말하지 않는다.
- Controller 테스트에서 service 로직을 직접 검증하지 않는다.
- Service 테스트에서 HTTP status를 검증하지 않는다.
- public API 테스트에 인증 헤더를 넣어 성공시키지 않는다.
- 실패한 테스트를 삭제해서 build를 통과시키지 않는다.

