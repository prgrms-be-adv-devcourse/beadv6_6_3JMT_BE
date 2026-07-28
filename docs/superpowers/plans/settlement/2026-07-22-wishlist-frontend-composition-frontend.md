# Wishlist Frontend Composition Frontend Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 마이페이지 Wishlist가 User, Product, Seller API를 순차 호출해 상품 카드 데이터를 조합하고, 축소된 User Wishlist 응답으로 전역 찜 상태를 동기화한다.

**Architecture:** API helper는 서비스별 계약과 최대 30개 배치 처리를 캡슐화한다. 순수 `wishlistComposition` 모듈은 `productId`와 `sellerId` Map으로 화면 카드와 전역 찜 동기화 항목을 만든다. 마이페이지는 Wishlist → Product → Seller waterfall을 제어하며 Product 실패는 화면 오류, Seller 실패는 `탈퇴한 판매자` fallback으로 처리한다.

**Tech Stack:** Next.js 16.2.9, React 19.2.4, TypeScript, Axios, Zustand 5, Node.js `node:test`, ESLint, Next build

## Global Constraints

- 대상 저장소는 `/Users/taetaetae/IdeaProjects/beadv6_6_3JMT_FE`다.
- 기준 이슈는 BE 저장소의 `#485 (이슈)`다.
- 구현 시작 시 FE `origin/main`에서 `feat/#485-split-wishlist-composition` 브랜치를 별도로 만든다. 현재 로컬 브랜치의 unrelated 변경을 가져오지 않는다.
- 백엔드 선행 조건은 `#478 (PR)`의 Product API와 #485 백엔드 계획의 User API 배포다.
- Wishlist User 응답은 `wishlistId`, `productId`, `addedAt`만 사용한다.
- Product 요청은 `POST /api/v2/products/wishlists` body `{ productIds: string[] }`를 사용한다. 구 `GET /products/by-ids` fallback은 두지 않는다.
- Seller 요청은 `POST /api/v2/sellers/wishlists` body `{ sellerIds: string[] }`를 사용한다. Wishlist 화면에서 `/sellers/products`를 호출하지 않는다.
- Product와 Seller 배치는 중복 제거 후 최대 30개씩 요청한다.
- 원래 Wishlist 순서를 유지하고 Product 응답에 없는 항목은 제외한다.
- Seller 응답 누락, `sellerName: null`, Seller API 실패는 `탈퇴한 판매자`로 표시한다.
- Product API 실패는 빈 Wishlist로 위장하지 않고 로딩 오류 상태를 표시한다.
- `useWishStore`는 찜 여부와 삭제용 `wishlistId`를 축소 응답만으로 동기화할 수 있어야 한다.
- 현재 마이페이지 query 계약은 `?tab=wishlist`다. `table` key나 `tab=wish` 네비게이션 수정은 별도 작업이며 이번 데이터 조합 범위에 포함하지 않는다.
- 구현 전 변경 파일에 해당하는 `AGENTS.md`, `app/CLAUDE.md`, `app/mypage/CLAUDE.md`, `components/CLAUDE.md`, `store/CLAUDE.md`와 Next.js 로컬 문서를 읽는다.

---

## Execution Preflight

```bash
cd /Users/taetaetae/IdeaProjects/beadv6_6_3JMT_FE
git status --short
git fetch origin main
git switch -c 'feat/#485-split-wishlist-composition' origin/main
sed -n '1,220p' AGENTS.md
sed -n '1,220p' app/CLAUDE.md
sed -n '1,220p' app/mypage/CLAUDE.md
sed -n '1,220p' components/CLAUDE.md
sed -n '1,220p' store/CLAUDE.md
sed -n '1,220p' node_modules/next/dist/docs/01-app/01-getting-started/05-server-and-client-components.md
```

Expected: 작업 트리 clean, 새 브랜치가 `origin/main`을 추적하고 관련 규칙과 Next.js Client Component 지침을 확인한다. 미커밋 변경이 있으면 임의 stash/reset하지 말고 사용자에게 가져갈지 확인한다.

---

## File Map

### Create

- `lib/batchIds.ts` — 서비스 API에서 공유할 UUID 중복 제거와 30개 청크 분할 순수 함수.
- `lib/batchIds.test.ts` — 빈 목록, 중복 제거, 30개 초과 분할 테스트.
- `lib/wishlistComposition.ts` — 세 API 응답의 카드 조합과 최소 WishStore 항목 변환.
- `lib/wishlistComposition.test.ts` — 순서 유지, 누락 Product 제외, Seller fallback, 전역 동기화 테스트.

