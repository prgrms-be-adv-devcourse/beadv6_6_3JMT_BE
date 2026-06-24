package com.prompthub.order.presentation;

import com.prompthub.order.application.usecase.CartUseCase;
import com.prompthub.order.global.exception.CartException;
import com.prompthub.order.global.exception.ErrorCode;
import com.prompthub.order.global.exception.GlobalExceptionHandler;
import com.prompthub.order.global.web.AuthHeaders;
import com.prompthub.order.global.web.OrderServiceAuthInterceptor;
import com.prompthub.order.presentation.dto.request.AddCartProductRequest;
import com.prompthub.order.presentation.dto.response.AddCartProductResponse;
import com.prompthub.order.presentation.dto.response.CartProductResponse;
import com.prompthub.order.presentation.dto.response.CartResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;
import tools.jackson.databind.ObjectMapper;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static com.prompthub.order.fixture.OrderFixture.BUYER_ID;
import static com.prompthub.order.fixture.OrderFixture.PRODUCT_AMOUNT_1;
import static com.prompthub.order.fixture.OrderFixture.PRODUCT_ID_1;
import static com.prompthub.order.fixture.OrderFixture.PRODUCT_THUMBNAIL_URL;
import static com.prompthub.order.fixture.OrderFixture.PRODUCT_TITLE_1;
import static com.prompthub.order.fixture.OrderFixture.PRODUCT_TYPE_PROMPT;
import static com.prompthub.order.fixture.OrderFixture.SELLER_ID_1;
import static org.hamcrest.Matchers.nullValue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class CartControllerTest {

	private static final UUID CART_ID =
		UUID.fromString("00000000-0000-0000-0000-000000000700");
	private static final UUID CART_PRODUCT_ID =
		UUID.fromString("00000000-0000-0000-0000-000000000701");
	private static final String SELLER_NICKNAME = "seller-one";
	private static final LocalDateTime ADDED_AT = LocalDateTime.of(2026, 6, 22, 10, 0);

	private MockMvc mockMvc;

	private final ObjectMapper objectMapper = new ObjectMapper();

	@Mock
	private CartUseCase cartUseCase;

	@BeforeEach
	void setUp() {
		LocalValidatorFactoryBean validator = new LocalValidatorFactoryBean();
		validator.afterPropertiesSet();

		mockMvc = MockMvcBuilders.standaloneSetup(new CartController(cartUseCase))
			.setControllerAdvice(new GlobalExceptionHandler())
			.addInterceptors(new OrderServiceAuthInterceptor())
			.setValidator(validator)
			.build();
	}

	@Nested
	@DisplayName("장바구니 조회 (GET /api/v1/cart)")
	class GetCart {

		@Test
		@DisplayName("장바구니 조회 성공")
		void getCart_success() throws Exception {
			CartResponse response = new CartResponse(
				CART_ID,
				BUYER_ID,
				List.of(cartProductResponse()),
				PRODUCT_AMOUNT_1,
				1
			);
			when(cartUseCase.getCart(BUYER_ID)).thenReturn(response);

			mockMvc.perform(get("/api/v1/cart")
					.header(AuthHeaders.USER_ID, BUYER_ID.toString())
					.header(AuthHeaders.USER_ROLE, AuthHeaders.USER))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.success").value(true))
				.andExpect(jsonPath("$.message").value("success"))
				.andExpect(jsonPath("$.data.cartId").value(CART_ID.toString()))
				.andExpect(jsonPath("$.data.buyerId").value(BUYER_ID.toString()))
				.andExpect(jsonPath("$.data.products[0].cartProductId").value(CART_PRODUCT_ID.toString()))
				.andExpect(jsonPath("$.data.products[0].productTitle").value(PRODUCT_TITLE_1))
				.andExpect(jsonPath("$.data.totalAmount").value(PRODUCT_AMOUNT_1))
				.andExpect(jsonPath("$.data.totalItemCount").value(1));

			verify(cartUseCase).getCart(BUYER_ID);
		}

		@Test
		@DisplayName("USER 권한과 SELLER 권한을 함께 가진 사용자는 장바구니를 조회할 수 있다")
		void getCart_userWithSellerRole_success() throws Exception {
			CartResponse response = new CartResponse(
				CART_ID,
				BUYER_ID,
				List.of(cartProductResponse()),
				PRODUCT_AMOUNT_1,
				1
			);
			when(cartUseCase.getCart(BUYER_ID)).thenReturn(response);

			mockMvc.perform(get("/api/v1/cart")
					.header(AuthHeaders.USER_ID, BUYER_ID.toString())
					.header(AuthHeaders.USER_ROLE, AuthHeaders.USER + "," + AuthHeaders.SELLER))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.success").value(true));

			verify(cartUseCase).getCart(BUYER_ID);
		}

		@Test
		@DisplayName("X-User-Id 헤더가 없으면 401 Unauthorized")
		void getCart_withoutUserIdHeader_unauthorized() throws Exception {
			mockMvc.perform(get("/api/v1/cart"))
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.success").value(false))
				.andExpect(jsonPath("$.code").value(ErrorCode.INVALID_AUTHENTICATION.getCode()));

			verifyNoInteractions(cartUseCase);
		}

		@Test
		@DisplayName("X-User-Role 헤더가 없으면 401 Unauthorized")
		void getCart_withoutUserRoleHeader_unauthorized() throws Exception {
			mockMvc.perform(get("/api/v1/cart")
					.header(AuthHeaders.USER_ID, BUYER_ID.toString()))
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.success").value(false))
				.andExpect(jsonPath("$.code").value(ErrorCode.INVALID_AUTHENTICATION.getCode()));

			verifyNoInteractions(cartUseCase);
		}

		@Test
		@DisplayName("X-User-Role이 USER가 아니면 403 Forbidden")
		void getCart_nonUserRole_forbidden() throws Exception {
			mockMvc.perform(get("/api/v1/cart")
					.header(AuthHeaders.USER_ID, BUYER_ID.toString())
					.header(AuthHeaders.USER_ROLE, AuthHeaders.ADMIN))
				.andExpect(status().isForbidden())
				.andExpect(jsonPath("$.success").value(false))
				.andExpect(jsonPath("$.code").value(ErrorCode.FORBIDDEN.getCode()));

			verifyNoInteractions(cartUseCase);
		}
	}

	@Nested
	@DisplayName("장바구니 상품 추가 (POST /api/v1/cart/products)")
	class AddCartProduct {

		@Test
		@DisplayName("장바구니 상품 추가 성공")
		void addCartProduct_success() throws Exception {
			AddCartProductRequest request = new AddCartProductRequest(PRODUCT_ID_1);
			AddCartProductResponse response = new AddCartProductResponse(
				CART_PRODUCT_ID,
				PRODUCT_ID_1,
				PRODUCT_TITLE_1,
				PRODUCT_TYPE_PROMPT,
				PRODUCT_AMOUNT_1,
				PRODUCT_THUMBNAIL_URL,
				SELLER_ID_1,
				SELLER_NICKNAME,
				"ON_SALE",
				ADDED_AT
			);
			when(cartUseCase.addCartProduct(BUYER_ID, request)).thenReturn(response);

			mockMvc.perform(post("/api/v1/cart/products")
					.header(AuthHeaders.USER_ID, BUYER_ID.toString())
					.header(AuthHeaders.USER_ROLE, AuthHeaders.USER)
					.contentType(MediaType.APPLICATION_JSON)
					.content(objectMapper.writeValueAsString(request)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.success").value(true))
				.andExpect(jsonPath("$.message").value("success"))
				.andExpect(jsonPath("$.data.cartProductId").value(CART_PRODUCT_ID.toString()))
				.andExpect(jsonPath("$.data.productId").value(PRODUCT_ID_1.toString()))
				.andExpect(jsonPath("$.data.productStatus").value("ON_SALE"));

			verify(cartUseCase).addCartProduct(eq(BUYER_ID), eq(request));
		}

		@Test
		@DisplayName("productId가 없으면 400 Bad Request")
		void addCartProduct_withoutProductId_badRequest() throws Exception {
			mockMvc.perform(post("/api/v1/cart/products")
					.header(AuthHeaders.USER_ID, BUYER_ID.toString())
					.header(AuthHeaders.USER_ROLE, AuthHeaders.USER)
					.contentType(MediaType.APPLICATION_JSON)
					.content("{}"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.success").value(false))
				.andExpect(jsonPath("$.code").value(ErrorCode.INVALID_INPUT_VALUE.getCode()));

			verifyNoInteractions(cartUseCase);
		}

		@Test
		@DisplayName("중복 상품이면 409 Conflict와 C001을 반환한다")
		void addCartProduct_duplicate_conflict() throws Exception {
			AddCartProductRequest request = new AddCartProductRequest(PRODUCT_ID_1);
			doThrow(new CartException(ErrorCode.CART_ITEM_DUPLICATED))
				.when(cartUseCase).addCartProduct(eq(BUYER_ID), eq(request));

			mockMvc.perform(post("/api/v1/cart/products")
					.header(AuthHeaders.USER_ID, BUYER_ID.toString())
					.header(AuthHeaders.USER_ROLE, AuthHeaders.USER)
					.contentType(MediaType.APPLICATION_JSON)
					.content(objectMapper.writeValueAsString(request)))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.success").value(false))
				.andExpect(jsonPath("$.code").value(ErrorCode.CART_ITEM_DUPLICATED.getCode()));
		}

		@Test
		@DisplayName("판매 중이 아닌 상품이면 400 Bad Request와 O003을 반환한다")
		void addCartProduct_notOnSale_badRequest() throws Exception {
			AddCartProductRequest request = new AddCartProductRequest(PRODUCT_ID_1);
			doThrow(new CartException(ErrorCode.PRODUCT_NOT_ON_SALE))
				.when(cartUseCase).addCartProduct(eq(BUYER_ID), eq(request));

			mockMvc.perform(post("/api/v1/cart/products")
					.header(AuthHeaders.USER_ID, BUYER_ID.toString())
					.header(AuthHeaders.USER_ROLE, AuthHeaders.USER)
					.contentType(MediaType.APPLICATION_JSON)
					.content(objectMapper.writeValueAsString(request)))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.success").value(false))
				.andExpect(jsonPath("$.code").value(ErrorCode.PRODUCT_NOT_ON_SALE.getCode()));
		}
	}

	@Nested
	@DisplayName("장바구니 상품 삭제 (DELETE /api/v1/cart/products/{cartProductId})")
	class DeleteCartProduct {

		@Test
		@DisplayName("장바구니 상품 삭제 성공")
		void deleteCartProduct_success() throws Exception {
			mockMvc.perform(delete("/api/v1/cart/products/{cartProductId}", CART_PRODUCT_ID)
					.header(AuthHeaders.USER_ID, BUYER_ID.toString())
					.header(AuthHeaders.USER_ROLE, AuthHeaders.USER))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.success").value(true))
				.andExpect(jsonPath("$.message").value("success"))
				.andExpect(jsonPath("$.data").value(nullValue()));

			verify(cartUseCase).deleteCartProduct(BUYER_ID, CART_PRODUCT_ID);
		}

		@Test
		@DisplayName("cartProductId가 UUID가 아니면 400 Bad Request")
		void deleteCartProduct_invalidCartProductId_badRequest() throws Exception {
			mockMvc.perform(delete("/api/v1/cart/products/not-a-uuid")
					.header(AuthHeaders.USER_ID, BUYER_ID.toString())
					.header(AuthHeaders.USER_ROLE, AuthHeaders.USER))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.success").value(false))
				.andExpect(jsonPath("$.code").value(ErrorCode.INVALID_INPUT_VALUE.getCode()));

			verifyNoInteractions(cartUseCase);
		}

		@Test
		@DisplayName("X-User-Id 헤더가 없으면 401 Unauthorized")
		void deleteCartProduct_withoutUserIdHeader_unauthorized() throws Exception {
			mockMvc.perform(delete("/api/v1/cart/products/{cartProductId}", CART_PRODUCT_ID))
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.success").value(false))
				.andExpect(jsonPath("$.code").value(ErrorCode.INVALID_AUTHENTICATION.getCode()));

			verifyNoInteractions(cartUseCase);
		}

		@Test
		@DisplayName("본인 장바구니 항목이 아니면 403 Forbidden과 C003을 반환한다")
		void deleteCartProduct_notOwner_forbidden() throws Exception {
			doThrow(new CartException(ErrorCode.CART_ITEM_FORBIDDEN))
				.when(cartUseCase).deleteCartProduct(BUYER_ID, CART_PRODUCT_ID);

			mockMvc.perform(delete("/api/v1/cart/products/{cartProductId}", CART_PRODUCT_ID)
					.header(AuthHeaders.USER_ID, BUYER_ID.toString())
					.header(AuthHeaders.USER_ROLE, AuthHeaders.USER))
				.andExpect(status().isForbidden())
				.andExpect(jsonPath("$.success").value(false))
				.andExpect(jsonPath("$.code").value(ErrorCode.CART_ITEM_FORBIDDEN.getCode()));
		}
	}

	private CartProductResponse cartProductResponse() {
		return new CartProductResponse(
			CART_PRODUCT_ID,
			PRODUCT_ID_1,
			PRODUCT_TITLE_1,
			PRODUCT_TYPE_PROMPT,
			PRODUCT_AMOUNT_1,
			PRODUCT_THUMBNAIL_URL,
			SELLER_ID_1,
			SELLER_NICKNAME,
			"ON_SALE",
			ADDED_AT
		);
	}
}
