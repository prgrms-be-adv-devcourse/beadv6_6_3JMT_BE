package com.prompthub.product.presentation;

import com.prompthub.product.application.service.ProductCatalogService;
import com.prompthub.product.presentation.dto.ProductListItemResponse;
import com.prompthub.presentation.dto.PageResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
public class ProductController {

	private final ProductCatalogService productCatalogService;

	@GetMapping("/products")
	public PageResponse<ProductListItemResponse> getProducts(
		@RequestParam(defaultValue = "") String q,
		@RequestParam(defaultValue = "all") String category,
		@RequestParam(defaultValue = "popular") String sort,
		@RequestParam(defaultValue = "1") int page,
		@RequestParam(defaultValue = "20") int size
	) {
		return productCatalogService.getProducts(q, category, sort, page, size);
	}
}
