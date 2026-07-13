package com.prompthub.order.application.service.cart;

import com.prompthub.order.application.client.ProductClient;
import com.prompthub.order.application.dto.ProductCartSnapshot;
import com.prompthub.order.domain.model.Cart;
import com.prompthub.order.domain.model.CartProduct;
import com.prompthub.order.domain.repository.CartRepository;
import com.prompthub.order.global.exception.CartException;
import com.prompthub.order.global.exception.ErrorCode;
import com.prompthub.order.presentation.dto.request.AddCartProductRequest;
import com.prompthub.order.presentation.dto.response.AddCartProductResponse;
import com.prompthub.order.presentation.dto.response.CartResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static com.prompthub.order.fixture.OrderFixture.BUYER_ID;
import static com.prompthub.order.fixture.OrderFixture.PRODUCT_AMOUNT_1;
import static com.prompthub.order.fixture.OrderFixture.PRODUCT_AMOUNT_2;
import static com.prompthub.order.fixture.OrderFixture.PRODUCT_ID_1;
import static com.prompthub.order.fixture.OrderFixture.PRODUCT_ID_2;
import static com.prompthub.order.fixture.OrderFixture.PRODUCT_THUMBNAIL_URL;
import static com.prompthub.order.fixture.OrderFixture.PRODUCT_TITLE_1;
import static com.prompthub.order.fixture.OrderFixture.PRODUCT_TITLE_2;
import static com.prompthub.order.fixture.OrderFixture.PRODUCT_TYPE_PROMPT;
import static com.prompthub.order.fixture.OrderFixture.SELLER_ID_1;
import static com.prompthub.order.fixture.OrderFixture.SELLER_ID_2;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;

@ExtendWith(MockitoExtension.class)
class CartServiceTest {

	private static final String SELLER_NICKNAME_1 = "seller-one";
	private static final String SELLER_NICKNAME_2 = "seller-two";
	private static final UUID CART_PRODUCT_ID =
		UUID.fromString("00000000-0000-0000-0000-000000000701");

	@Mock
	private CartRepository cartRepository;

	@Mock
	private ProductClient productClient;

	@InjectMocks
	private CartService cartService;

	@Nested
	@DisplayName("장바구니 조회")
	class GetCart {

		@Test
		@DisplayName("장바구니가 없으면 빈 장바구니 응답을 반환하고 저장하지 않는다")
		void getCart_withoutCart_returnsEmptyResponse() {
			given(cartRepository.findByBuyerIdWithCartProducts(BUYER_ID))
				.willReturn(Optional.empty());

			CartResponse response = cartService.getCart(BUYER_ID);

			assertThat(response.cartId()).isNull();
			assertThat(response.buyerId()).isEqualTo(BUYER_ID);
			assertThat(response.products()).isEmpty();
			assertThat(response.totalAmount()).isZero();
			assertThat(response.totalItemCount()).isZero();
			then(cartRepository).should(never()).save(any());
			then(productClient).shouldHaveNoInteractions();
		}

		@Test
		@DisplayName("최신 상품 스냅샷 기준으로 상품 목록과 총액을 반환한다")
		void getCart_withProducts_calculatesLatestTotalAmount() {
			Cart cart = Cart.create(BUYER_ID);
			CartProduct first = cart.addProduct(PRODUCT_ID_1);
			CartProduct second = cart.addProduct(PRODUCT_ID_2);
			List<ProductCartSnapshot> snapshots = List.of(cartSnapshot1(), cartSnapshot2());

			given(cartRepository.findByBuyerIdWithCartProducts(BUYER_ID))
				.willReturn(Optional.of(cart));
			given(productClient.getCartSnapshots(List.of(PRODUCT_ID_1, PRODUCT_ID_2)))
				.willReturn(snapshots);

			CartResponse response = cartService.getCart(BUYER_ID);

			assertThat(response.cartId()).isEqualTo(cart.getId());
			assertThat(response.buyerId()).isEqualTo(BUYER_ID);
			assertThat(response.totalAmount()).isEqualTo(PRODUCT_AMOUNT_1 + PRODUCT_AMOUNT_2);
			assertThat(response.totalItemCount()).isEqualTo(2);
			assertThat(response.products()).hasSize(2);
			assertThat(response.products().get(0).cartProductId()).isEqualTo(first.getId());
			assertThat(response.products().get(0).productTitle()).isEqualTo(PRODUCT_TITLE_1);
			assertThat(response.products().get(1).cartProductId()).isEqualTo(second.getId());
			assertThat(response.products().get(1).productTitle()).isEqualTo(PRODUCT_TITLE_2);
		}

