---
name: write-tests
description: Product Service 변경사항에 맞는 테스트 범위를 판단하고 테스트를 추가·수정한 뒤 build로 검증한다. "테스트 만들어줘", "PR 전에 테스트 확인해줘" 같은 요청에 사용한다.
---

# Write Tests Skill

## 1. 사전 확인

`.claude/rules/testing.md`, `.claude/rules/architecture.md`, `.claude/rules/product-api.md`를
읽는다.

## 2. 변경 계층 분류

```bash
git branch --show-current
git status --short
git diff --stat
```

- `presentation/controller` 변경 → Controller 테스트
- `application` 변경 → Service 테스트
- `domain` 변경 → Domain 테스트
- `infra/persistence` 변경 → Repository 테스트
- 문서만 변경 → 테스트 추가 없이 build 확인만

## 3. 기존 스타일 확인

```bash
rg --files product-service/src/test
rg "@SpringBootTest|MockMvc|Mockito|DataJpaTest|DisplayName" product-service/src/test
```

## 4. 작성 기준

`.claude/rules/testing.md`의 변경 유형별 기준을 따른다. public API 테스트에는 인증 헤더를
넣지 않는다. 인증 필요 API는 `X-User-Id`, `X-User-Role`을 직접 넣는다.

## 5. Build 검증

```bash
cd product-service
./gradlew clean build --no-daemon
```

## 6. 보고

추가/수정한 테스트 파일, 검증한 케이스, build 결과, 남은 테스트 공백을 보고한다.

## 금지 사항

- `test` task만 실행하고 PR 전 검증이 끝났다고 말하지 않는다.
- Controller 테스트에서 service 로직을 직접 검증하지 않는다.
- 실패한 테스트를 삭제해서 build를 통과시키지 않는다.
