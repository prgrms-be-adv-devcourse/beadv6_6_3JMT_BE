package com.prompthub.product.presentation.controller;

import com.prompthub.presentation.dto.ApiResult;
import com.prompthub.product.application.client.StorageClient;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/v1/sellers/me/products")
@RequiredArgsConstructor
public class FileUploadController {

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
        @RequestHeader("X-User-Role") String role,
        @RequestParam("file") MultipartFile file,
        @RequestParam(value = "productId", required = false) UUID productId
    ) throws Exception {
        String ext = extractExtension(file.getOriginalFilename());
        String contentType = CONTENT_TYPE_MAP.getOrDefault(ext, "image/jpeg");
        String key = buildKey(productId, ext);
        String url = storageClient.upload(key, file.getBytes(), contentType);
        return ApiResult.success(Map.of("url", url));
    }

    private String buildKey(UUID productId, String ext) {
        String uuid = UUID.randomUUID().toString();
        if (productId != null) {
            return "products/" + productId + "/images/" + uuid + "." + ext;
        }
        return "products/images/" + uuid + "." + ext;
    }

    private String extractExtension(String fileName) {
        if (fileName == null) return "jpg";
        int dot = fileName.lastIndexOf('.');
        return dot >= 0 ? fileName.substring(dot + 1).toLowerCase() : "jpg";
    }
}
