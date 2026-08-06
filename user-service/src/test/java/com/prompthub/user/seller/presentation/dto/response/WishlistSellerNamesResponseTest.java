package com.prompthub.user.seller.presentation.dto.response;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.UUID;

import com.prompthub.user.seller.application.dto.SellerInfoResult;

import org.junit.jupiter.api.Test;

class WishlistSellerNamesResponseTest {

    @Test
    void of_누락된_판매자는_null로_포함한다() {
        UUID found = UUID.randomUUID();
        UUID missing = UUID.randomUUID();
        SellerInfoResult result = new SellerInfoResult(
                found.toString(), "김철수", "", "ACTIVE");

        WishlistSellerNamesResponse response = WishlistSellerNamesResponse.of(
                List.of(found, missing), List.of(result));

        assertThat(response.sellers())
                .extracting(WishlistSellerNamesResponse.Item::sellerId)
                .containsExactly(found, missing);
        assertThat(response.sellers())
                .extracting(WishlistSellerNamesResponse.Item::sellerName)
                .containsExactly("김철수", null);
    }

    @Test
    void of_중복된_sellerId는_한_번만_포함한다() {
        UUID sellerId = UUID.randomUUID();
        SellerInfoResult result = new SellerInfoResult(
                sellerId.toString(), "김철수", "", "ACTIVE");

        WishlistSellerNamesResponse response = WishlistSellerNamesResponse.of(
                List.of(sellerId, sellerId), List.of(result));

        assertThat(response.sellers())
                .extracting(WishlistSellerNamesResponse.Item::sellerId)
                .containsExactly(sellerId);
    }
}
