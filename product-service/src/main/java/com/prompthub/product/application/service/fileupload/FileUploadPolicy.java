package com.prompthub.product.application.service.fileupload;

import com.prompthub.product.exception.ProductException;
import com.prompthub.product.exception.enums.ProductErrorCode;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * purpose·productType·확장자 조합 검증과 업로드 content-type 결정을 전담한다.
 */
@Component
public class FileUploadPolicy {

	private static final Map<String, String> IMAGE_CONTENT_TYPE = Map.of(
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

	private static final Map<String, Set<String>> FILE_EXTENSIONS_BY_PRODUCT_TYPE = Map.of(
		"PPT", Set.of("pptx", "ppt"),
		"EXCEL", Set.of("xlsx", "xls")
	);

	public record UploadDetails(String extension, String contentType) {
	}

	public UploadDetails getUploadDetails(UploadPurpose purpose, String productType, String fileName) {
		String extension = extractExtension(fileName);
		String contentType = switch (purpose) {
			case FILE -> resolveFileContentType(productType, extension);
			case THUMBNAIL, IMAGE -> resolveImageContentType(extension);
		};
		return new UploadDetails(extension, contentType);
	}

	private String resolveFileContentType(String productType, String extension) {
		// Map.of()가 만드는 불변 Map은 get(null)에서 바로 NPE를 던지므로 null을 먼저 걸러낸다.
		Set<String> allowed = productType == null ? null : FILE_EXTENSIONS_BY_PRODUCT_TYPE.get(productType);
		if (allowed == null || !allowed.contains(extension)) {
			throw new ProductException(ProductErrorCode.INVALID_UPLOAD_FILE_TYPE);
		}
		return DOC_CONTENT_TYPE.get(extension);
	}

	private String resolveImageContentType(String extension) {
		String contentType = IMAGE_CONTENT_TYPE.get(extension);
		if (contentType == null) {
			throw new ProductException(ProductErrorCode.INVALID_UPLOAD_FILE_TYPE);
		}
		return contentType;
	}

	private String extractExtension(String fileName) {
		int dot = fileName == null ? -1 : fileName.lastIndexOf('.');
		if (dot < 0 || dot == fileName.length() - 1) {
			throw new ProductException(ProductErrorCode.INVALID_UPLOAD_FILE_TYPE);
		}
		return fileName.substring(dot + 1).toLowerCase(Locale.ROOT);
	}
}
