package com.prompthub.order.infra.product;

import com.prompthub.order.application.client.ProductClient;
import com.prompthub.order.application.dto.ProductOrderSnapshot;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class StubProductClientTest {

    @Test
    @DisplayName("기본 프로필에서 StubProductClient 빈을 사용할 수 있다")
    void stubProductClientIsAvailableWithDefaultProfile() {
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            context.scan("com.prompthub.order.infra.product");
            context.refresh();

            assertThat(context.getBean(ProductClient.class)).isInstanceOf(StubProductClient.class);
        }
    }

    @Test
    @DisplayName("요청한 상품 ID 목록에 대한 개발용 상품 스냅샷을 반환한다")
    void getOrderSnapshotsReturnsDevelopmentSnapshotsForRequestedProductIds() {
        StubProductClient productClient = new StubProductClient();
        UUID firstProductId = UUID.randomUUID();
        UUID secondProductId = UUID.randomUUID();

        List<ProductOrderSnapshot> snapshots = productClient.getOrderSnapshots(List.of(firstProductId, secondProductId));

        assertThat(snapshots).hasSize(2);
        assertThat(snapshots).extracting(ProductOrderSnapshot::productId)
                .containsExactly(firstProductId, secondProductId);
        assertThat(snapshots).allSatisfy(snapshot -> {
            assertThat(snapshot.sellerId()).isEqualTo(UUID.fromString("11111111-1111-1111-1111-111111111111"));
            assertThat(snapshot.title()).isEqualTo("테스트 상품");
            assertThat(snapshot.productType()).isEqualTo("PROMPT");
            assertThat(snapshot.amount()).isEqualTo(10000);
        });
    }
}
