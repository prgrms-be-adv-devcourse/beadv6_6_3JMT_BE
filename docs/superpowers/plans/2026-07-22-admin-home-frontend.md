# Admin Home Frontend Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 프론트 어드민 홈의 다섯 API 조합을 GET /api/v2/admin/home 한 번 호출로 교체하고 정상값 0과 요청 실패를 구분한다.

**Architecture:** 백엔드 응답 계약과 화면 모델 변환은 Node 테스트가 가능한 순수 adminHomeAdapters 모듈로 분리한다. adminHome API 클라이언트는 통합 응답을 한 번 받아 변환하고, Client Component는 단일 로딩·오류·재시도 상태와 기존 디자인 토큰 기반 UI를 유지한다.

**Tech Stack:** Next.js 16.2.9 App Router, React 19.2.4, TypeScript, Axios, Node.js node:test, ESLint, Prettier

## Global Constraints

- 프론트 저장소는 /Users/taetaetae/IdeaProjects/beadv6_6_3JMT_FE다.
- 어드민 홈은 GET /api/v2/admin/home 한 번만 호출한다.
- 기존 getAdminUserStats, getAdminMonthlyOrders, getAdminWeeklyOrders, getAdminSettlementSummary, getAdminProducts 함수는 삭제하지 않는다.
- 백엔드 요청 오류를 0이나 빈 목록으로 바꾸지 않는다.
- 정상 응답의 실제 0은 그대로 렌더링한다.
- 상품 미리보기 길이와 pendingProducts.totalCount를 같은 값으로 가정하지 않는다.
- YYYY-MM-DD는 타임존 이동 없이 한국 요일과 M/D로 변환한다.
- 현재 작업 트리의 기존 어드민 상품·정산 변경을 보존하고 되돌리지 않는다.
- UI는 docs/design-tokens.md와 기존 ph-* 토큰을 유지한다.
- 모든 동작 변경은 실패 테스트를 먼저 확인한다.
- 계약 원본은 ../beadv6_6_3JMT_BE/docs/superpowers/specs/2026-07-22-admin-home-dashboard-design.md다.

---

## File Map

**Create:**
- lib/adminHomeAdapters.ts: 통합 응답 타입과 화면 모델 변환
- lib/adminHomeAdapters.test.ts: 날짜·KPI·상품 count 변환 테스트
- lib/adminHome.ts: /admin/home API 클라이언트

**Modify:**
- app/admin/page.tsx: 다섯 호출 제거, 단일 홈 상태·오류·재시도 연결
- app/admin/CLAUDE.md: 홈 데이터 원본과 실패 정책 갱신

---

### Task 0: Preserve the Existing Dirty Worktree

**Files:**
- Inspect only: app/admin/page.tsx
- Inspect only: app/admin/CLAUDE.md
- Inspect only: app/admin/settlements/CLAUDE.md
- Inspect only: app/admin/settlements/_components/AdminSettlementsView.tsx
- Inspect only: lib/adminProducts.ts
- Inspect only: lib/adminStats.ts
- Inspect only: lib/settlements.ts
- Inspect only: lib/adminProductAdapters.ts
- Inspect only: lib/adminProducts.test.ts

**Interfaces:**
- Produces: a clean, recoverable baseline before home work starts

- [ ] **Step 1: Record current status and diff**

~~~bash
git status --short
git diff -- app/admin/page.tsx app/admin/CLAUDE.md \
  app/admin/settlements/CLAUDE.md \
  app/admin/settlements/_components/AdminSettlementsView.tsx \
  lib/adminProducts.ts lib/adminStats.ts lib/settlements.ts
git diff --no-index /dev/null lib/adminProductAdapters.ts
git diff --no-index /dev/null lib/adminProducts.test.ts
~~~

Expected: only the known prior admin API, product mapping, and settlement changes appear.

- [ ] **Step 2: Verify the existing baseline before preserving it**

