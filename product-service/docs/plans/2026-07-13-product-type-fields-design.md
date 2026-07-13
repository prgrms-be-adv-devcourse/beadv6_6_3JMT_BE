# 상품유형별 필드 설계 (Spec A: 유형별 필드)

- 작성일: 2026-07-13
- 대상 서비스: product-service
- 상태: 설계 확정 대기(사용자 리뷰)
- 관련 분할 spec: **B(presigned PUT 업로드/heap fix)**, **C(구매자 산출물 전달/gRPC)** — 각각 별도 spec

## 1. 배경 / 목적

상품 유형(`ProductType`: PROMPT / NOTION / PPT / EXCEL)마다 판매자가 입력·업로드하는
필드 구성이 다르다. 현재 `product` 테이블에는 본문 텍스트용 `content` 컬럼만 있어, 유형별
산출물(파일/외부 링크)을 담을 수 없다. 이 spec은 **유형별 필드를 데이터·도메인·API 계층에
추가**하고, 유형에 맞지 않는 필드 조합을 도메인에서 거부하는 것을 다룬다.

`ProductType` enum 4값은 이미 존재한다. 본 작업은 필드/컬럼과 검증을 추가하는 것이다.

## 2. 범위

### 이 spec(A)이 다루는 것
- `product` 테이블에 `file_url`, `external_url` 컬럼 2개 추가
- `Product` 엔티티 필드 및 팩토리(`create`/`update`/`nextVersion`) 시그니처 확장
- 유형별 필드 정합성 **도메인 검증**(불일치 시 에러 거부)
- 생성/수정 요청·응답 DTO 변경 및 유형별 필드 API 계약 문서화
- 에러 코드 추가
- 테스트, docs 동기화

### 이 spec이 다루지 않는 것 (의존/후속)
- **B (별도 spec)**: presigned PUT 업로드 전환. 현재 서버 경유 `byte[]` 업로드가
  product-service heap을 부풀리는 문제(= docker heap 조사 갈래)를 FE 직접 스트리밍 PUT으로
  해결. ppt/excel 산출물 파일의 **실제 업로드 UX**는 B에서 완성된다. A에서는 `file_url`을
  기존 저장 흐름(키 추출·이동)으로 받아 저장하는 것까지만 다룬다.
- **C (별도 spec)**: 구매자 산출물 전달. gRPC `GetProductContent` 응답을 유형별
  산출물(content/file_url/external_url)로 일반화. **proto 변경 → order-service(소비자)
  영향 → 사용자 승인 + order-service 조율 게이트 필요.** order proto 정렬은 사용자가 소유.
  A·B 완료 후 진행.
- **FE 구현**: 유형별 입력 폼 분기는 FE repo(`beadv6_6_3JMT_FE`) 별도 트랙. 이 spec은
  FE가 따라올 수 있도록 요청/응답 필드 계약을 문서화하는 데까지만 관여한다.

### 유형별 필드 완결성
- **PROMPT / NOTION**: A만으로 판매자 생성·수정·조회가 end-to-end 완결(S3 불필요).
- **PPT / EXCEL**: 컬럼·검증·DTO는 A에서 준비되나, 산출물 파일 업로드 UX는 B에 의존.

## 3. 유형별 필드 매트릭스

| 유형 | `content` (기존 TEXT) | `file_url` (신규) | `external_url` (신규) |
|---|---|---|---|
| PROMPT | 필수 | null | null |
| PPT | null | 필수 | null |
| EXCEL | null | 필수 | null |
| NOTION | null | null | 필수 |

- 각 유형은 정확히 한 필드만 사용하고, 나머지는 반드시 null(또는 blank)이어야 한다.
- 유형에 맞지 않는 필드가 채워져 오면 **에러로 거부**한다(§6).

## 4. 데이터 모델

`product` 테이블에 nullable 컬럼 2개 추가:

- `file_url` — TEXT, NULL. **우리가 호스팅하는 산출물 파일의 스토리지 키**를 저장
  (`thumbnail_url`과 동일하게 key 저장, 조회 시 presigned download URL 생성).
- `external_url` — TEXT, NULL. **판매자 외부 노션 링크**를 원문 URL 그대로 저장
  (presign 처리 없음).

product-service는 **JPA `ddl-auto`로 스키마를 생성**한다(로컬 `create-only`, 테스트
`create-drop`). 별도 DDL 스크립트 파일은 없고 엔티티가 스키마 원천이다.

### 마이그레이션
- 두 컬럼 모두 nullable이므로 기존 row는 NULL로 채워지며 backfill 불필요.
- 기존 데이터에 대한 소급 유형 검증은 하지 않는다(신규 생성/수정 요청부터 §6 검증 적용).
- **배포 DB는 사용자가 직접 `ALTER TABLE`을 적용**한다(자동 마이그레이션 없음). 계획 문서가
  실행할 `ALTER TABLE product ADD COLUMN ...` 문을 산출물로 제공한다.
- `docs/erd/schema.md`의 Product 섹션(미러)을 함께 갱신한다.

## 5. 도메인 모델 변경

- `Product`에 `private String fileUrl;`, `private String externalUrl;` 필드 추가.
- 팩토리/변경 메서드 시그니처에 두 값을 `content` 옆에 추가:
  - `create(..., String content, String fileUrl, String externalUrl, List<String> tags)`
  - `update(..., String content, String fileUrl, String externalUrl, ...)`
  - `nextVersion(..., String content, String fileUrl, String externalUrl, ...)`