		@Test
		@DisplayName("상품 서비스 조회가 SYS002로 실패하면 장바구니 상품을 변경하지 않고 예외를 전파한다")
		void getCart_productServiceUnavailable_keepsCartProductsUnchanged() {
			Cart cart = Cart.create(BUYER_ID);
			CartProduct first = cart.addProduct(PRODUCT_ID_1);
			CartProduct second = cart.addProduct(PRODUCT_ID_2);

			given(cartRepository.findByBuyerIdWithCartProducts(BUYER_ID))
				.willReturn(Optional.of(cart));
			given(productClient.getCartSnapshots(List.of(PRODUCT_ID_1, PRODUCT_ID_2)))
				.willThrow(new com.prompthub.exception.BusinessException(ErrorCode.PRODUCT_SERVICE_UNAVAILABLE));

			assertThatThrownBy(() -> cartService.getCart(BUYER_ID))
				.isInstanceOf(com.prompthub.exception.BusinessException.class)
				.satisfies(exception -> assertThat(
					((com.prompthub.exception.BusinessException) exception).getErrorCode()
				).isEqualTo(ErrorCode.PRODUCT_SERVICE_UNAVAILABLE));

			assertThat(cart.getCartProducts())
				.extracting(CartProduct::getId)
				.containsExactly(first.getId(), second.getId());
			then(cartRepository).should(never()).save(any());
		}
	}

	@Nested
	@DisplayName("장바구니 상품 추가")
	class AddCartProduct {

		@Test
		@DisplayName("장바구니가 없으면 생성하고 ON_SALE 상품을 추가한다")
		void addCartProduct_withoutCart_createsCartAndAddsProduct() {
			given(productClient.getCartSnapshot(PRODUCT_ID_1))
				.willReturn(cartSnapshot1());
			given(cartRepository.findByBuyerIdWithCartProducts(BUYER_ID))
				.willReturn(Optional.empty());
			given(cartRepository.save(any(Cart.class)))
				.willAnswer(invocation -> invocation.getArgument(0));

			AddCartProductResponse response = cartService.addCartProduct(
				BUYER_ID,
				new AddCartProductRequest(PRODUCT_ID_1)
			);

			assertThat(response.cartProductId()).isNotNull();
			assertThat(response.productId()).isEqualTo(PRODUCT_ID_1);
			assertThat(response.productTitle()).isEqualTo(PRODUCT_TITLE_1);
			assertThat(response.productAmount()).isEqualTo(PRODUCT_AMOUNT_1);
			assertThat(response.productStatus()).isEqualTo("ON_SALE");

			ArgumentCaptor<Cart> cartCaptor = ArgumentCaptor.forClass(Cart.class);
			then(cartRepository).should().save(cartCaptor.capture());
			assertThat(cartCaptor.getValue().getBuyerId()).isEqualTo(BUYER_ID);
			assertThat(cartCaptor.getValue().getCartProducts()).hasSize(1);
		}

		@Test
		@DisplayName("판매 중이 아닌 상품은 장바구니에 추가할 수 없다")
		void addCartProduct_notOnSale_throwsException() {
			given(productClient.getCartSnapshot(PRODUCT_ID_1))
				.willReturn(new ProductCartSnapshot(
					PRODUCT_ID_1,
					PRODUCT_TITLE_1,
					PRODUCT_TYPE_PROMPT,
					PRODUCT_AMOUNT_1,
					PRODUCT_THUMBNAIL_URL,
					SELLER_ID_1,
					SELLER_NICKNAME_1,
					"STOPPED"
				));

			assertThatThrownBy(() -> cartService.addCartProduct(BUYER_ID, new AddCartProductRequest(PRODUCT_ID_1)))
				.isInstanceOf(CartException.class)
				.satisfies(exception ->
					assertThat(((CartException) exception).getErrorCode()).isEqualTo(ErrorCode.PRODUCT_NOT_ON_SALE)
				);

			then(cartRepository).shouldHaveNoInteractions();
		}

