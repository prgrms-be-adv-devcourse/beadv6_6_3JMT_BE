package com.prompthub.order.domain.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class CartTest {

	private static final UUID BUYER_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
	private static final UUID PRODUCT_ID_1 = UUID.fromString("00000000-0000-0000-0000-000000000101");
	private static final UUID PRODUCT_ID_2 = UUID.fromString("00000000-0000-0000-0000-000000000102");
	private static final UUID PRODUCT_ID_3 = UUID.fromString("00000000-0000-0000-0000-000000000103");
	private static final UUID PRODUCT_ID_4 = UUID.fromString("00000000-0000-0000-0000-000000000104");
	private static final UUID UNRELATED_PRODUCT_ID =
		UUID.fromString("00000000-0000-0000-0000-000000000199");

	@Test
	@DisplayName("복구 시 중복 입력과 기존 상품을 제외하고 없는 상품만 추가한다")
	void addProductsIfAbsent_addsOnlyMissingDistinctProducts() {
		Cart cart = Cart.create(BUYER_ID);
		cart.addProduct(PRODUCT_ID_1);

		int added = cart.addProductsIfAbsent(List.of(
			PRODUCT_ID_1,
			PRODUCT_ID_2,
			PRODUCT_ID_2,
			PRODUCT_ID_3,
			PRODUCT_ID_4
		));

		assertThat(added).isEqualTo(3);
		assertThat(cart.getCartProducts())
			.extracting(CartProduct::getProductId)
			.containsExactlyInAnyOrder(PRODUCT_ID_1, PRODUCT_ID_2, PRODUCT_ID_3, PRODUCT_ID_4);
	}

	@Test
	@DisplayName("바로 구매 단건 실패도 빈 장바구니에 상품 한 건을 복구한다")
	void addProductsIfAbsent_restoresSingleDirectPurchase() {
		Cart cart = Cart.create(BUYER_ID);

		int added = cart.addProductsIfAbsent(List.of(PRODUCT_ID_1));

		assertThat(added).isEqualTo(1);
		assertThat(cart.getCartProducts())
			.extracting(CartProduct::getProductId)
			.containsExactly(PRODUCT_ID_1);
	}

	@Test
	@DisplayName("복구 대상이 null 또는 비어 있으면 장바구니를 변경하지 않는다")
	void addProductsIfAbsent_ignoresNullAndEmptyInput() {
		Cart cart = Cart.create(BUYER_ID);
		cart.addProduct(UNRELATED_PRODUCT_ID);

		assertThat(cart.addProductsIfAbsent(null)).isZero();
		assertThat(cart.addProductsIfAbsent(List.of())).isZero();
		assertThat(cart.getCartProducts())
			.extracting(CartProduct::getProductId)
			.containsExactly(UNRELATED_PRODUCT_ID);
	}

	@Test
	@DisplayName("모든 복구 상품이 이미 있으면 중복 항목을 만들지 않는다")
	void addProductsIfAbsent_isIdempotentWhenAllProductsExist() {
		Cart cart = Cart.create(BUYER_ID);
		cart.addProduct(PRODUCT_ID_1);
		cart.addProduct(PRODUCT_ID_2);

		int added = cart.addProductsIfAbsent(List.of(PRODUCT_ID_1, PRODUCT_ID_2, PRODUCT_ID_1));

		assertThat(added).isZero();
		assertThat(cart.getCartProducts())
			.extracting(CartProduct::getProductId)
			.containsExactly(PRODUCT_ID_1, PRODUCT_ID_2);
	}

	@Test
	@DisplayName("상품 제거는 중복 호출에도 안전하고 무관한 상품을 유지한다")
	void removeProductsByProductIds_isIdempotentAndPreservesUnrelatedProducts() {
		Cart cart = Cart.create(BUYER_ID);
		cart.addProduct(PRODUCT_ID_1);
		cart.addProduct(PRODUCT_ID_2);
		cart.addProduct(UNRELATED_PRODUCT_ID);

		cart.removeProductsByProductIds(List.of(PRODUCT_ID_1, PRODUCT_ID_2));
		cart.removeProductsByProductIds(List.of(PRODUCT_ID_1, PRODUCT_ID_2));

		assertThat(cart.getCartProducts())
			.extracting(CartProduct::getProductId)
			.containsExactly(UNRELATED_PRODUCT_ID);
	}
}
