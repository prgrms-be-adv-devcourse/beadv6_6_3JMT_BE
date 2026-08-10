package com.prompthub.product.presentation.controller;

import com.prompthub.presentation.dto.ApiResult;
import com.prompthub.product.application.usecase.FileUploadUseCase;
import com.prompthub.product.presentation.dto.request.UploadUrlRequest;
import com.prompthub.product.presentation.dto.response.UploadUrlResponse;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v2/products")
@RequiredArgsConstructor
public class FileUploadController {

	private final FileUploadUseCase fileUploadUseCase;

	@PostMapping("/uploads/presigned-urls")
	public ApiResult<UploadUrlResponse> createUploadUrl(
		@RequestHeader("X-User-Id") UUID sellerId,
		@Valid @RequestBody UploadUrlRequest request
	) {
		return ApiResult.success(fileUploadUseCase.createUploadUrl(sellerId, request));
	}

	@DeleteMapping("/images")
	public ApiResult<Void> deleteTempImages(
		@RequestHeader("X-User-Id") UUID sellerId,
		@RequestBody List<String> objectKeys
	) {
		fileUploadUseCase.deleteTempObjects(sellerId, objectKeys);
		return ApiResult.success(null);
	}
}
