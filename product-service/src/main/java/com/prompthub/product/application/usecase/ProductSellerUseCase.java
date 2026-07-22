package com.prompthub.product.application.usecase;

import com.prompthub.product.presentation.dto.request.ProductCreateRequest;
import com.prompthub.product.presentation.dto.request.ProductUpdateRequest;
import com.prompthub.product.presentation.dto.response.ProductCountResponse;
import com.prompthub.product.presentation.dto.response.ProductCreateResponse;
import com.prompthub.product.presentation.dto.response.SellerProductDetailResponse;
import com.prompthub.product.presentation.dto.response.SellerProductListItemResponse;
import java.util.List;
import java.util.UUID;

public interface ProductSellerUseCase {

	ProductCreateResponse createProduct(UUID sellerId, ProductCreateRequest request);

	void updateProduct(UUID sellerId, UUID productId, ProductUpdateRequest request);

	void deleteProduct(UUID sellerId, UUID productId);

	List<SellerProductListItemResponse> getMyProducts(UUID sellerId);

	SellerProductDetailResponse getMyProduct(UUID sellerId, UUID productId);

	void submitForReview(UUID sellerId, UUID productId);

	ProductCountResponse getProductCount(UUID sellerId);
}