### Modify

- `lib/wishlists.ts` — 축소된 Wishlist 응답 타입을 반영한다.
- `lib/products.ts` — Product Wishlist POST 계약과 공통 청크 함수를 사용한다.
- `lib/sellers.ts` — Wishlist Seller POST helper를 추가하고 공통 청크 함수를 사용한다.
- `app/mypage/page.tsx` — 세 API waterfall, 오류 상태, 순수 조합 함수를 연결한다.
- `components/providers/AuthSync.tsx` — User Wishlist 응답의 최소 식별자만 WishStore에 동기화한다.
- `store/useWishStore.ts` — 서버 동기화 항목에서 상품 표시 필드를 선택값으로 허용한다.

---

### Task 1: 서비스별 Wishlist API 계약과 공통 배치 분할

**Files:**

- Create: `lib/batchIds.ts`
- Create: `lib/batchIds.test.ts`
- Modify: `lib/wishlists.ts`
- Modify: `lib/products.ts`
- Modify: `lib/sellers.ts`

**Interfaces:**

- Produces: `splitUniqueIds(ids: string[], size?: number): string[][]`
- Produces: `getWishlists(): Promise<WishlistItem[]>`
- Produces: `getProductsByIds(productIds: string[]): Promise<ProductByIdsItem[]>` using the Wishlist POST contract
- Produces: `getWishlistSellerNames(sellerIds: string[]): Promise<Record<string, string | null>>`
- Preserves: Browse의 `getSellerNames()`와 Seller summary/profile helper

- [ ] **Step 1: 공통 배치 분할 실패 테스트 작성**

`lib/batchIds.test.ts`를 생성한다.

```ts
import assert from 'node:assert/strict'
import test from 'node:test'

import { splitUniqueIds } from './batchIds.ts'

test('splitUniqueIds returns no chunks for an empty list', () => {
  assert.deepEqual(splitUniqueIds([]), [])
})

test('splitUniqueIds removes duplicates and preserves first-seen order', () => {
  assert.deepEqual(splitUniqueIds(['a', 'b', 'a']), [['a', 'b']])
})

test('splitUniqueIds splits more than 30 ids into ordered chunks', () => {
  const ids = Array.from({ length: 31 }, (_, index) => `id-${index + 1}`)

  const chunks = splitUniqueIds(ids)

  assert.equal(chunks.length, 2)
  assert.equal(chunks[0].length, 30)
  assert.deepEqual(chunks[1], ['id-31'])
})
```

- [ ] **Step 2: 함수가 없어 테스트가 실패하는지 확인**

Run:

```bash
node --test lib/batchIds.test.ts
```

Expected: FAIL with module-not-found 또는 `splitUniqueIds` export 없음.

- [ ] **Step 3: UUID 중복 제거와 청크 분할 함수 구현**

`lib/batchIds.ts`를 생성한다.

```ts
export const API_BATCH_MAX = 30

export function splitUniqueIds(ids: string[], size = API_BATCH_MAX): string[][] {
  const unique = Array.from(new Set(ids))
  const chunks: string[][] = []
  for (let index = 0; index < unique.length; index += size) {
    chunks.push(unique.slice(index, index + size))
  }
  return chunks
}
```

- [ ] **Step 4: WishlistItem을 User 소유 필드로 축소**

`lib/wishlists.ts`의 `WishlistItem`을 다음 타입으로 바꾼다. `getWishlists`의 불필요한 generic을 제거해 실제 계약을 고정한다.

```ts
export interface WishlistItem {
  wishlistId: string
  productId: string
  addedAt: string
}

export async function getWishlists(): Promise<WishlistItem[]> {
  const res = await api.get<{
    success: boolean
    data: WishlistItem[]
    message: string
  }>(`${API_BASE}/wishlists`)
  return res.data.data ?? []
}
```

`getWishlistIdForProduct`는 반환 타입과 로직을 그대로 유지한다.

- [ ] **Step 5: Product helper를 #478 POST 계약으로 변경**

`lib/products.ts`에 다음 import를 추가한다.

```ts
import { splitUniqueIds } from '@/lib/batchIds'
```