~~~bash
node --experimental-strip-types --test lib/*.test.ts
npx tsc --noEmit
npm run lint
npm run build
~~~

Expected: all Node tests pass, TypeScript and ESLint have zero errors, and the Next production build succeeds.

- [ ] **Step 3: Preserve the prior logical change separately**

Stage only the files listed in this task, review the staged diff, and create the pending prior-work commit before adding home files.

~~~bash
git add app/admin/page.tsx app/admin/CLAUDE.md \
  app/admin/settlements/CLAUDE.md \
  app/admin/settlements/_components/AdminSettlementsView.tsx \
  lib/adminProducts.ts lib/adminStats.ts lib/settlements.ts \
  lib/adminProductAdapters.ts lib/adminProducts.test.ts
git diff --cached --check
git diff --cached --stat
git commit -m "refactor: 어드민 API 계약을 현재 백엔드에 맞춤"
~~~

Expected: the worktree is clean after the commit. Do not push in this step. If inspection shows unrelated user changes, stop and ask the user instead of committing them.

---

### Task 1: Admin Home Contract Adapter

**Files:**
- Create: lib/adminHomeAdapters.ts
- Create: lib/adminHomeAdapters.test.ts

**Interfaces:**
- Consumes: AdminHomeResponseData
- Produces: mapAdminHome(data): AdminHomeViewModel and formatAdminHomeDate(date)

- [ ] **Step 1: Write the failing adapter tests**

~~~ts
import assert from 'node:assert/strict'
import test from 'node:test'
import { formatAdminHomeDate, mapAdminHome } from './adminHomeAdapters.ts'

test('formatAdminHomeDate formats ISO dates without timezone shifting', () => {
  assert.deepEqual(formatAdminHomeDate('2026-07-19'), { day: '일', displayDate: '7/19' })
  assert.deepEqual(formatAdminHomeDate('2026-07-20'), { day: '월', displayDate: '7/20' })
})

test('mapAdminHome maps integrated stats and keeps total review count', () => {
  const result = mapAdminHome({
    generatedAt: '2026-07-22T15:30:00+09:00',
    users: { totalUsers: 1250, todayNewUsers: 18 },
    transactions: {
      monthlyTransactionAmount: 32500000,
      recent7Days: {
        totalTransactionCount: 142,
        totalTransactionAmount: 8900000,
        period: { startDate: '2026-07-16', endDate: '2026-07-22' },
        dailyTransactions: [
          { date: '2026-07-19', transactionCount: 21, transactionAmount: 1250000 },
        ],
      },
    },
    settlements: { pendingApprovalAmount: 4200000, pendingApprovalCount: 12 },
    pendingProducts: {
      totalCount: 7,
      items: [{
        productId: 'product-1',
        title: '검수 대기 프롬프트',
        sellerNickname: '프롬프트랩',
        productType: 'PROMPT',
        model: 'GPT-5',
        amount: 10000,
        status: 'PENDING_REVIEW',
        createdAt: '2026-07-21T10:20:30',
      }],
    },
  })

  assert.deepEqual(result.stats, {
    totalUsers: 1250,
    newToday: 18,
    monthRevenue: 32500000,
    pendingApprovalAmount: 4200000,
    pendingApprovalCount: 12,
    weekTotal: 142,
    weekRevenue: 8900000,
    sales7d: [{ day: '일', date: '7/19', count: 21, revenue: 1250000 }],
  })
  assert.equal(result.reviewCount, 7)
  assert.equal(result.products.length, 1)
  assert.equal(result.products[0].status, 'review')
})
~~~

- [ ] **Step 2: Verify RED**

~~~bash
node --experimental-strip-types --test lib/adminHomeAdapters.test.ts
~~~

Expected: FAIL because adminHomeAdapters.ts does not exist.

- [ ] **Step 3: Define exact types**

~~~ts
export interface AdminHomeResponseData {
  generatedAt: string
  users: { totalUsers: number; todayNewUsers: number }
  transactions: {
    monthlyTransactionAmount: number
    recent7Days: {
      totalTransactionCount: number
      totalTransactionAmount: number
      period: { startDate: string; endDate: string }
      dailyTransactions: Array<{
        date: string
        transactionCount: number
        transactionAmount: number
      }>
    }
  }
  settlements: { pendingApprovalAmount: number; pendingApprovalCount: number }
  pendingProducts: {
    totalCount: number
    items: Array<{
      productId: string
      title: string
      sellerNickname: string
      productType: string
      model?: string
      amount: number
      status: string
      createdAt: string
    }>
  }
}

export interface AdminHomeStats {
  totalUsers: number
  newToday: number
  monthRevenue: number
  pendingApprovalAmount: number
  pendingApprovalCount: number
  weekTotal: number
  weekRevenue: number
  sales7d: Array<{ day: string; date: string; count: number; revenue: number }>
}

export interface AdminHomeViewModel {
  generatedAt: string
  stats: AdminHomeStats
  reviewCount: number
  products: AdminProduct[]
}
~~~

- [ ] **Step 4: Implement deterministic mapping**

Import AdminProduct and mapAdminProducts from ./adminProductAdapters.ts.

~~~ts
const DAY_LABELS = ['일', '월', '화', '수', '목', '금', '토'] as const

export function formatAdminHomeDate(date: string) {
  const match = /^(\d{4})-(\d{2})-(\d{2})$/.exec(date)
  if (!match) return { day: '', displayDate: date }
  const year = Number(match[1])
  const month = Number(match[2])
  const dayOfMonth = Number(match[3])
  const weekday = new Date(Date.UTC(year, month - 1, dayOfMonth)).getUTCDay()
  return { day: DAY_LABELS[weekday], displayDate: month + '/' + dayOfMonth }
}

export function mapAdminHome(data: AdminHomeResponseData): AdminHomeViewModel {
  return {
    generatedAt: data.generatedAt,
    stats: {
      totalUsers: data.users.totalUsers,
      newToday: data.users.todayNewUsers,
      monthRevenue: data.transactions.monthlyTransactionAmount,
      pendingApprovalAmount: data.settlements.pendingApprovalAmount,
      pendingApprovalCount: data.settlements.pendingApprovalCount,
      weekTotal: data.transactions.recent7Days.totalTransactionCount,
      weekRevenue: data.transactions.recent7Days.totalTransactionAmount,
      sales7d: data.transactions.recent7Days.dailyTransactions.map((item) => {
        const formatted = formatAdminHomeDate(item.date)
        return {
          day: formatted.day,
          date: formatted.displayDate,
          count: item.transactionCount,
          revenue: item.transactionAmount,
        }
      }),
    },
    reviewCount: data.pendingProducts.totalCount,
    products: mapAdminProducts(data.pendingProducts.items),
  }
}
~~~

Do not use ?? 0 fallbacks in this mapper.

- [ ] **Step 5: Verify GREEN and commit**

~~~bash
node --experimental-strip-types --test lib/adminHomeAdapters.test.ts lib/adminProducts.test.ts
git add lib/adminHomeAdapters.ts lib/adminHomeAdapters.test.ts
git commit -m "feat: 어드민 홈 응답 변환 계약 추가"
~~~

Expected: both test files PASS before commit.

---

### Task 2: Single Admin Home API Client

**Files:**
- Create: lib/adminHome.ts

**Interfaces:**
- Consumes: AdminHomeResponseData and mapAdminHome
- Produces: getAdminHome(): Promise<AdminHomeViewModel>

- [ ] **Step 1: Add the typed client**

~~~ts
import api from '@/lib/auth'
import { API_BASE } from '@/lib/apiBase'
import {
  mapAdminHome,
  type AdminHomeResponseData,
  type AdminHomeViewModel,
} from '@/lib/adminHomeAdapters'

export async function getAdminHome(): Promise<AdminHomeViewModel> {
  const response = await api.get<{
    success: boolean
    data: AdminHomeResponseData
    message: string
  }>(API_BASE + '/admin/home')
  return mapAdminHome(response.data.data)
}
~~~

Do not catch errors here.

- [ ] **Step 2: Type-check and commit**

~~~bash
npx tsc --noEmit
git add lib/adminHome.ts
git commit -m "feat: 어드민 홈 통합 API 클라이언트 추가"
~~~

Expected: zero TypeScript errors before commit.

---

### Task 3: Dashboard Single Loading and Error State

**Files:**
- Modify: app/admin/page.tsx
- Modify: app/admin/CLAUDE.md

**Interfaces:**
- Consumes: getAdminHome() and AdminHomeViewModel
- Produces: one-request dashboard, explicit failure panel, retry action

- [ ] **Step 1: Replace split imports and local contracts**

Remove getAdminUserStats, getAdminMonthlyOrders, getAdminWeeklyOrders, getAdminProducts, and getAdminSettlementSummary imports. Add:

~~~ts
import { getAdminHome } from '@/lib/adminHome'
import type { AdminHomeViewModel } from '@/lib/adminHomeAdapters'

type SalesPoint = AdminHomeViewModel['stats']['sales7d'][number]
~~~

Delete the page-local SalesPoint and Stats interfaces.

- [ ] **Step 2: Replace split state and Promise.all**

~~~ts
const [home, setHome] = useState<AdminHomeViewModel | null>(null)
const [loading, setLoading] = useState(true)
const [error, setError] = useState(false)

const load = async () => {
  setLoading(true)
  setError(false)
  try {
    setHome(await getAdminHome())
  } catch {
    setError(true)
  } finally {
    setLoading(false)
  }
}
~~~

Keep the token guard and call void load() in the effect. This page must not call legacy stats/product/settlement clients.

- [ ] **Step 3: Add whole-page error and retry UI**

Before the normal dashboard return:

~~~tsx
if (error) {
  return (
    <div className="rounded-ph-lg border border-ph-border bg-ph-white px-[24px] py-[56px] text-center">
      <div className="text-[15px] font-bold text-ph-text">
        대시보드 정보를 불러오지 못했어요
      </div>
      <div className="mt-[6px] text-[13.5px] text-ph-text-muted">
        잠시 후 다시 시도해 주세요.
      </div>
      <button
        type="button"
        onClick={() => void load()}
        className="mt-[18px] inline-flex h-[36px] items-center justify-center rounded-ph-sm bg-ph-primary px-[16px] text-[13.5px] font-semibold text-white hover:bg-ph-blue-hover"
      >
        다시 시도
      </button>
    </div>
  )
}
~~~

This branch must not render KPI values as zero.

- [ ] **Step 4: Bind backend totals and preview**

~~~ts
const stats = home?.stats
const sales = stats?.sales7d ?? []
const reviewProducts = home?.products ?? []
const reviewCount = home?.reviewCount ?? 0
const weekTotal = stats?.weekTotal ?? 0
const weekRevenue = stats?.weekRevenue ?? 0
~~~

Cards use totalUsers, newToday, monthRevenue, pendingApprovalAmount, pendingApprovalCount. Remove client-side weekly reductions and products.filter(...).slice(0, 4).

- [ ] **Step 5: Update local documentation**

Add these exact rules to app/admin/CLAUDE.md:

~~~markdown
- 어드민 홈은 GET /api/v2/admin/home 단일 응답으로 회원 KPI, 월간·최근 7일 거래, 정산 승인 대기, 검수 대기 상품 미리보기를 렌더링한다.
- 홈 요청 실패를 정상 통계 0으로 대체하지 않고 전체 오류 상태와 재시도를 제공한다.
- 검수 대기 전체 건수는 pendingProducts.totalCount, 화면 목록은 pendingProducts.items를 사용한다.
~~~

- [ ] **Step 6: Run formatting and static checks**

~~~bash
npx prettier --check app/admin/page.tsx app/admin/CLAUDE.md \
  lib/adminHome.ts lib/adminHomeAdapters.ts lib/adminHomeAdapters.test.ts
npx tsc --noEmit
npm run lint
~~~

Expected: all commands exit 0.

- [ ] **Step 7: Commit the home integration**

~~~bash
git add app/admin/page.tsx app/admin/CLAUDE.md \
  lib/adminHome.ts lib/adminHomeAdapters.ts lib/adminHomeAdapters.test.ts
git diff --cached --check
git diff --cached --stat
git commit -m "feat: 어드민 홈 통합 API 연결"
~~~

Expected: only home integration files are staged because Task 0 preserved the prior dirty baseline.

---

### Task 4: Frontend Regression Verification

**Files:**
- Verify: app/admin/page.tsx
- Verify: lib/adminHome.ts
- Verify: lib/adminHomeAdapters.ts
- Verify: lib/adminHomeAdapters.test.ts

**Interfaces:**
- Produces: frontend verification evidence

- [ ] **Step 1: Run all Node contract tests**

~~~bash
node --experimental-strip-types --test lib/*.test.ts
~~~

Expected: zero failed tests.

- [ ] **Step 2: Run TypeScript, lint, and production build**

~~~bash
npx tsc --noEmit
npm run lint
npm run build
~~~

Expected: zero type/lint errors and successful production build.

- [ ] **Step 3: Verify one API source**

~~~bash
rg -n 'getAdmin(Home|UserStats|MonthlyOrders|WeeklyOrders|Products|SettlementSummary)' app/admin/page.tsx
~~~

Expected: only getAdminHome is imported and called.

- [ ] **Step 4: Review final status**

~~~bash
git status --short
git diff --check
git diff --stat
~~~

Expected: clean worktree and no whitespace errors. Do not reset, delete, or silently include any newly discovered unrelated user changes.
