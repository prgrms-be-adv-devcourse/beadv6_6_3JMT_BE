package com.prompthub.user.seller.presentation.dto.response;

import static org.assertj.core.api.Assertions.assertThat;

import com.prompthub.user.seller.application.dto.SellerInfoResult;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class SellerNamesResponseTest {

    @Test
    void of_존재하지_않는_sellerId는_sellerName이_null이다() {
        UUID found = UUID.randomUUID();
        UUID missing = UUID.randomUUID();
        SellerInfoResult result = new SellerInfoResult(found.toString(), "김철수", "", "ACTIVE");

        SellerNamesResponse response = SellerNamesResponse.of(List.of(found, missing), List.of(result));

        assertThat(response.sellers()).extracting(SellerNamesResponse.Item::sellerId)
                .containsExactly(found, missing);
        assertThat(response.sellers()).extracting(SellerNamesResponse.Item::sellerName)
                .containsExactly("김철수", null);
    }

    @Test
    void of_요청_순서와_개수를_그대로_유지한다() {
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        UUID c = UUID.randomUUID();

        SellerNamesResponse response = SellerNamesResponse.of(List.of(a, b, c), List.of());

        assertThat(response.sellers()).hasSize(3);
        assertThat(response.sellers()).extracting(SellerNamesResponse.Item::sellerName)
                .containsOnlyNulls();
    }

    @Test
    void of_중복된_sellerId는_dedupe되어_한_번만_포함된다() {
        UUID id = UUID.randomUUID();
        SellerInfoResult result = new SellerInfoResult(id.toString(), "김철수", "", "ACTIVE");

        SellerNamesResponse response = SellerNamesResponse.of(List.of(id, id, id), List.of(result));

        assertThat(response.sellers()).extracting(SellerNamesResponse.Item::sellerId)
                .containsExactly(id);
    }
}
