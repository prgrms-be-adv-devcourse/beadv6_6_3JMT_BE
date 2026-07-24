# presigned PUT 업로드 전환 (Spec B) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 판매자 파일 업로드를 서버 경유 multipart(`file.getBytes()`)에서 presigned PUT 발급 방식으로 전환해, product-service가 파일 bytes를 heap에 올리지 않게 한다.

**Architecture:** 백엔드는 presigned PUT URL만 발급(파일 미접촉), FE가 S3로 직접 PUT. 발급 시 확장자↔용도/유형 검증 + content-type 서명. 저장(생성/수정) 경로와 temp→영구 이동은 Spec A 구현을 그대로 쓴다.

**Tech Stack:** Java 21, Spring Boot, AWS SDK v2(S3Presigner), JUnit5 + Mockito + MockMvc(standalone).

## Global Constraints

- 설계 문서: `docs/superpowers/specs/2026-07-13-presigned-put-upload-design.md` (로컬 보관 — git 미추적)
- 이슈 #308, 브랜치 `feat/#306-product-type-fields`(A에서 이어서), PR/close는 A·B·C 완료 후 일괄
- 버킷 private 유지. 업로드=presigned PUT, 읽기=기존 presigned GET
- 발급 엔드포인트 통합 `POST /api/v2/sellers/me/products/uploads`, purpose∈{thumbnail,image,file}
- 확장자 엄격: PPT→`pptx`/`ppt`, EXCEL→`xlsx`/`xls`, 이미지→`jpg/jpeg/png/gif/webp`. 불일치 400
- content-type 서명 강제. 크기 제한은 범위 밖
- key는 항상 temp: `products/temp/{purpose}/{uuid}.{ext}` (생성/수정 시 A의 `moveToProductPath`가 영구 이동)
- 코드 스타일: checkstyle(wildcard/unused import 금지). Build: 루트에서 `.\gradlew.bat :product-service:build --no-daemon`

---

### Task 1: presign 업로드 발급 (포트 + 어댑터 + /uploads 엔드포인트)

새 발급 엔드포인트를 추가한다. 기존 `uploadImage`(multipart)는 이 태스크에선 **그대로 두고**(제거는 Task 2), 서로 공존해 컴파일·테스트가 통과하게 한다.

**Files:**
- Modify: `product-service/src/main/java/com/prompthub/product/application/client/StorageClient.java`
- Modify: `product-service/src/main/java/com/prompthub/product/infra/storage/S3StorageAdapter.java`
- Modify: `product-service/src/main/java/com/prompthub/product/exception/enums/ProductErrorCode.java`
- Create: `product-service/src/main/java/com/prompthub/product/presentation/dto/request/UploadUrlRequest.java`
- Create: `product-service/src/main/java/com/prompthub/product/presentation/dto/response/UploadUrlResponse.java`
- Modify: `product-service/src/main/java/com/prompthub/product/presentation/controller/FileUploadController.java`
- Test: `product-service/src/test/java/com/prompthub/product/presentation/controller/FileUploadControllerTest.java`

**Interfaces:**
- Produces:
  - `StorageClient.generatePresignedUploadUrl(String key, String contentType) -> String`
  - `POST /api/v2/sellers/me/products/uploads` (JSON) → `UploadUrlResponse(String uploadUrl, String fileUrl)`
  - `UploadUrlRequest(String purpose, String fileName, String productType)`
  - `ProductErrorCode.INVALID_UPLOAD_FILE_TYPE` (P008, 400)

- [ ] **Step 1: 포트에 발급 메서드 추가**

`StorageClient.java`에 시그니처 추가(기존 `upload`는 Task 2에서 제거하므로 이번엔 그대로 둔다):

```java
	String generatePresignedUploadUrl(String key, String contentType);
```

- [ ] **Step 2: 어댑터에 presignPutObject 구현**

`S3StorageAdapter.java`에 상수와 메서드를 추가하고 import를 넣는다.

