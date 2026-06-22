package com.prompthub.order.infra.product;

import com.prompthub.order.application.dto.ProductContent;
import com.prompthub.order.application.dto.ProductOrderSnapshot;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

import java.util.List;
import java.util.UUID;

@FeignClient(name = "product-service", path = "/internal/products")
public interface ProductFeignClient {

    @PostMapping("/order-snapshots")
    List<ProductOrderSnapshot> getOrderSnapshots(@RequestBody ProductOrderSnapshotRequest request);

    @GetMapping("/{productId}/content")
    ProductContent getProductContent(@PathVariable UUID productId);
}
