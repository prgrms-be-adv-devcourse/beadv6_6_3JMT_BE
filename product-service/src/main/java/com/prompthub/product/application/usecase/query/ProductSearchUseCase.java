package com.prompthub.product.application.usecase.query;

import com.prompthub.product.presentation.dto.response.product.ProductListItemResponse;
import com.prompthub.presentation.dto.PageResponse;
import java.util.List;

public interface ProductSearchUseCase {

	PageResponse<ProductListItemResponse> getProducts(String q, String productType, String sort, int page, int size);

	List<String> suggest(String q);
}