import 추가:
```java
import software.amazon.awssdk.services.s3.presigner.model.PutObjectPresignRequest;
import software.amazon.awssdk.services.s3.presigner.model.PresignedPutObjectRequest;
```

상수(기존 `PRESIGNED_GET_EXPIRATION` 옆):
```java
	private static final Duration PRESIGNED_PUT_EXPIRATION = Duration.ofMinutes(10);
```

메서드(`generatePresignedDownloadUrl` 아래):
```java
	@Override
	public String generatePresignedUploadUrl(String key, String contentType) {
		try {
			PutObjectRequest objectRequest = PutObjectRequest.builder()
				.bucket(awsS3Properties.s3().bucket())
				.key(key)
				.contentType(contentType)
				.build();
			PutObjectPresignRequest presignRequest = PutObjectPresignRequest.builder()
				.signatureDuration(PRESIGNED_PUT_EXPIRATION)
				.putObjectRequest(objectRequest)
				.build();
			PresignedPutObjectRequest presigned = s3Presigner.presignPutObject(presignRequest);
			return presigned.url().toString();
		} catch (Exception e) {
			log.error("S3 presign upload failed key={}: {}", key, e.getMessage(), e);
			throw new ProductException(ProductErrorCode.S3_PRESIGN_FAILED);
		}
	}
```

(`PutObjectRequest`는 이미 import되어 있다.)

- [ ] **Step 3: 에러 코드 추가**

`ProductErrorCode.java`의 `PRODUCT_TYPE_FIELD_MISMATCH`(P007) 아래에 추가:

```java
	INVALID_UPLOAD_FILE_TYPE(HttpStatus.BAD_REQUEST, "P008", "업로드할 수 없는 파일 형식입니다."),
```

- [ ] **Step 4: 요청/응답 DTO 생성**

`UploadUrlRequest.java`:
```java
package com.prompthub.product.presentation.dto.request;

import jakarta.validation.constraints.NotBlank;

public record UploadUrlRequest(
	@NotBlank String purpose,
	@NotBlank String fileName,
	String productType
) {
}
```

`UploadUrlResponse.java`:
```java
package com.prompthub.product.presentation.dto.response;

public record UploadUrlResponse(
	String uploadUrl,
	String fileUrl
) {
}
```

- [ ] **Step 5: 실패 테스트 작성 (/uploads)**

`FileUploadControllerTest.java`에 import와 nested 클래스를 추가한다.

import 추가:
```java
import static org.mockito.ArgumentMatchers.eq;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
```
(`eq`는 이미 import되어 있으면 중복 추가하지 않는다.)

