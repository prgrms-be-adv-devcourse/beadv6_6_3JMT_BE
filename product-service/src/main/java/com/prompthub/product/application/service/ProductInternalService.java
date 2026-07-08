package com.prompthub.product.application.service;

import com.prompthub.product.application.client.SellerClient;
import com.prompthub.product.application.usecase.ProductInternalUseCase;
import com.prompthub.product.domain.model.entity.Product;
import com.prompthub.product.domain.model.entity.Review;
import com.prompthub.product.domain.repository.ProductRepository;
import com.prompthub.product.domain.repository.ReviewRepository;
import com.prompthub.product.exception.ProductException;
import com.prompthub.product.exception.enums.ProductErrorCode;
import com.prompthub.product.presentation.dto.response.ProductCartSnapshotResponse;
import com.prompthub.product.presentation.dto.response.ProductContentResponse;
import com.prompthub.product.presentation.dto.response.ProductCountResponse;
import com.prompthub.product.presentation.dto.response.ProductOrderSnapshotResponse;
import com.prompthub.product.presentation.dto.response.ProductsByIdsResponse;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ProductInternalService implements ProductInternalUseCase {

	private final ProductRepository productRepository;
	private final ReviewRepository reviewRepository;
	private final SellerClient sellerClient;

	@Override
	public List<ProductsByIdsResponse> getProductsByIds(List<UUID> productIds) {
		return productRepository.findAllByIdIn(productIds).stream()
			.map(p -> new ProductsByIdsResponse(
				p.getId(),
				p.getSellerId(),
				p.getName(),
				p.getAmount(),
				p.getThumbnailUrl(),
				p.getProductType().name(),
				p.getModel() != null ? p.getModel() : "",
				p.getSalesCount(),
				productRepository.getAverageRating(p.getId()),
				p.getStatus().name()
			))
			.toList();
	}

	@Override
	public List<ProductOrderSnapshotResponse> getOrderSnapshots(List<UUID> productIds) {
		return productRepository.findOnSaleByIdIn(productIds).stream()
			.map(ProductOrderSnapshotResponse::from)
			.toList();
	}

	@Override
	public ProductCartSnapshotResponse getCartSnapshot(UUID productId) {
		Product product = productRepository.findById(productId)
			.orElseThrow(() -> new ProductException(ProductErrorCode.PRODUCT_NOT_FOUND));
		String sellerNickname = sellerClient.getSellerInfo(product.getSellerId()).sellerName();
		return ProductCartSnapshotResponse.from(product, sellerNickname);
	}

	@Override
	public List<ProductCartSnapshotResponse> getCartSnapshots(List<UUID> productIds) {
		List<Product> products = productRepository.findOnSaleByIdIn(productIds);
		Map<UUID, String> sellerNicknames = products.stream()
			.map(Product::getSellerId)
			.distinct()
			.collect(Collectors.toMap(id -> id, id -> sellerClient.getSellerInfo(id).sellerName()));
		return products.stream()
			.map(p -> ProductCartSnapshotResponse.from(p, sellerNicknames.get(p.getSellerId())))
			.toList();
	}

	@Override
	public ProductContentResponse getProductContent(UUID productId) {
		Product product = productRepository.findById(productId)
			.orElseThrow(() -> new ProductException(ProductErrorCode.PRODUCT_NOT_FOUND));
		return ProductContentResponse.from(product);
	}

	@Override
	@Transactional
	public void upsertReview(UUID buyerId, UUID productId, Integer rating) {
		Product product = productRepository.findById(productId)
			.orElseThrow(() -> new ProductException(ProductErrorCode.PRODUCT_NOT_FOUND));
		reviewRepository.findByUserIdAndProductId(buyerId, productId)
			.ifPresentOrElse(
				review -> review.updateRating((short) rating.intValue()),
				() -> reviewRepository.save(Review.create(buyerId, product, (short) rating.intValue()))
			);
	}

	@Override
	public ProductCountResponse getProductCount(UUID sellerId) {
		return new ProductCountResponse(sellerId, productRepository.countBySellerId(sellerId));
	}
}
