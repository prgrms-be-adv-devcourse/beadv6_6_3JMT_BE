# PromptHub — AI 프롬프트 마켓 기획 문서

## 1. 프로젝트 소개

- **프로젝트 이름**: AI 프롬프트 마켓 (PromptHub)
- **판매 상품(서비스)**: 각종 프롬프트 및 스킬(`.md`)

생성형 AI 활용이 늘면서, 잘 만들어진 프롬프트 자체가 거래 가치를 가지는 자산이 되었다.
PromptHub는 **판매자가 프롬프트를 등록·판매하고, 구매자가 탐색·구매·활용**하는 마켓플레이스를 제공한다.

### 주요 기능

- **프롬프트 거래**: 텍스트, 이미지, 개발 코드 등 분야별 최적화된 프롬프트 사고팔기
- **추천 시스템**: 구매자가 구매한 상품을 기반으로 한 상품 추천

## 2. 산출물

| 항목 | 내용 |
| --- | --- |
| 아키텍처 다이어그램 | `Gemini_Generated_Image_66t51d66t51d66t5.png` |
| 와이어프레임 | `PromptHub admin dashboard.html`, `PromptHub main page.html` |
| API 명세서 | API 명세 카탈로그 / Swagger 파일 |

### Git 저장소

- **BE**: https://github.com/prgrms-be-adv-devcourse/beadv6_6_3JMT_BE
- **FE**: https://github.com/prgrms-be-adv-devcourse/beadv6_6_3JMT_FE

## 3. API 명세

> 비고
> - `(UI 필수 X)`: UI 화면 구현이 필수가 아닌 API
> - `(internal)`: 내부 서비스 간 통신용 API
> - **굵게 표시**된 항목은 우선 담당 작업

### 인증 (Auth)

| 메서드 | 경로 | 비고 |
| --- | --- | --- |
| POST | `/auth/signup` | 회원가입 |
| POST | `/auth/login` | 로그인 |
| POST | `/auth/oauth/{provider}` | 소셜 로그인 |
| POST | `/auth/logout` | 로그아웃 |
| POST | `/auth/token/refresh` | 토큰 재발급 |

- JWT 서명·만료 검증

### 사용자 (Users)

| 메서드 | 경로 | 비고 |
| --- | --- | --- |
| GET | `/users/me` | 내 정보 조회 |
| PUT | `/users/me` | 내 정보 수정 |
| DELETE | `/users/me` | 회원 탈퇴 |

### 위시리스트 (Wishlist)

| 메서드 | 경로 | 비고 |
| --- | --- | --- |
| POST | `/wishlists` | **위시리스트 추가** |

### 상품 (Products)

| 메서드 | 경로 | 비고 |
| --- | --- | --- |
| POST | `/products` | 상품 등록 |
| GET | `/products` | 상품 목록 조회 |
| GET | `/products/{productId}` | 상품 상세 조회 |
| PUT | `/products/{productId}` | 상품 수정 |
| DELETE | `/products/{productId}` | 상품 삭제 |
| GET | `/products/{productId}/reviews` | 상품 리뷰 조회 |
| GET | `/products/pending-review` | 검수 대기 상품 조회 |
| PATCH | `/products/{productId}/approve` | 상품 승인 |
| PATCH | `/products/{productId}/reject` | 상품 반려 |
| GET | `/internal/products/{productId}/snapshot` | (internal) 상품 스냅샷 |
| GET | `/internal/products/{productId}/content` | (internal) 상품 콘텐츠 |

### 장바구니 (Cart)

| 메서드 | 경로 | 비고 |
| --- | --- | --- |
| GET | `/cart` | 장바구니 조회 (UI 필수 X) |
| POST | `/cart/products` | 장바구니 상품 추가 |
| DELETE | `/cart/products/{cartProductId}` | 장바구니 상품 삭제 |

### 주문 (Orders)

| 메서드 | 경로 | 비고 |
| --- | --- | --- |
| POST | `/orders` | 주문 생성 |
| GET | `/orders` | 주문 목록 조회 |
| GET | `/orders/{orderId}` | 주문 상세 조회 (UI 필수 X) |
| GET | `/orders/payments` | 주문 결제 내역 조회 |
| GET | `/orders/{orderId}/content/{orderProductId}` | 주문 상품 콘텐츠 조회 |
| POST | `/orders/review` | 주문 리뷰 작성 |
| GET | `/internal/orders/paid` | (internal) 결제 완료 주문 조회 |
| GET | `/admin/orders` | (admin) 주문 목록 조회 |
| GET | `/admin/orders/month` | (admin) 월별 주문 조회 |
| GET | `/admin/orders/weekend` | (admin) 주말 주문 조회 |

### 결제 (Payments)

| 메서드 | 경로 | 비고 |
| --- | --- | --- |
| POST | `/payments/confirm` | 결제 승인 |
| POST | `/payments/{paymentId}/refund` | 환불 |
| POST | `/internal/payments/confirm` | (internal) 결제 승인 |

**결제 이벤트**

| 이벤트 | 비고 |
| --- | --- |
| `payment.approved` | 결제 승인 |
| `payment.canceled` | 결제 취소 |
| **`payment.cancel_failed`** | **결제 취소 실패** |
| `payment.refunded` | 환불 완료 |
| `payment.refund_failed` | 환불 실패 |

### 정산 (Settlements)

| 메서드 | 경로 | 비고 |
| --- | --- | --- |
| GET | `/sellers/me/settlements` | 판매자 정산 목록 조회 |
| GET | `/sellers/me/settlements/summary` | **판매자 정산 요약 조회** |
| GET | `/admin/settlements` | (admin) 정산 목록 조회 |
| GET | `/admin/settlements/summary` | (admin) 정산 요약 조회 |
| GET | `/admin/settlements/{settlementId}` | (admin) 정산 상세 조회 (UI 필수 X) |
| GET | `/admin/settlements/{settlementId}/details` | (admin) 정산 상세 내역 조회 (UI 필수 X) |
| POST | `/admin/settlement-batches` | (admin) 정산 배치 생성 (UI 필수 X) |
| PATCH | `/admin/settlements/{settlementId}/approve` | (admin) 정산 승인 |
| PATCH | `/admin/settlements/{settlementId}/hold` | (admin) 정산 보류 |
| PATCH | `/admin/settlements/{settlementId}/release-hold` | (admin) 정산 보류 해제 |

- 정산 배치 자동 실행 (잡 스케줄링)
