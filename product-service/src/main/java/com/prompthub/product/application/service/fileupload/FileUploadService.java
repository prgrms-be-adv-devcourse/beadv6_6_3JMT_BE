package com.prompthub.product.application.service.fileupload;

import com.prompthub.product.application.gateway.external.ObjectStorageGateway;
import com.prompthub.product.application.gateway.external.ObjectStorageKey;
import com.prompthub.product.application.usecase.fileupload.FileUploadUseCase;
import com.prompthub.product.presentation.dto.request.fileupload.UploadUrlRequest;
import com.prompthub.product.presentation.dto.response.fileupload.UploadUrlResponse;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class FileUploadService implements FileUploadUseCase {

	private final FileUploadPolicy fileUploadPolicy;
	private final ObjectStorageGateway objectStorage;

	@Override
	public UploadUrlResponse createUploadUrl(UUID sellerId, UploadUrlRequest request) {
		UploadPurpose purpose = UploadPurpose.from(request.purpose());
		FileUploadPolicy.UploadDetails uploadDetails =
			fileUploadPolicy.getUploadDetails(purpose, request.productType(), request.fileName());
		// temp key는 만료되는 preview URL 대신 상품 저장·취소 요청에 계속 사용한다.
		ObjectStorageKey tempKey = ObjectStorageKey.newTemp(sellerId, purpose, uploadDetails.extension());

		String presignedPutUrl = objectStorage.createPresignedPutUrl(tempKey.value(), uploadDetails.contentType());
		String presignedGetUrl = objectStorage.createPresignedGetUrl(tempKey.value());

		return new UploadUrlResponse(tempKey.value(), presignedPutUrl, presignedGetUrl);
	}

	@Override
	public void deleteTempObjects(UUID sellerId, List<String> objectKeys) {
		objectKeys.stream()
			.filter(raw -> raw != null && !raw.isBlank())
			.map(ObjectStorageKey::new)
			.filter(ObjectStorageKey::isTemp)
			.filter(key -> key.isOwnedBy(sellerId))
			.forEach(key -> objectStorage.delete(key.value()));
	}
}