		@Test
		@DisplayName("같은 상품은 중복으로 장바구니에 담을 수 없다")
		void addCartProduct_duplicatedProduct_throwsException() {
			Cart cart = Cart.create(BUYER_ID);
			cart.addProduct(PRODUCT_ID_1);

			given(productClient.getCartSnapshot(PRODUCT_ID_1))
				.willReturn(cartSnapshot1());
			given(cartRepository.findByBuyerIdWithCartProducts(BUYER_ID))
				.willReturn(Optional.of(cart));

			assertThatThrownBy(() -> cartService.addCartProduct(BUYER_ID, new AddCartProductRequest(PRODUCT_ID_1)))
				.isInstanceOf(CartException.class)
				.satisfies(exception ->
					assertThat(((CartException) exception).getErrorCode()).isEqualTo(ErrorCode.CART_ITEM_DUPLICATED)
				);

			then(cartRepository).should(never()).save(any());
		}
	}

	@Nested
	@DisplayName("장바구니 상품 삭제")
	class DeleteCartProduct {

		@Test
		@DisplayName("본인 장바구니 상품이면 삭제한다")
		void deleteCartProduct_owner_success() {
			Cart cart = Cart.create(BUYER_ID);
			CartProduct cartProduct = cart.addProduct(PRODUCT_ID_1);

			given(cartRepository.findCartProductWithCart(cartProduct.getId()))
				.willReturn(Optional.of(cartProduct));

			cartService.deleteCartProduct(BUYER_ID, cartProduct.getId());

			assertThat(cart.getCartProducts()).isEmpty();
			then(cartRepository).should().save(cart);
		}

		@Test
		@DisplayName("장바구니 상품이 없으면 O006 예외가 발생한다")
		void deleteCartProduct_notFound_throwsException() {
			given(cartRepository.findCartProductWithCart(CART_PRODUCT_ID))
				.willReturn(Optional.empty());

			assertThatThrownBy(() -> cartService.deleteCartProduct(BUYER_ID, CART_PRODUCT_ID))
				.isInstanceOf(CartException.class)
				.satisfies(exception ->
					assertThat(((CartException) exception).getErrorCode()).isEqualTo(ErrorCode.CART_PRODUCT_NOT_FOUND)
				);

			then(cartRepository).should(never()).save(any());
		}

		@Test
		@DisplayName("다른 구매자의 장바구니 상품이면 C003 예외가 발생한다")
		void deleteCartProduct_notOwner_throwsException() {
			UUID otherBuyerId = UUID.fromString("00000000-0000-0000-0000-000000000991");
			Cart cart = Cart.create(otherBuyerId);
			CartProduct cartProduct = cart.addProduct(PRODUCT_ID_1);

			given(cartRepository.findCartProductWithCart(cartProduct.getId()))
				.willReturn(Optional.of(cartProduct));

			assertThatThrownBy(() -> cartService.deleteCartProduct(BUYER_ID, cartProduct.getId()))
				.isInstanceOf(CartException.class)
				.satisfies(exception ->
					assertThat(((CartException) exception).getErrorCode()).isEqualTo(ErrorCode.CART_ITEM_FORBIDDEN)
				);

			then(cartRepository).should(never()).save(any());
		}
	}

	private ProductCartSnapshot cartSnapshot1() {
		return new ProductCartSnapshot(
			PRODUCT_ID_1,
			PRODUCT_TITLE_1,
			PRODUCT_TYPE_PROMPT,
			PRODUCT_AMOUNT_1,
			PRODUCT_THUMBNAIL_URL,
			SELLER_ID_1,
			SELLER_NICKNAME_1,
			"ON_SALE"
		);
	}

	private ProductCartSnapshot cartSnapshot2() {
		return new ProductCartSnapshot(
			PRODUCT_ID_2,
			PRODUCT_TITLE_2,
			PRODUCT_TYPE_PROMPT,
			PRODUCT_AMOUNT_2,
			PRODUCT_THUMBNAIL_URL,
			SELLER_ID_2,
			SELLER_NICKNAME_2,
			"ON_SALE"
		);
	}
}
