package com.prompthub.product.application.service;

import com.prompthub.product.application.client.SellerClient;
import com.prompthub.product.application.client.SellerInfo;
import com.prompthub.product.application.usecase.ProductSellerUseCase;
import com.prompthub.product.domain.model.entity.Category;
import com.prompthub.product.domain.model.entity.Product;
import com.prompthub.product.domain.model.enums.AmountType;
import com.prompthub.product.domain.repository.ProductRepository;
import com.prompthub.product.exception.ProductException;
import com.prompthub.product.exception.enums.ProductErrorCode;
import com.prompthub.product.presentation.dto.request.ProductCreateRequest;
import com.prompthub.product.presentation.dto.request.ProductUpdateRequest;
import com.prompthub.product.presentation.dto.response.ProductCreateResponse;
import com.prompthub.product.presentation.dto.response.SellerProductListItemResponse;
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

	@Override
	public ProductCreateResponse createProduct(UUID sellerId, ProductCreateRequest request) {
		SellerInfo seller = sellerClient.getSellerInfo(sellerId);
		if (!"ACTIVE".equals(seller.status())) {
			throw new ProductException(ProductErrorCode.SELLER_NOT_ACTIVE);
		}

		Category category = productRepository.findCategoryByCode(request.category())
			.orElseThrow(() -> new ProductException(ProductErrorCode.CATEGORY_NOT_FOUND));

		AmountType amountType = request.amount() == 0 ? AmountType.FREE : AmountType.PAID;
		Product product = Product.create(
			sellerId,
			category,
			request.title(),
			request.desc(),
			request.model(),
			amountType,
			request.amount(),
			request.thumbnailUrl(),
			request.content()
		);

		Product saved = productRepository.save(product);

		return new ProductCreateResponse(
			saved.getId(),
			saved.getSellerId(),
			saved.getName(),
			category.getCode(),
			saved.getProductType(),
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

		AmountType amountType = request.amount() == 0 ? AmountType.FREE : AmountType.PAID;
		boolean isMajor = "MAJOR".equalsIgnoreCase(request.versionType());
		product.update(
			category,
			request.title(),
			request.desc(),
			request.model(),
			amountType,
			request.amount(),
			request.thumbnailUrl(),
			request.content(),
			request.changeReason(),
			isMajor
		);

		productRepository.save(product);
	}

	@Override
	public void deleteProduct(UUID sellerId, String role, UUID productId) {
		Product product = productRepository.findById(productId)
			.orElseThrow(() -> new ProductException(ProductErrorCode.PRODUCT_NOT_FOUND));

		if (!"ADMIN".equals(role) && !product.isOwnedBy(sellerId)) {
			throw new ProductException(ProductErrorCode.PRODUCT_FORBIDDEN);
		}

		product.softDelete();
		productRepository.save(product);
	}

	@Override
	@Transactional(readOnly = true)
	public List<SellerProductListItemResponse> getMyProducts(UUID sellerId) {
		return productRepository.findBySellerId(sellerId).stream()
			.map(SellerProductListItemResponse::from)
			.toList();
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