`PRODUCT_BATCH_MAX`을 삭제하고 기존 `getProductsByIds` 구현을 다음 코드로 교체한다. 함수 이름은 호출자 호환을 유지하지만 HTTP 계약은 Wishlist POST로 전환한다.

```ts
export async function getProductsByIds(productIds: string[]): Promise<ProductByIdsItem[]> {
  const chunks = splitUniqueIds(productIds)
  if (chunks.length === 0) return []

  const responses = await Promise.all(
    chunks.map((productIdsChunk) =>
      api.post<{ success: boolean; data: ProductByIdsItem[]; message: string }>(
        `${API_BASE}/products/wishlists`,
        { productIds: productIdsChunk },
      ),
    ),
  )

  return responses.flatMap((res) => res.data.data ?? [])
}
```

- [ ] **Step 6: Seller helper에 Wishlist 경로 추가하고 청크 로직 공유**

`lib/sellers.ts`에 다음 import를 추가한다.

```ts
import { splitUniqueIds } from '@/lib/batchIds'
```

기존 `SELLER_BATCH_MAX`과 `getSellerNames`를 다음 공통 구현과 공개 함수 두 개로 교체한다.

```ts
async function getSellerNamesByPurpose(
  sellerIds: string[],
  purpose: 'products' | 'wishlists',
): Promise<Record<string, string | null>> {
  const chunks = splitUniqueIds(sellerIds)
  if (chunks.length === 0) return {}

  const responses = await Promise.all(
    chunks.map((sellerIdsChunk) =>
      api.post<{ success: boolean; data: { sellers: SellerBatchItem[] }; message: string }>(
        `${API_BASE}/sellers/${purpose}`,
        { sellerIds: sellerIdsChunk },
      ),
    ),
  )

  const names: Record<string, string | null> = {}
  responses.forEach((res) => {
    res.data.data.sellers.forEach((seller) => {
      names[seller.sellerId] = seller.sellerName
    })
  })
  return names
}

export function getSellerNames(sellerIds: string[]): Promise<Record<string, string | null>> {
  return getSellerNamesByPurpose(sellerIds, 'products')
}

export function getWishlistSellerNames(
  sellerIds: string[],
): Promise<Record<string, string | null>> {
  return getSellerNamesByPurpose(sellerIds, 'wishlists')
}
```

- [ ] **Step 7: 배치 테스트와 TypeScript 계약 확인**

Run:

```bash
node --test lib/batchIds.test.ts
npx tsc --noEmit
```

Expected: 배치 테스트 3개 통과, TypeScript 오류 0. 기존 `getProductsByIds` export를 유지하므로 마이페이지 소비자도 이 단계에서 컴파일된다.

- [ ] **Step 8: API 계약 변경 커밋**

다음을 실행한다.

```bash
git add lib/batchIds.ts lib/batchIds.test.ts lib/wishlists.ts lib/products.ts lib/sellers.ts
git diff --cached --check
git commit -m "refactor: Wishlist 서비스별 API 계약 분리 (#485)"
```

---

### Task 2: 세 응답을 화면 카드로 조합하는 순수 모듈

**Files:**

- Create: `lib/wishlistComposition.ts`
- Create: `lib/wishlistComposition.test.ts`

**Interfaces:**

- Consumes: `WishlistItem[]`, `ProductByIdsItem[]`, `Record<sellerId, sellerName | null>`
- Produces: `composeWishlistCards(...) -> WishlistCard[]`
- Produces: `toSyncedWishItems(wishlists) -> SyncedWishItem[]`

- [ ] **Step 1: 순서·누락·fallback·동기화 실패 테스트 작성**

`lib/wishlistComposition.test.ts`를 생성한다.

