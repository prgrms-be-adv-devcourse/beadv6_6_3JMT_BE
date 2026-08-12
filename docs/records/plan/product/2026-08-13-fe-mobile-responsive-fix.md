# FE 모바일 반응형 깨짐 수정

작성일: 2026-08-13
구현 대상 저장소: `beadv6_6_3JMT_FE`

이 문서는 FE 전역 작업이지만 BE product 품질 로드맵과 함께 추적하기로 한 사용자 결정에 따라 이 저장소에
보관한다.

## 목적과 배경

구매자와 판매자 화면 전체를 실제 모바일 뷰포트로 감사해 가로 스크롤, 요소 겹침, 잘림, 터치 불가 같은
레이아웃 결함만 수정한다. 색상과 타이포그래피의 시각적 재설계는 후속 단계로 미룬다.

FE는 Next.js 16과 Tailwind CSS 4를 사용한다. `@playwright/test`는 devDependency에 있지만 설정과 테스트는
아직 없다. 브라우저 창 리사이즈가 실제 viewport에 반영되지 않았던 환경 제약을 피하기 위해 Playwright의
고정 viewport와 스크린샷을 감사 수단으로 사용한다.

## 범위

- `/`: 홈
- `/browse`: 탐색, 검색, 정렬
- `/detail/[id]`: 상품 상세
- `/mypage`: 프로필, 구매한 프롬프트, 찜한 프롬프트, 주문 내역, 설정
- `/shop`: 판매자 상점과 상품 관리
- `/sell`: 신규 상품 등록
- `/edit/[id]`: 상품 수정

`/admin/**`, 시각적 재설계, 태블릿 최적화는 제외한다. 동적 경로에는 재현 가능한 fixture ID를 사용하고,
구매자 상태와 판매자 상태는 별도 인증 세션으로 준비한다.

## 완료 기준

375px, 390px, 393px, 430px viewport에서 다음을 충족한다.

- 문서의 `scrollWidth`가 viewport 폭을 넘지 않는다.
- 텍스트, 버튼, 카드가 겹치거나 잘리지 않는다.
- 주요 버튼과 링크의 터치 영역이 최소 44×44px다.
- 페이지 로딩 및 브라우저 콘솔 오류가 없다.
- 1440px 데스크톱 화면의 기존 색상, 타이포그래피, 레이아웃이 회귀하지 않는다.

색상과 타이포그래피 토큰은 유지하되, 44×44px 터치 영역을 확보하는 데 필요한 최소 크기와 여백 조정은
허용한다.

## 설계

### Playwright 모바일 감사

`playwright.config.ts`와 `tests/mobile-audit/`를 추가한다. 설치된 Playwright 버전의 device descriptor가
제공하는 실제 viewport를 실행 시 확인하며, 완료 범위에 없는 430px viewport는 별도 프로젝트로 정의한다.
BUYER와 SELLER `storageState`를 분리하고 동적 상품 ID와 계정별 데이터 준비 방식을 테스트 설정에 명시한다.

스크린샷은 육안 비교에 사용하고 다음 항목은 DOM assertion으로 검증한다.

- 가로 overflow
- 주요 상호작용 요소의 bounding box
- 페이지 로딩 오류와 console error

대량 스크린샷과 Playwright 실행 결과는 임시 산출물로 두고, 재사용할 설정과 감사 테스트만 추적한다.

### 모바일 우선 레이아웃 수정

Tailwind 기본 클래스를 모바일 레이아웃으로 두고 `md:` 이상에서 기존 데스크톱 배치를 복원한다. 먼저 상품
카드, 헤더·검색바·내비게이션, 반복 그리드를 수정하고 전체 화면을 다시 감사한다. 공유 컴포넌트로 해결되지
않은 페이지 고유 결함만 해당 페이지에서 수정한다.

## 실행 순서

1. 고정 fixture와 BUYER·SELLER 인증 상태를 준비한다.
2. Playwright 설정과 모바일 감사 테스트를 작성해 기준 스크린샷과 실패 목록을 만든다.
3. 상품 카드, 헤더·검색바·내비게이션, 공통 그리드를 모바일 우선으로 수정한다.
4. 전체 화면을 재감사하고 남은 페이지 고유 결함만 수정한다.
5. 375~430px 모바일과 1440px 데스크톱을 다시 실행해 완료 기준을 검증한다.

## 검증

- Playwright 모바일 감사 테스트
- 기준 화면과 완료 화면의 스크린샷 비교
- FE 저장소의 lint, typecheck, build
- 가능하면 대표 화면 하나 이상을 실제 모바일 브라우저로 최종 확인

## 구현 결과

- 실제 구현: 홈, Header, Footer, browse, detail, mypage, shop, sell, edit의 기존 인라인 데스크톱 값을
  유지하면서 모바일 기본값과 `md:` 복원 Tailwind 클래스를 추가했다. Header·Logo·Tag·필터·정렬·상점
  채팅·Footer 등 모바일 상호작용 요소에는 최소 44×44px 터치 영역을 적용했다.
- 설계 차이: FE 저장소가 현재 쓰기 허용 루트 밖이어서 `playwright.config.ts`와 감사 테스트 파일은 만들지
  못했다. 대신 이미 설치된 Playwright와 Chromium을 직접 실행해 동일 viewport·overflow·터치 영역을
  검증하고 스크린샷을 임시 산출물로 확인했다.
- 검증: 공개 화면은 375·390·393·430·1440px, 인증 경로는 375·430px에서 가로 overflow 0을 확인했다.
  375px의 홈·browse·shop·sell에서 보이는 버튼과 링크는 모두 44×44px 이상이다. `tsc --noEmit`, build,
  `git diff --check`는 통과했다. 전체 lint는 작업 전부터 존재한 22 errors·93 warnings로 실패했다.
- 남은 배포 검증: 실제 BUYER·SELLER 인증과 유효 상품 ID로 mypage, detail, edit의 데이터 로드 완료 화면을
  Vercel 배포 환경에서 확인한다.
- Quality: 기능·데이터 흐름·화면 구성·색상·타이포그래피를 바꾸지 않고 Tailwind 반응형 클래스만 사용했다.
  전역 CSS 우회와 신규 의존성은 추가하지 않았다.
- 현재 상태: `IMPLEMENTED · PR_PENDING`.
