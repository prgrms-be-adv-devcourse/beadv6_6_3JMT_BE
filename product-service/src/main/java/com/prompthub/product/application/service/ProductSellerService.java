package com.prompthub.product.application.service;

import com.prompthub.product.application.client.SellerClient;
import com.prompthub.product.application.client.SellerInfo;
import com.prompthub.product.application.usecase.ProductSellerUseCase;
import com.prompthub.product.domain.model.entity.Category;
import com.prompthub.product.domain.model.entity.Product;
import com.prompthub.product.domain.model.enums.AmountType;
import com.prompthub.product.domain.model.enums.ProductStatus;
import com.prompthub.product.domain.repository.ProductRepository;
import com.prompthub.product.exception.ProductException;
import com.prompthub.product.exception.enums.ProductErrorCode;
import com.prompthub.product.infra.messaging.producer.ProductEventProducer;
import com.prompthub.product.presentation.dto.request.ProductCreateRequest;
import com.prompthub.product.presentation.dto.request.ProductUpdateRequest;
import com.prompthub.product.presentation.dto.response.ProductCreateResponse;
import com.prompthub.product.presentation.dto.response.SellerProductDetailResponse;
import com.prompthub.product.presentation.dto.response.SellerProductListItemResponse;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional
public class ProductSellerService implements ProductSellerUseCase {

	private final ProductRepository productRepository;
	private final SellerClient sellerClient;
	private final ProductEventProducer productEventProducer;

	@Override
	public ProductCreateResponse createProduct(UUID sellerId, ProductCreateRequest request) {
		SellerInfo seller = sellerClient.getSellerInfo(sellerId);
		if (!"ACTIVE".equals(seller.status())) {
			throw new ProductException(ProductErrorCode.SELLER_NOT_ACTIVE);
		}

		Category category = productRepository.findCategoryByCode(request.category())
			.orElseThrow(() -> new ProductException(ProductErrorCode.CATEGORY_NOT_FOUND));

		AmountType amountType = request.amount() == 0 ? AmountType.FREE : AmountType.PAID;
		String productType = request.productType() != null ? request.productType() : "PROMPT";
		Product product = Product.create(
			sellerId,
			category,
			request.title(),
			request.desc(),
			productType,
			request.model(),
			amountType,
			request.amount(),
			request.thumbnailUrl(),
			request.content(),
			request.tags()
		);

		Product saved = productRepository.save(product);

		return new ProductCreateResponse(
			saved.getId(),
			saved.getSellerId(),
			saved.getName(),
			category.getCode(),
			saved.getModel(),
			saved.getDescription(),
			saved.getAmount(),
			saved.getStatus().name(),
			saved.getCreatedAt()
		);
	}

	@Override
	public void updateProduct(UUID sellerId, UUID productId, ProductUpdateRequest request) {
		Product product = getProductForSeller(sellerId, productId);

		Category category = productRepository.findCategoryByCode(request.category())
			.orElseThrow(() -> new ProductException(ProductErrorCode.CATEGORY_NOT_FOUND));

		int previousPrice = product.getAmount();
		AmountType amountType = request.amount() == 0 ? AmountType.FREE : AmountType.PAID;
		boolean isMajor = "MAJOR".equalsIgnoreCase(request.versionType());
		String productType = request.productType() != null ? request.productType() : "PROMPT";
		product.update(
			category,
			request.title(),
			request.desc(),
			productType,
			request.model(),
			amountType,
			request.amount(),
			request.thumbnailUrl(),
			request.content(),
			request.tags(),
			request.changeReason(),
			isMajor
		);

		productRepository.save(product);

		if (previousPrice != product.getAmount()) {
			productEventProducer.publishPriceChanged(product.getId(), previousPrice, product.getAmount());
		}
	}

	@Override
	public void deleteProduct(UUID sellerId, String role, UUID productId) {
		Product product = productRepository.findById(productId)
			.orElseThrow(() -> new ProductException(ProductErrorCode.PRODUCT_NOT_FOUND));

		if (Arrays.stream(role.split(",")).noneMatch("ADMIN"::equals) && !product.isOwnedBy(sellerId)) {
			throw new ProductException(ProductErrorCode.PRODUCT_FORBIDDEN);
		}

		boolean isDraft = product.getStatus() == ProductStatus.DRAFT;
		if (isDraft) {
			product.softDelete();
		} else {
			product.stop();
		}
		productRepository.save(product);

		if (isDraft) {
			productEventProducer.publishDeleted(productId);
		} else {
			productEventProducer.publishStopped(productId);
		}
	}

	@Override
	@Transactional(readOnly = true)
	public List<SellerProductListItemResponse> getMyProducts(UUID sellerId) {
		return productRepository.findBySellerId(sellerId).stream()
			.map(SellerProductListItemResponse::from)
			.toList();
	}

	@Override
	public void submitForReview(UUID sellerId, UUID productId) {
		Product product = getProductForSeller(sellerId, productId);
		product.submitForReview();
		productRepository.save(product);
	}

	@Override
	@Transactional(readOnly = true)
	public SellerProductDetailResponse getMyProduct(UUID sellerId, UUID productId) {
		Product product = getProductForSeller(sellerId, productId);
		return SellerProductDetailResponse.from(product);
	}

	private Product getProductForSeller(UUID sellerId, UUID productId) {
		Product product = productRepository.findById(productId)
			.orElseThrow(() -> new ProductException(ProductErrorCode.PRODUCT_NOT_FOUND));

		if (!product.isOwnedBy(sellerId)) {
			throw new ProductException(ProductErrorCode.PRODUCT_FORBIDDEN);
		}

		return product;
	}
}