- 세 메서드 모두 값 대입 전에 유형 검증 메서드를 호출한다.

### 검증 메서드
도메인에 유형별 정합성 검증을 둔다(예: `Product.validateTypeFields(ProductType type,
String content, String fileUrl, String externalUrl)`):

- 유형에 맞는 **필수 필드가 blank/null**이면 → `ProductException(PRODUCT_TYPE_FIELD_MISMATCH)`
- 유형에 맞지 않는 필드가 **채워져 있으면** → `ProductException(PRODUCT_TYPE_FIELD_MISMATCH)`

`submitForReview` 등 기존 도메인 상태 검증이 `ProductException`을 던지는 패턴과 일관되게 둔다.

## 6. 검증 규칙 (불일치 = 거부)

| 유형 | 필수 | 반드시 null |
|---|---|---|
| PROMPT | content | file_url, external_url |
| PPT | file_url | content, external_url |
| EXCEL | file_url | content, external_url |
| NOTION | external_url | content, file_url |

- 위반 시 400 응답, 에러 코드 `PRODUCT_TYPE_FIELD_MISMATCH`.

## 7. 스토리지 처리 (application 서비스)

`ProductSellerService.createProduct`/`updateProduct`에서 유형에 따라 저장 전처리를 분기한다.

- **PPT / EXCEL**: `file_url`은 요청으로 들어온 URL을 기존 `extractKey()` →
  `moveToProductPath()` 흐름으로 처리해 **스토리지 키를 저장**(thumbnail/image와 동일 패턴).
  산출물 파일은 이미지와 섞이지 않도록 **`purpose=file` 키 세그먼트**를 쓴다:
  `products/temp/file/{uuid}.{ext}` → `products/{productId}/file/{uuid}.{ext}`.
  경로 패턴은 기존과 동일하고 `moveToProductPath()`를 그대로 재사용한다. ppt/excel별로
  경로를 더 쪼개지 않는다(확장자 .pptx/.xlsx가 유형을 구분).
- **NOTION**: `external_url`은 외부 링크이므로 **가공 없이 원문 저장**(extractKey/move 없음).
- **PROMPT**: `content`만 저장(기존과 동일), 두 신규 필드는 null.

> 참고: A에서는 `file_url` 소스 URL이 어디서 오는지에 무관하게 기존 저장 플럼빙을 재사용한다.
> 업로드 발급 방식(presigned PUT)으로의 전환은 B에서 다룬다. 서비스의 유형 분기 로직과
> `purpose=file` 규칙은 A에서 확정되고, B는 발급 엔드포인트만 교체한다.

## 8. API 계약 (요청/응답 DTO)

### 요청 DTO — `ProductCreateRequest`, `ProductUpdateRequest`
- `content`의 `@NotBlank` **제거**(전 유형 필수 → PROMPT 전용). 유형별 필수는 도메인이 검증.
- `String fileUrl`, `String externalUrl` 필드 추가(모두 nullable).

### 응답 DTO — `SellerProductDetailResponse` (판매자 상세만)
- `fileUrl`, `externalUrl` 추가.
- `fileUrl`은 `storageClient.generatePresignedDownloadUrl(key)`로 presign,
  `externalUrl`은 원문 그대로 반환.
- **공개 상세(`ProductDetailResponse`)에는 넣지 않는다.** 공개 상세는 구매 전 노출이라
  유료 산출물(file_url/external_url)을 내보내면 안 된다. 구매자에게의 산출물 전달은
  C(gRPC `GetProductContent`)에서 다룬다. 공개 상세는 기존 `createPreviewContent()` 미리보기 유지.

### FE 계약 문서화
`docs/api-spec/product.md`에 유형별 요청/응답 필드 표를 추가해 FE가 유형별 폼 분기를
구현할 수 있게 한다(§3, §6 매트릭스 기준).

## 9. 에러 코드

`ProductErrorCode`에 추가:
- `PRODUCT_TYPE_FIELD_MISMATCH` — 메시지 "상품 유형에 맞지 않는 필드 구성입니다.", 400.
- 코드 문자(P0xx)는 `docs/error-codes.md` PRODUCT 섹션과 함께 확정·동기화한다.

## 10. 테스트 계획

- **도메인**: 유형별 정합성 검증 — 각 유형 정상 케이스 + 필수 누락/불일치 필드 위반 예외.
- **application 서비스**: PPT/EXCEL의 file_url 키 추출·이동 처리, NOTION의 external_url
  원문 저장, PROMPT의 두 필드 null. StorageClient는 목킹.
- **controller**: 유형별 생성/수정 성공(공통 응답 포맷) + 필드 불일치 400.
- Build 기준: `product-service` 기준 `.\gradlew.bat clean build --no-daemon`.

## 11. docs 동기화 대상 (`sync-product-docs`)

- `docs/api-spec/product.md` — 유형별 요청/응답 필드 계약
- `docs/error-codes.md` — `PRODUCT_TYPE_FIELD_MISMATCH`
- `docs/erd/schema.md` — Product 섹션에 `file_url`, `external_url` (배포 DB ALTER는 사용자 적용)
- `docs/domain-glossary/product.md` — 유형별 필드 용어(필요 시)

## 12. 작업 순서 (product-service CLAUDE.md 규칙)

이슈 생성 → 브랜치 생성 → 구현 → 테스트 작성 → docs 동기화 → 규칙 검증 → 커밋 → PR.
