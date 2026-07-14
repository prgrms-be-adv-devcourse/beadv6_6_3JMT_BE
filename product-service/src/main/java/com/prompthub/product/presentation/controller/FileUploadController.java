package com.prompthub.product.presentation.controller;

import com.prompthub.presentation.dto.ApiResult;
import com.prompthub.product.application.client.StorageClient;
import java.util.List;
import java.util.Map;
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

    private final StorageClient storageClient;

    @PostMapping(value = "/images", consumes = "multipart/form-data")
    public ApiResult<Map<String, String>> uploadImage(
        @RequestHeader("X-User-Id") UUID sellerId,
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
