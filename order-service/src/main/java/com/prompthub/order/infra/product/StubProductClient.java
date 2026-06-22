package com.prompthub.order.infra.product;

import com.prompthub.order.application.client.ProductClient;
import com.prompthub.order.application.dto.ProductContent;
import com.prompthub.order.application.dto.ProductOrderSnapshot;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;

@Component
@Profile({"default", "local", "test"})
public class StubProductClient implements ProductClient {

    private static final UUID STUB_SELLER_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final String STUB_TITLE = "테스트 상품";
    private static final String STUB_PRODUCT_TYPE = "PROMPT";
    private static final int STUB_AMOUNT = 10000;
    private static final String STUB_CONTENT = "테스트 상품 콘텐츠";

    @Override
    public List<ProductOrderSnapshot> getOrderSnapshots(List<UUID> productIds) {
        return productIds.stream()
                .map(productId -> new ProductOrderSnapshot(
                        productId,
                        STUB_SELLER_ID,
                        STUB_TITLE,
                        STUB_PRODUCT_TYPE,
                        STUB_AMOUNT
                ))
                .toList();
    }

    @Override
    public ProductContent getProductContent(UUID productId) {
        return new ProductContent(productId, STUB_CONTENT);
    }
}
