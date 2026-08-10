package com.prompthub.product.application.service.fileupload;

import com.prompthub.product.application.gateway.external.ObjectStorageGateway;
import com.prompthub.product.application.gateway.external.ObjectStorageKey;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Component
@RequiredArgsConstructor
public class TempFilePromoter {

	private final ObjectStorageGateway objectStorage;

	public record PromotedFiles(String thumbnailKey, List<String> imageKeys, String fileKey) {
	}

	// 모든 key를 먼저 검증한 뒤 temp 파일만 영구 경로로 복사한다.
	public PromotedFiles promote(
		String thumbnailKey, List<String> imageKeys, String fileKey, UUID productId, UUID sellerId
	) {
		validateKeys(thumbnailKey, imageKeys, fileKey, sellerId);

		List<String> promotedTempOriginals = new ArrayList<>();
		List<String> newPermanentKeys = new ArrayList<>();
		try {
			String promotedThumbnail = promoteKey(
				thumbnailKey, UploadPurpose.THUMBNAIL, productId, sellerId,
				promotedTempOriginals, newPermanentKeys);
			List<String> promotedImages = promoteKeys(
				imageKeys, UploadPurpose.IMAGE, productId, sellerId,
				promotedTempOriginals, newPermanentKeys);
			String promotedFile = promoteKey(
				fileKey, UploadPurpose.FILE, productId, sellerId,
				promotedTempOriginals, newPermanentKeys);

			registerTransactionCleanup(promotedTempOriginals, newPermanentKeys);
			return new PromotedFiles(promotedThumbnail, promotedImages, promotedFile);
		} catch (RuntimeException e) {
			deleteKeys(newPermanentKeys);
			throw e;
		}
	}

	// 일부 복사 후 검증 실패가 발생하지 않도록 전체 key를 선검증한다.
	private void validateKeys(
		String thumbnailKey, List<String> imageKeys, String fileKey, UUID sellerId
	) {
		validateKey(thumbnailKey, UploadPurpose.THUMBNAIL, sellerId);
		if (imageKeys != null) {
			imageKeys.forEach(key -> validateKey(key, UploadPurpose.IMAGE, sellerId));
		}
		validateKey(fileKey, UploadPurpose.FILE, sellerId);
	}

	// 빈 key는 선택 파일이 없는 경우이므로 검증에서 제외한다.
	private void validateKey(
		String rawKey, UploadPurpose purpose, UUID sellerId
	) {
		if (rawKey != null && !rawKey.isBlank()) {
			new ObjectStorageKey(rawKey).validateFor(sellerId, purpose);
		}
	}

	// 소개 이미지 순서를 유지하며 각 key를 승격한다.
	private List<String> promoteKeys(
		List<String> rawKeys, UploadPurpose purpose, UUID productId, UUID sellerId,
		List<String> promotedTempOriginals, List<String> newPermanentKeys
	) {
		if (rawKeys == null) {
			return null;
		}
		List<String> promotedKeys = new ArrayList<>(rawKeys.size());
		for (String rawKey : rawKeys) {
			promotedKeys.add(promoteKey(
				rawKey, purpose, productId, sellerId, promotedTempOriginals, newPermanentKeys));
		}
		return promotedKeys;
	}

	// 영구 key는 유지하고 temp key만 상품 경로로 복사한다.
	private String promoteKey(
		String rawKey, UploadPurpose purpose, UUID productId, UUID sellerId,
		List<String> promotedTempOriginals, List<String> newPermanentKeys
	) {
		if (rawKey == null || rawKey.isBlank()) {
			return rawKey;
		}
		ObjectStorageKey key = new ObjectStorageKey(rawKey);
		if (!key.isTemp()) {
			return rawKey;
		}
		ObjectStorageKey permanent = key.promote(productId, sellerId, purpose);
		objectStorage.copy(key.value(), permanent.value());
		promotedTempOriginals.add(key.value());
		newPermanentKeys.add(permanent.value());
		return permanent.value();
	}

	// DB commit 시 temp 원본을, rollback 시 이번 영구 복사본을 삭제한다.
	private void registerTransactionCleanup(List<String> tempKeys, List<String> permanentKeys) {
		if (tempKeys.isEmpty()) {
			return;
		}
		if (!TransactionSynchronizationManager.isSynchronizationActive()) {
			deleteKeys(tempKeys);
			return;
		}
		TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
			@Override
			public void afterCompletion(int status) {
				if (status == TransactionSynchronization.STATUS_COMMITTED) {
					deleteKeys(tempKeys);
				} else {
					deleteKeys(permanentKeys);
				}
			}
		});
	}

	// 정리 실패는 storage adapter가 기록하고 나머지 key 정리를 계속한다.
	private void deleteKeys(List<String> keys) {
		keys.forEach(objectStorage::delete);
	}
}