nested 클래스 추가(기존 `UploadImage` 클래스 위 또는 아래):
```java
	@Nested
	@DisplayName("POST /api/v2/sellers/me/products/uploads")
	class CreateUploadUrl {

		@Test
		@DisplayName("PPT 파일은 pptx content-type으로 presigned PUT URL을 발급한다")
		void createUploadUrl_pptxFile() throws Exception {
			given(storageClient.generatePresignedUploadUrl(
				org.mockito.ArgumentMatchers.startsWith("products/temp/file/"),
				eq("application/vnd.openxmlformats-officedocument.presentationml.presentation")))
				.willReturn("https://put-url");
			given(storageClient.generatePresignedDownloadUrl(org.mockito.ArgumentMatchers.anyString()))
				.willReturn("https://get-url");

			mockMvc.perform(post("/api/v2/sellers/me/products/uploads")
					.contentType(MediaType.APPLICATION_JSON)
					.content("{\"purpose\":\"file\",\"fileName\":\"a.pptx\",\"productType\":\"PPT\"}")
					.header("X-User-Id", SELLER_ID.toString())
					.header("X-User-Role", "SELLER"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.uploadUrl").value("https://put-url"))
				.andExpect(jsonPath("$.data.fileUrl").value("https://get-url"));
		}

		@Test
		@DisplayName("이미지는 확장자에 맞는 content-type으로 발급한다")
		void createUploadUrl_image() throws Exception {
			given(storageClient.generatePresignedUploadUrl(
				org.mockito.ArgumentMatchers.startsWith("products/temp/thumbnail/"), eq("image/png")))
				.willReturn("https://put-url");
			given(storageClient.generatePresignedDownloadUrl(org.mockito.ArgumentMatchers.anyString()))
				.willReturn("https://get-url");

			mockMvc.perform(post("/api/v2/sellers/me/products/uploads")
					.contentType(MediaType.APPLICATION_JSON)
					.content("{\"purpose\":\"thumbnail\",\"fileName\":\"t.png\"}")
					.header("X-User-Id", SELLER_ID.toString())
					.header("X-User-Role", "SELLER"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.uploadUrl").value("https://put-url"));
		}

		@Test
		@DisplayName("productType과 확장자가 맞지 않으면 400")
		void createUploadUrl_extMismatch() throws Exception {
			mockMvc.perform(post("/api/v2/sellers/me/products/uploads")
					.contentType(MediaType.APPLICATION_JSON)
					.content("{\"purpose\":\"file\",\"fileName\":\"a.xlsx\",\"productType\":\"PPT\"}")
					.header("X-User-Id", SELLER_ID.toString())
					.header("X-User-Role", "SELLER"))
				.andExpect(status().isBadRequest());
		}

		@Test
		@DisplayName("purpose=file인데 productType이 없으면 400")
		void createUploadUrl_fileWithoutType() throws Exception {
			mockMvc.perform(post("/api/v2/sellers/me/products/uploads")
					.contentType(MediaType.APPLICATION_JSON)
					.content("{\"purpose\":\"file\",\"fileName\":\"a.pptx\"}")
					.header("X-User-Id", SELLER_ID.toString())
					.header("X-User-Role", "SELLER"))
				.andExpect(status().isBadRequest());
		}
	}
```

- [ ] **Step 6: 빌드로 실패 확인**

Run: `Set-Location C:\programmers_prj\beadv6_6_3JMT_BE; .\gradlew.bat :product-service:test --no-daemon --tests "com.prompthub.product.presentation.controller.FileUploadControllerTest"`
Expected: 컴파일 실패(`/uploads` 핸들러 없음) 또는 신규 테스트 FAIL

- [ ] **Step 7: /uploads 핸들러 구현**

`FileUploadController.java`에 문서용 content-type 맵과 발급 핸들러를 추가한다. import 추가:
```java
import com.prompthub.product.exception.ProductException;
import com.prompthub.product.exception.enums.ProductErrorCode;
import com.prompthub.product.presentation.dto.request.UploadUrlRequest;
import com.prompthub.product.presentation.dto.response.UploadUrlResponse;
import jakarta.validation.Valid;
import java.util.Set;
import org.springframework.web.bind.annotation.RequestBody;
```

문서 content-type 맵(기존 `CONTENT_TYPE_MAP` 아래):
```java
    private static final Map<String, String> DOC_CONTENT_TYPE = Map.of(
        "pptx", "application/vnd.openxmlformats-officedocument.presentationml.presentation",
        "ppt", "application/vnd.ms-powerpoint",
        "xlsx", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
        "xls", "application/vnd.ms-excel"
    );
```

