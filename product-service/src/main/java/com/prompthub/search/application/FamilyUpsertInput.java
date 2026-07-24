package com.prompthub.search.application;

import com.prompthub.product.domain.model.entity.Product;
import java.time.LocalDateTime;

public record FamilyUpsertInput(Product onSale, long familySalesCount, double averageRating, LocalDateTime firstPublishedAt) {
}
