# Git 컨벤션

팀이 정한 Git 사용 규칙. 커밋 메시지, 브랜치 명명, PR·병합 전략을 정리한다.

## 커밋 메시지

`<타입>: <내용>` 형식으로 작성한다. 설명은 한국어, 타입 접두사는 영어. scope는 쓰지 않는다.

| 타입 | 설명 | 예시 |
| --- | --- | --- |
| `feat` | 새 기능 추가 | `feat: 상품 목록 조회 API 연결` |
| `fix` | 버그 수정 | `fix: 장바구니 총 금액 계산 오류 수정` |
| `refactor` | 기능 변경 없이 코드 구조 개선 | `refactor: 결제 로직 서비스 레이어 분리` |
| `docs` | 문서 수정 | `docs: API 명세서 업데이트` |
| `chore` | 설정 파일, 패키지 등 변경 | `chore: ESLint 설정 추가` |
| `style` | 코드 형식만 변경 (기능 변경 없음) | `style: Prettier 적용` |
| `test` | 테스트 코드 추가 | `test: 주문 생성 단위 테스트 추가` |

**본문(body)**: 변경 폭이 크거나 맥락 설명이 필요하면 제목 뒤 빈 줄 후 불릿(`-`) 본문을 작성한다. 작은 변경은 제목만으로 충분하다.

**`#이슈번호`는 커밋 본문에 쓰지 않는다** — 이슈 추적은 브랜치명과 PR에서 한다.

**AI 협업 커밋**: AI가 작업에 기여한 경우 본문 마지막에 빈 줄 후 트레일러를 붙인다. 모델명은 실제 작업에 사용한 버전으로 기입한다(버전 생략 금지).

```
Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>
```

**예시 — 제목만:**
```
chore: Checkstyle 설정 추가
```

**예시 — 제목 + 본문 + AI 트레일러:**
```
chore: 클린 아키텍처 패키지 구조 정비 및 아키텍처 문서 작성

- 클린 아키텍처 기반 패키지 골격 생성 (domain, application, interfaces, infrastructure)
- usecase, gateway/{persistence,external,messaging} 구조로 전환
- 기존 파일 새 구조로 이동 및 import 갱신

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>
```

## 브랜치

### 명명 규칙

`<타입>/#<이슈번호>-<내용>` 형식으로 작성한다. 내용은 영어 소문자 kebab-case로 쓴다.
`#이슈번호`는 원칙적으로 필수이며, 이슈 없는 초기 셋업성 작업만 예외적으로 생략할 수 있다.

예:

- `feat/#12-product-list-query`
- `infra/#340-kubernetes-migration`
- `chore/checkstyle` ← 이슈 없는 셋업성 예외

### 브랜치 타입

작업 브랜치:

- `feat`
- `fix`
- `refactor`
- `docs`
- `chore`
- `style`
- `test`
- `infra`

운영 브랜치:

- `hotfix`
- `release`

기본 브랜치 (직접 작업 금지):

- `main`
- `develop`

## PR / 머지

- 작업 브랜치 → `develop`으로 PR. **`develop`/`main` 직접 푸시 금지**.
- 머지 전략: **GitHub merge commit**(squash 아님) — feature 브랜치의 개별 커밋 히스토리를 `develop`에 보존한다.
- 머지 커밋 제목은 GitHub 기본값(`Merge pull request #번호 from org/브랜치`)을 유지한다.
- PR 템플릿은 모노레포 루트 `.github/`의 것을 사용한다(서비스별로 새로 만들지 않는다).
- 이슈 연결은 PR 본문에서 `Closed #번호` / `Related #번호`로 명시한다.
- **커밋·푸시·PR은 사용자가 요청할 때만 수행한다.**

## 개발 환경

프로파일은 `local`(개인 PC) / `dev`(AWS 개발서버, CD 배포) / `test`(빌드 테스트 전용)
3단이다. **운영(prod) 서버는 없다** — `prod` 프로파일·`application-prod.yml`은 만들지
않는다. 상세 규칙은 `docs/adr/config-management.md` 참고.

## 워크플로 요약

```
이슈 생성 → 브랜치 생성 → 커밋 → PR(develop) → merge commit
```