```ts
import assert from 'node:assert/strict'
import test from 'node:test'

import {
  composeWishlistCards,
  toSyncedWishItems,
} from './wishlistComposition.ts'

const wishlists = [
  { wishlistId: 'wish-2', productId: 'product-2', addedAt: '2026-07-22T11:00:00' },
  { wishlistId: 'wish-1', productId: 'product-1', addedAt: '2026-07-22T10:00:00' },
]

const products = [
  {
    productId: 'product-1',
    sellerId: 'seller-1',
    title: 'Prompt 1',
    amount: 1000,
    thumbnailUrl: null,
    productType: 'PROMPT',
    model: 'GPT-4o',
    salesCount: 3,
    averageRating: 4.5,
    status: 'ON_SALE',
  },
  {
    productId: 'product-2',
    sellerId: 'seller-2',
    title: 'Prompt 2',
    amount: 2000,
    thumbnailUrl: '/thumb.png',
    productType: 'PROMPT',
    model: 'Claude',
    salesCount: 5,
    averageRating: 4.8,
    status: 'ON_SALE',
  },
]

test('composeWishlistCards keeps wishlist order and maps seller names', () => {
  const cards = composeWishlistCards(wishlists, products, {
    'seller-1': '판매자 1',
    'seller-2': '판매자 2',
  })

  assert.deepEqual(cards.map((card) => card.id), ['product-2', 'product-1'])
  assert.deepEqual(cards.map((card) => card.seller), ['판매자 2', '판매자 1'])
})

test('composeWishlistCards omits missing products and falls back for missing sellers', () => {
  const cards = composeWishlistCards(wishlists, [products[0]], {})

  assert.equal(cards.length, 1)
  assert.equal(cards[0].id, 'product-1')
  assert.equal(cards[0].seller, '탈퇴한 판매자')
})

test('toSyncedWishItems keeps only product and wishlist ids', () => {
  assert.deepEqual(toSyncedWishItems(wishlists), [
    { id: 'product-2', wishlistId: 'wish-2' },
    { id: 'product-1', wishlistId: 'wish-1' },
  ])
})
```

- [ ] **Step 2: 모듈이 없어 테스트가 실패하는지 확인**

Run:

```bash
node --test lib/wishlistComposition.test.ts
```

Expected: FAIL with module-not-found.

- [ ] **Step 3: 순수 조합 모듈 구현**

`lib/wishlistComposition.ts`를 생성한다.

```ts
import type { ProductByIdsItem } from './products.ts'
import type { WishlistItem } from './wishlists.ts'

export interface WishlistCard {
  id: string
  title: string
  thumbnail_url: string | null
  amount: number
  seller: string
  rating: number
  salesCount: number
  productType: string
  icon: string
  model: string
  desc: string
}

export interface SyncedWishItem {
  id: string
  wishlistId: string
}

export function composeWishlistCards(
  wishlists: WishlistItem[],
  products: ProductByIdsItem[],
  sellerNames: Record<string, string | null>,
): WishlistCard[] {
  const productMap = new Map(products.map((product) => [product.productId, product]))

  return wishlists.flatMap((wishlist) => {
    const product = productMap.get(wishlist.productId)
    if (!product) return []

    return [{
      id: product.productId,
      title: product.title,
      thumbnail_url: product.thumbnailUrl,
      amount: product.amount,
      seller: sellerNames[product.sellerId] ?? '탈퇴한 판매자',
      rating: product.averageRating,
      salesCount: product.salesCount,
      productType: product.productType,
      icon: '',
      model: product.model,
      desc: '',
    }]
  })
}

export function toSyncedWishItems(wishlists: WishlistItem[]): SyncedWishItem[] {
  return wishlists.map((wishlist) => ({
    id: wishlist.productId,
    wishlistId: wishlist.wishlistId,
  }))
}
```

- [ ] **Step 4: 순수 조합 테스트 통과 확인**

Run:

```bash
node --test lib/wishlistComposition.test.ts
```

Expected: 3 tests passed, 0 failed.

- [ ] **Step 5: 조합 모듈 커밋**

```bash
git add lib/wishlistComposition.ts lib/wishlistComposition.test.ts
git diff --cached --check
git commit -m "feat: Wishlist 카드 응답 조합 로직 추가 (#485)"
```

---

### Task 3: 마이페이지에 Wishlist → Product → Seller waterfall 연결

**Files:**

- Modify: `app/mypage/page.tsx`

**Interfaces:**

- Consumes: `getWishlists()`
- Consumes: `getProductsByIds(productIds)`
- Consumes: `getWishlistSellerNames(sellerIds)`
- Consumes: `composeWishlistCards(wishlists, products, sellerNames)`
- Produces: Wishlist 탭의 `Prompt[]` 카드와 명시적인 Product 로딩 오류 상태

- [ ] **Step 1: 마이페이지 import를 새 API와 조합 함수로 변경**

기존 import를 다음으로 바꾼다.

