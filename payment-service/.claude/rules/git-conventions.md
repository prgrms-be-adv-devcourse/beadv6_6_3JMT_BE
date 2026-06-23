payment-service Git/협업 규칙. 커밋·브랜치·PR 작업 시 따른다.

## 커밋

형식: `type: 한국어 설명` (scope 미사용). 설명은 한국어, type 접두사는 영어.

| type | 용도 |
|---|---|
| `feat` | 새 기능 추가 |
| `fix` | 버그 수정 |
| `docs` | 문서 변경 |
| `chore` | 설정·빌드·잡일 (로직 변경 없음) |
| `test` | 테스트 추가·수정 |
| `style` | 포맷팅 (로직 변경 없음) |
| `refactor` | 동작 불변 구조 개선 |

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

형식: `type/#이슈번호-설명`

- `type`은 커밋 type과 동일 집합을 사용한다.
- 설명: **영어 소문자 kebab-case**.
- `#이슈번호`: **원칙 필수**. 이슈 없는 초기 셋업성 작업만 예외적으로 생략 가능하다.

```
feat/#12-product-list-query     ← 일반 기능 브랜치
chore/#6-payment-service-setting
chore/checkstyle                ← 이슈 없는 셋업성 예외
```

기본 브랜치: `main`(운영), `develop`(통합). 작업은 항상 별도 브랜치에서 진행한다.

## PR / 머지

- 작업 브랜치 → `develop`으로 PR. **`develop`/`main` 직접 푸시 금지**.
- 머지 전략: **GitHub merge commit** (squash 아님) — feature 브랜치의 개별 커밋 히스토리를 `develop`에 보존한다.
- 머지 커밋 제목은 GitHub 기본값(`Merge pull request #번호 from org/브랜치`)을 유지한다.
- PR 템플릿은 **모노레포 루트 `.github/`** 의 것을 사용한다 (서비스별로 새로 만들지 않는다).
- 이슈 연결은 PR 본문에서 `Closed #번호` / `Related #번호`로 명시한다.
- **커밋·푸시·PR은 사용자가 요청할 때만 수행한다.**

## 워크플로 요약

```
이슈 생성 → 브랜치 생성 → 커밋 → PR(develop) → merge commit
```