핸들러 + 헬퍼:
```java
    @PostMapping("/uploads")
    public ApiResult<UploadUrlResponse> createUploadUrl(
        @RequestHeader("X-User-Id") UUID sellerId,
        @RequestHeader("X-User-Role") String role,
        @Valid @RequestBody UploadUrlRequest request
    ) {
        String ext = extractExtension(request.fileName());
        String contentType = resolveContentType(request.purpose(), request.productType(), ext);
        String key = buildKey(null, request.purpose(), ext);
        String uploadUrl = storageClient.generatePresignedUploadUrl(key, contentType);
        String fileUrl = storageClient.generatePresignedDownloadUrl(key);
        return ApiResult.success(new UploadUrlResponse(uploadUrl, fileUrl));
    }

    private String resolveContentType(String purpose, String productType, String ext) {
        if ("file".equals(purpose)) {
            Set<String> allowed;
            if ("PPT".equals(productType)) {
                allowed = Set.of("pptx", "ppt");
            } else if ("EXCEL".equals(productType)) {
                allowed = Set.of("xlsx", "xls");
            } else {
                throw new ProductException(ProductErrorCode.INVALID_UPLOAD_FILE_TYPE);
            }
            if (!allowed.contains(ext)) {
                throw new ProductException(ProductErrorCode.INVALID_UPLOAD_FILE_TYPE);
            }
            return DOC_CONTENT_TYPE.get(ext);
        }
        if ("thumbnail".equals(purpose) || "image".equals(purpose)) {
            String contentType = CONTENT_TYPE_MAP.get(ext);
            if (contentType == null) {
                throw new ProductException(ProductErrorCode.INVALID_UPLOAD_FILE_TYPE);
            }
            return contentType;
        }
        throw new ProductException(ProductErrorCode.INVALID_UPLOAD_FILE_TYPE);
    }
```

(`buildKey(null, purpose, ext)`는 productId가 null이라 temp 키 `products/temp/{purpose}/{uuid}.{ext}`를 만든다 — 기존 메서드 재사용.)

- [ ] **Step 8: 빌드로 통과 확인**

Run: `Set-Location C:\programmers_prj\beadv6_6_3JMT_BE; .\gradlew.bat :product-service:test :product-service:checkstyleMain :product-service:checkstyleTest --no-daemon`
Expected: 전체 PASS(신규 4개 포함). checkstyle은 기존 proto WARN만.

- [ ] **Step 9: 커밋**

```bash
git add product-service/src/main/java/com/prompthub/product/application/client/StorageClient.java product-service/src/main/java/com/prompthub/product/infra/storage/S3StorageAdapter.java product-service/src/main/java/com/prompthub/product/exception/enums/ProductErrorCode.java product-service/src/main/java/com/prompthub/product/presentation/dto/request/UploadUrlRequest.java product-service/src/main/java/com/prompthub/product/presentation/dto/response/UploadUrlResponse.java product-service/src/main/java/com/prompthub/product/presentation/controller/FileUploadController.java product-service/src/test/java/com/prompthub/product/presentation/controller/FileUploadControllerTest.java
git commit -m "feat: presigned PUT 업로드 발급 엔드포인트(/uploads) 추가"
```

---

### Task 2: 서버 경유 byte 업로드 제거

기존 multipart 업로드 경로를 걷어내 heap 문제의 원인을 없앤다.

**Files:**
- Modify: `product-service/src/main/java/com/prompthub/product/presentation/controller/FileUploadController.java`
- Modify: `product-service/src/main/java/com/prompthub/product/application/client/StorageClient.java`
- Modify: `product-service/src/main/java/com/prompthub/product/infra/storage/S3StorageAdapter.java`
- Test: `product-service/src/test/java/com/prompthub/product/presentation/controller/FileUploadControllerTest.java`

**Interfaces:**
- Consumes: Task 1의 `/uploads`
- Produces: `StorageClient`에서 `upload(byte[])` 제거됨

- [ ] **Step 1: multipart 테스트 3개 삭제**

`FileUploadControllerTest.java`의 `@Nested class UploadImage { ... }` 블록 전체(테스트 3개: `uploadImage_success`, `uploadImage_withProductId`, `uploadImage_s3Failure`)를 삭제한다. `DeleteTempImages`와 Task 1의 `CreateUploadUrl`은 남긴다. 이제 안 쓰는 import(`MockMultipartFile`, `multipart`)를 제거한다.

- [ ] **Step 2: uploadImage 핸들러 삭제**

