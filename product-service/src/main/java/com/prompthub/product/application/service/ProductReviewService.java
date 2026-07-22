package com.prompthub.product.application.service;

import com.prompthub.product.application.usecase.ProductReviewUseCase;
import com.prompthub.product.domain.model.entity.Product;
import com.prompthub.product.domain.model.entity.Review;
import com.prompthub.product.domain.repository.ProductRepository;
import com.prompthub.product.domain.repository.ReviewRepository;
import com.prompthub.product.exception.ProductException;
import com.prompthub.product.exception.enums.ProductErrorCode;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional
public class ProductReviewService implements ProductReviewUseCase {

	private final ProductRepository productRepository;
	private final ReviewRepository reviewRepository;

	@Override
	public void upsertReview(UUID buyerId, UUID productId, Integer rating) {
		Product anchor = productRepository.findById(productId)
			.orElseThrow(() -> new ProductException(ProductErrorCode.PRODUCT_NOT_FOUND));
		Product root = productRepository.findById(anchor.familyRootId())
			.orElseThrow(() -> new ProductException(ProductErrorCode.PRODUCT_NOT_FOUND));
		reviewRepository.findByUserIdAndProductId(buyerId, root.getId())
			.ifPresentOrElse(
				review -> review.updateRating((short) rating.intValue()),
				() -> reviewRepository.save(Review.create(buyerId, root, (short) rating.intValue()))
			);
	}
}
