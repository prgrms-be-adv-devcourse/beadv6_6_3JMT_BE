package com.prompthub.user.seller.presentation.dto.response;

import static org.assertj.core.api.Assertions.assertThat;

import com.prompthub.user.seller.application.dto.SellerInfoResult;
import java.lang.reflect.RecordComponent;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class OrderProductSellerNamesResponseTest {

    @Test
    void of_요청_순서대로_이름을_매핑하고_누락된_판매자는_null로_반환한다() {
        UUID found = UUID.randomUUID();
        UUID missing = UUID.randomUUID();
        SellerInfoResult result = new SellerInfoResult(
                found.toString(), "김철수", "https://cdn.example.com/profile.png", "ACTIVE");

        OrderProductSellerNamesResponse response = OrderProductSellerNamesResponse.of(
                List.of(found, missing), List.of(result));

        assertThat(response.sellers()).containsExactly(
                new OrderProductSellerNamesResponse.Item(found, "김철수"),
                new OrderProductSellerNamesResponse.Item(missing, null)
        );
    }

    @Test
    void of_중복된_sellerId는_첫_등장_한_건만_반환한다() {
        UUID sellerId = UUID.randomUUID();
        SellerInfoResult result = new SellerInfoResult(
                sellerId.toString(), "김철수", "", "ACTIVE");

        OrderProductSellerNamesResponse response = OrderProductSellerNamesResponse.of(
                List.of(sellerId, sellerId, sellerId), List.of(result));

        assertThat(response.sellers()).containsExactly(
                new OrderProductSellerNamesResponse.Item(sellerId, "김철수")
        );
    }

    @Test
    void item은_sellerId와_sellerName만_노출한다() {
        assertThat(OrderProductSellerNamesResponse.Item.class.getRecordComponents())
                .extracting(RecordComponent::getName)
                .containsExactly("sellerId", "sellerName");
    }
}