`FileUploadController.java`에서 `@PostMapping(value = "/images", consumes = "multipart/form-data") ... uploadImage(...)` 메서드 전체를 삭제하고, 안 쓰게 되는 import `org.springframework.web.multipart.MultipartFile`와 `org.springframework.web.bind.annotation.RequestParam`(다른 곳에서 안 쓰면)을 제거한다. `DELETE /images` 핸들러, `CONTENT_TYPE_MAP`, `buildKey`, `extractExtension`, `extractKey`는 유지(/uploads·delete가 쓴다).

- [ ] **Step 3: 포트·어댑터에서 upload(byte[]) 제거**

`StorageClient.java`에서 `String upload(String key, byte[] bytes, String contentType);` 삭제.
`S3StorageAdapter.java`에서 `@Override public String upload(...) { ... }` 메서드 전체와, 이제 안 쓰는 import `software.amazon.awssdk.core.sync.RequestBody`를 삭제.

- [ ] **Step 4: 빌드로 통과 확인**

Run: `Set-Location C:\programmers_prj\beadv6_6_3JMT_BE; .\gradlew.bat :product-service:build --no-daemon`
Expected: BUILD SUCCESSFUL. `upload`/`MultipartFile` 참조가 남아 있으면 컴파일 에러로 드러난다(모두 제거돼야 함).

- [ ] **Step 5: 커밋**

```bash
git add product-service/src/main/java/com/prompthub/product/presentation/controller/FileUploadController.java product-service/src/main/java/com/prompthub/product/application/client/StorageClient.java product-service/src/main/java/com/prompthub/product/infra/storage/S3StorageAdapter.java product-service/src/test/java/com/prompthub/product/presentation/controller/FileUploadControllerTest.java
git commit -m "refactor: 서버 경유 multipart byte 업로드 제거(heap 절감)"
```

---

### Task 3: docs 동기화

**Files:**
- Modify: `docs/api-spec/product.md`
- Modify: `docs/error-codes.md`

- [ ] **Step 1: 대상 섹션 확인**

Run(읽기): `docs/api-spec/product.md`의 판매자 업로드 관련 부분, `docs/error-codes.md`의 PRODUCT 섹션. 기존 표/블록 포맷을 따른다.

- [ ] **Step 2: api-spec에 /uploads 발급 계약 추가**

판매자 API 섹션에 `POST /sellers/me/products/uploads`(요청 `{purpose, fileName, productType?}`, 응답 `{uploadUrl, fileUrl}`)와 "presign 요청 → S3로 직접 PUT → 생성/수정 요청에 fileUrl 포함" 흐름을 추가한다. 기존 multipart `/images` 업로드 설명이 있으면 이 방식으로 대체 표기한다.

- [ ] **Step 3: error-codes.md에 P008 추가**

PRODUCT 섹션 P007 아래에 기존 행 포맷과 동일하게 추가:
```
| `INVALID_UPLOAD_FILE_TYPE` | P008 | 업로드할 수 없는 파일 형식입니다. | 400 |
```

- [ ] **Step 4: 커밋**

```bash
git add docs/api-spec/product.md docs/error-codes.md
git commit -m "docs: presigned PUT 업로드(/uploads) API 및 에러코드 동기화"
```

---

## Self-Review 결과

- **Spec 커버리지**: 발급 엔드포인트→Task1; 확장자·content-type 검증→Task1 Step7; 포트/어댑터 presign→Task1 Step1-2; 에러코드→Task1 Step3; 서버경유 제거→Task2; temp→영구 이동은 A 재사용(무변경); docs→Task3. FE는 계약 문서화(Task3)까지.
- **Placeholder**: 없음(코드 블록 실제 내용).
- **타입 일관성**: `generatePresignedUploadUrl(key, contentType)`, `UploadUrlResponse(uploadUrl, fileUrl)`, `INVALID_UPLOAD_FILE_TYPE`가 Task 전반에서 동일.
- **주의(구현자 확인)**: `FileUploadControllerTest`에 `eq` import가 이미 있으면 중복 추가하지 말 것(Task1 Step5). Task2에서 multipart 관련 import를 지울 때 남은 사용처가 없는지 확인.