```ts
import { getWishlists } from '@/lib/wishlists';
import { getProductsByIds } from '@/lib/products';
import { getWishlistSellerNames } from '@/lib/sellers';
import { composeWishlistCards } from '@/lib/wishlistComposition';
```

- [ ] **Step 2: Wishlist 오류 상태 추가**

기존 `loadingWishlist` 상태 다음에 추가한다.

```ts
const [wishlistLoadError, setWishlistLoadError] = useState(false);
```

- [ ] **Step 3: 기존 Wishlist 로딩 블록을 세 단계 waterfall로 교체**

현재 `getWishlists().then(...)` 블록 전체를 다음 코드로 바꾼다.

```ts
setWishlistLoadError(false);
getWishlists()
  .then(async (items) => {
    const products = await getProductsByIds(items.map((item) => item.productId));

    let sellerNames: Record<string, string | null> = {};
    try {
      sellerNames = await getWishlistSellerNames(products.map((product) => product.sellerId));
    } catch {
      // 판매자 조회 실패는 카드 전체 실패로 전파하지 않고 fallback 문구를 사용한다.
    }

    setWishlist(composeWishlistCards(items, products, sellerNames));
  })
  .catch(() => {
    setWishlistLoadError(true);
  })
  .finally(() => setLoadingWishlist(false));
```

`getProductsByIds([])`와 `getWishlistSellerNames([])`는 API를 호출하지 않으므로 빈 Wishlist에서 추가 요청이 발생하지 않는다.

- [ ] **Step 4: Product 로딩 오류를 빈 Wishlist와 구분해 표시**

Wishlist 탭 조건을 다음 순서로 바꾼다.

```tsx
{loadingWishlist ? (
  <GridSkeleton />
) : wishlistLoadError ? (
  <EmptyState
    icon={Heart}
    text="찜한 프롬프트를 불러오지 못했어요."
    cta="다시 시도"
    onCta={() => window.location.reload()}
  />
) : wishlist.length === 0 ? (
  <EmptyState
    icon={Heart}
    text="아직 찜한 프롬프트가 없어요."
    cta="프롬프트 둘러보기"
    onCta={() => router.push('/browse')}
  />
) : (
  <div style={{ display: 'grid', gridTemplateColumns: 'repeat(3, 1fr)', gap: 20 }}>
    {wishlist.map((p) => (
      <PromptCard key={p.id} p={p} onClick={() => router.push(`/detail/${p.id}`)} />
    ))}
  </div>
)}
```

- [ ] **Step 5: TypeScript와 조합 회귀 테스트 확인**

Run:

```bash
node --test lib/batchIds.test.ts lib/wishlistComposition.test.ts
npx tsc --noEmit
```

Expected: 6 tests passed, TypeScript 오류 0. 기존 `getProductsByIds` import 또는 `WishlistItem` 상품 필드 참조가 남아 있으면 실패해야 한다.

- [ ] **Step 6: 마이페이지 연동 커밋**

```bash
git add app/mypage/page.tsx
git diff --cached --check
git commit -m "feat: 마이페이지 Wishlist 응답을 프론트에서 조합 (#485)"
```

---

### Task 4: 축소된 Wishlist 응답으로 전역 찜 상태 동기화

**Files:**

- Modify: `store/useWishStore.ts`
- Modify: `components/providers/AuthSync.tsx`
- Modify: `lib/wishlistComposition.test.ts`

**Interfaces:**

- Consumes: `toSyncedWishItems(WishlistItem[])`
- Produces: `{ id, wishlistId }`만으로도 저장 가능한 `WishItem`
- Preserves: PromptCard의 낙관적 추가·삭제와 `getWishlistIdForProduct`

- [ ] **Step 1: WishItem의 표시 필드를 선택값으로 변경**

`store/useWishStore.ts`의 `WishItem`을 export하고 상품 표시 필드를 선택값으로 바꾼다.

```ts
export interface WishItem {
  id: string
  title?: string
  amount?: number
  thumbnailUrl?: string | null
  wishlistId?: string | null
}
```

스토어 action 구현은 변경하지 않는다. Header, PromptCard, Detail은 `id`와 `wishlistId`만 필수로 사용하며 새 찜 추가 시 기존처럼 전체 표시 정보를 저장할 수 있다.

- [ ] **Step 2: AuthSync가 API helper와 최소 변환 함수를 사용하게 변경**

