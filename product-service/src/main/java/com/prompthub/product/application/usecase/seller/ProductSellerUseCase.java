package com.prompthub.product.application.usecase.seller;

import com.prompthub.product.presentation.dto.request.product.ProductCreateRequest;
import com.prompthub.product.presentation.dto.request.product.ProductUpdateRequest;
import com.prompthub.product.presentation.dto.response.product.ProductCountResponse;
import com.prompthub.product.presentation.dto.response.product.ProductCreateResponse;
import com.prompthub.product.presentation.dto.response.product.ProductUpdateResponse;
import com.prompthub.product.presentation.dto.response.seller.SellerProductDetailResponse;
import com.prompthub.product.presentation.dto.response.seller.SellerProductListItemResponse;
import com.prompthub.presentation.dto.PageResponse;
import java.util.UUID;

public interface ProductSellerUseCase {

	ProductCreateResponse createProduct(UUID sellerId, ProductCreateRequest request);

	void submitForReview(UUID sellerId, UUID productId);

	ProductUpdateResponse updateProduct(UUID sellerId, UUID productId, ProductUpdateRequest request);

	void deleteProduct(UUID sellerId, UUID productId);

	PageResponse<SellerProductListItemResponse> getMyProducts(UUID sellerId, int page, int size);

	SellerProductDetailResponse getMyProduct(UUID sellerId, UUID productId);

	ProductCountResponse getProductCount(UUID sellerId);
}
