package com.prompthub.product.presentation.controller;

import com.prompthub.presentation.dto.ApiResult;
import com.prompthub.product.application.client.StorageClient;
import com.prompthub.product.exception.ProductException;
import com.prompthub.product.exception.enums.ProductErrorCode;
import com.prompthub.product.presentation.dto.request.UploadUrlRequest;
import com.prompthub.product.presentation.dto.response.UploadUrlResponse;
import jakarta.validation.Valid;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/v2/sellers/me/products")
@RequiredArgsConstructor
public class FileUploadController {

    private static final String TEMP_PREFIX = "products/temp/";

    private static final Map<String, String> CONTENT_TYPE_MAP = Map.of(
        "jpg", "image/jpeg",
        "jpeg", "image/jpeg",
        "png", "image/png",
        "gif", "image/gif",
        "webp", "image/webp"
    );

    private static final Map<String, String> DOC_CONTENT_TYPE = Map.of(
        "pptx", "application/vnd.openxmlformats-officedocument.presentationml.presentation",
        "ppt", "application/vnd.ms-powerpoint",
        "xlsx", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
        "xls", "application/vnd.ms-excel"
    );

    private final StorageClient storageClient;

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

    @PostMapping(value = "/images", consumes = "multipart/form-data")
    public ApiResult<Map<String, String>> uploadImage(
        @RequestHeader("X-User-Id") UUID sellerId,
        @RequestHeader("X-User-Role") String role,
        @RequestParam("file") MultipartFile file,
        @RequestParam(value = "productId", required = false) UUID productId,
        @RequestParam(value = "purpose", defaultValue = "thumbnail") String purpose
    ) throws Exception {
        String ext = extractExtension(file.getOriginalFilename());
        String contentType = CONTENT_TYPE_MAP.getOrDefault(ext, "image/jpeg");
        String key = buildKey(productId, purpose, ext);
        storageClient.upload(key, file.getBytes(), contentType);
        String viewUrl = storageClient.generatePresignedDownloadUrl(key);
        return ApiResult.success(Map.of("url", viewUrl));
    }

    @DeleteMapping("/images")
    public ApiResult<Void> deleteTempImages(
        @RequestHeader("X-User-Id") UUID sellerId,
        @RequestHeader("X-User-Role") String role,
        @RequestBody List<String> presignedUrls
    ) {
        presignedUrls.stream()
            .map(this::extractKey)
            .filter(key -> key != null && key.startsWith(TEMP_PREFIX))
            .forEach(storageClient::deleteObject);
        return ApiResult.success(null);
    }

    private String buildKey(UUID productId, String purpose, String ext) {
        String uuid = UUID.randomUUID().toString();
        if (productId != null) {
            return "products/" + productId + "/" + purpose + "/" + uuid + "." + ext;
        }
        return TEMP_PREFIX + purpose + "/" + uuid + "." + ext;
    }

    private String extractExtension(String fileName) {
        if (fileName == null) return "jpg";
        int dot = fileName.lastIndexOf('.');
        return dot >= 0 ? fileName.substring(dot + 1).toLowerCase() : "jpg";
    }

    private String extractKey(String presignedUrl) {
        if (presignedUrl == null || presignedUrl.isBlank()) return null;
        String path = presignedUrl.split("\\?")[0];
        int idx = path.indexOf(".amazonaws.com/");
        return idx >= 0 ? path.substring(idx + ".amazonaws.com/".length()) : presignedUrl;
    }
}
