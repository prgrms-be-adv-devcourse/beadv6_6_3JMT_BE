package com.prompthub.product.application.usecase;

import com.prompthub.product.presentation.dto.request.UploadUrlRequest;
import com.prompthub.product.presentation.dto.response.UploadUrlResponse;
import java.util.List;
import java.util.UUID;

public interface FileUploadUseCase {

	UploadUrlResponse createUploadUrl(UUID sellerId, UploadUrlRequest request);

	void deleteTempObjects(UUID sellerId, List<String> objectKeys);
}