`components/providers/AuthSync.tsx`에서 직접 `api.get(${API_BASE}/wishlists)` 호출에만 쓰이던 `api`, `API_BASE` import를 제거하고 다음 import를 추가한다.

```ts
import { getWishlists } from '@/lib/wishlists';
import { toSyncedWishItems } from '@/lib/wishlistComposition';
```

Wishlist 동기화 effect의 로그인 분기 이후를 다음 코드로 바꾼다.

```ts
getWishlists()
  .then((items) => setItems(toSyncedWishItems(items)))
  .catch(() => {});
```

파일 상단의 `/users/me` 토큰 검증은 여전히 `api`와 `API_BASE`를 사용하므로 해당 import가 실제로 남아 있어야 한다. 자동으로 삭제하지 말고 전체 파일 사용처를 확인한다.

- [ ] **Step 3: 전역 동기화 변환 회귀 테스트 확인**

Run:

```bash
node --test lib/wishlistComposition.test.ts
npx tsc --noEmit
```

Expected: `toSyncedWishItems` 테스트 통과, `setItems` 인자 타입 오류 없음, PromptCard/Detail의 전체 `WishItem` 저장도 컴파일됨.

- [ ] **Step 4: 축소 응답 동기화 커밋**

```bash
git add store/useWishStore.ts components/providers/AuthSync.tsx lib/wishlistComposition.test.ts
git diff --cached --check
git commit -m "refactor: 전역 찜 상태를 최소 응답으로 동기화 (#485)"
```

---

### Task 5: 프론트 계약 검색과 전체 검증

**Files:**

- Verify only: `lib/`
- Verify only: `app/mypage/page.tsx`
- Verify only: `components/providers/AuthSync.tsx`
- Verify only: `store/useWishStore.ts`

**Interfaces:**

- Consumes: Tasks 1–4 전체 변경
- Produces: 구 합성 응답이나 구 Product GET 경로에 의존하지 않는 빌드 가능한 프론트

- [ ] **Step 1: 구 Wishlist 합성 필드와 구 API 경로 검색**

```bash
rg -n '/products/by-ids|sellerNickname|item\.title|item\.price|item\.thumbnailUrl|sellers/products' \
  app/mypage/page.tsx components/providers/AuthSync.tsx lib/wishlists.ts lib/products.ts lib/sellers.ts
```

Expected: Wishlist 흐름에서 `/products/by-ids`와 User Wishlist 상품 필드 참조가 없다. `lib/sellers.ts`의 `/sellers/products`는 Browse용 `getSellerNames` 구현에만 남는다.

- [ ] **Step 2: 새 API 경로와 request body 확인**

```bash
rg -n '/products/wishlists|productIds:|/sellers/wishlists|sellerIds:' \
  lib/products.ts lib/sellers.ts
```

Expected: Product와 Seller POST 경로 및 각각의 배열 body가 확인된다.

- [ ] **Step 3: 순수 단위 테스트 실행**

```bash
node --test lib/batchIds.test.ts lib/wishlistComposition.test.ts
```

Expected: 6 tests passed, 0 failed.

- [ ] **Step 4: lint와 production build 실행**

```bash
npm run lint
npm run build
```

Expected: ESLint 오류 0, Next.js production build 성공.

- [ ] **Step 5: 로그인 상태 수동 검증**

로컬 Gateway와 백엔드를 실행한 뒤 다음을 확인한다.

1. `/mypage?tab=wishlist` 진입 시 네트워크 호출이 Wishlist → Product → Seller 순서다.
2. Product 요청 body는 `{ productIds: [...] }`, Seller 요청 body는 `{ sellerIds: [...] }`다.
3. 상품·판매자 정보가 카드에 표시되고 Wishlist 순서가 유지된다.
4. 존재하지 않는 Product는 카드에서 제외된다.
5. 존재하지 않는 Seller는 `탈퇴한 판매자`로 표시된다.
6. Header 찜 개수와 상세·카드의 하트 활성 상태가 새로고침 뒤에도 유지된다.
7. 찜 삭제 시 User가 반환한 `wishlistId`가 DELETE 경로에 사용된다.

- [ ] **Step 6: 작업 트리와 커밋 경계 확인**

```bash
git status --short
git log --oneline --decorate origin/main..HEAD
git diff --check origin/main...HEAD
```

Expected: 작업 트리 clean, #485 관련 API·조합·동기화 커밋만 존재, whitespace 오류 없음.
