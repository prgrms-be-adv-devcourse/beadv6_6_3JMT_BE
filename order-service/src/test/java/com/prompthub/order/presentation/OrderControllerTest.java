package com.prompthub.order.presentation;

import com.prompthub.order.application.usecase.OrderUseCase;
import com.prompthub.order.domain.enums.PaymentStatus;
import com.prompthub.order.domain.enums.OrderStatus;
import com.prompthub.order.global.exception.ErrorCode;
import com.prompthub.order.global.exception.GlobalExceptionHandler;
import com.prompthub.order.global.exception.OrderException;
import com.prompthub.order.global.web.AuthHeaders;
import com.prompthub.order.global.web.OrderServiceAuthInterceptor;
import com.prompthub.order.presentation.dto.request.CreateOrderRequest;
import com.prompthub.order.presentation.dto.request.PageRequestParams;
import com.prompthub.order.presentation.dto.response.CreateOrderResponse;
import com.prompthub.order.presentation.dto.response.OrderContentResponse;
import com.prompthub.order.presentation.dto.response.OrderDetailProductResponse;
import com.prompthub.order.presentation.dto.response.OrderDetailResponse;
import com.prompthub.order.presentation.dto.response.OrderListResponse;
import com.prompthub.order.presentation.dto.response.OrderPaymentListResponse;
import com.prompthub.order.presentation.dto.response.OrderProductsResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;
import tools.jackson.databind.ObjectMapper;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static com.prompthub.order.fixture.OrderFixture.*;
import static org.hamcrest.Matchers.nullValue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.doThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class OrderControllerTest {

	private MockMvc mockMvc;

	private final ObjectMapper objectMapper = new ObjectMapper();

	@Mock
	private OrderUseCase orderUseCase;

	@BeforeEach
	void setUp() {
		LocalValidatorFactoryBean validator = new LocalValidatorFactoryBean();
		validator.afterPropertiesSet();

		mockMvc = MockMvcBuilders.standaloneSetup(new OrderController(orderUseCase))
			.setControllerAdvice(new GlobalExceptionHandler())
			.addInterceptors(new OrderServiceAuthInterceptor())
			.setValidator(validator)
			.build();
	}



	@Nested
	@DisplayName("구매 상품 콘텐츠 열람 (GET /api/v1/orders/{orderId}/content/{orderProductId})")
	class GetOrderContent {

		@Nested
		@DisplayName("성공 케이스")
		class Success {

			@Test
			@DisplayName("구매 상품 콘텐츠 열람 성공")
			void getOrderContent_success() throws Exception {
				// given
				String content = "구매 후 확인 가능한 프롬프트 원문";
				OrderContentResponse response = new OrderContentResponse(
					ORDER_ID,
					ORDER_PRODUCT_ID,
					ORDER_NUMBER,
					PRODUCT_ID_1,
					true,
					PRODUCT_TITLE_1,
					content
				);

				when(orderUseCase.getOrderContent(eq(BUYER_ID), eq(ORDER_ID), eq(ORDER_PRODUCT_ID)))
					.thenReturn(response);

				// when & then
				mockMvc.perform(get("/api/v1/orders/{orderId}/content/{orderProductId}", ORDER_ID, ORDER_PRODUCT_ID)
						.header(AuthHeaders.USER_ID, BUYER_ID.toString())
						.header(AuthHeaders.USER_ROLE, AuthHeaders.USER))
					.andExpect(status().isOk())
					.andExpect(jsonPath("$.success").value(true))
					.andExpect(jsonPath("$.message").value("success"))
					.andExpect(jsonPath("$.data.orderId").value(ORDER_ID.toString()))
					.andExpect(jsonPath("$.data.orderProductId").value(ORDER_PRODUCT_ID.toString()))
					.andExpect(jsonPath("$.data.orderNumber").value(ORDER_NUMBER))
					.andExpect(jsonPath("$.data.productId").value(PRODUCT_ID_1.toString()))
					.andExpect(jsonPath("$.data.isDownload").value(true))
					.andExpect(jsonPath("$.data.productTitle").value(PRODUCT_TITLE_1))
					.andExpect(jsonPath("$.data.content").value(content));

				verify(orderUseCase).getOrderContent(eq(BUYER_ID), eq(ORDER_ID), eq(ORDER_PRODUCT_ID));
			}
		}

		@Nested
		@DisplayName("실패 케이스")
		class Failure {

			@Test
			@DisplayName("X-User-Id 헤더가 없으면 401 Unauthorized")
			void getOrderContent_withoutUserIdHeader_unauthorized() throws Exception {
				// when & then
				mockMvc.perform(get("/api/v1/orders/{orderId}/content/{orderProductId}", ORDER_ID, ORDER_PRODUCT_ID))
					.andExpect(status().isUnauthorized())
					.andExpect(jsonPath("$.success").value(false))
					.andExpect(jsonPath("$.code").value(ErrorCode.INVALID_AUTHENTICATION.getCode()));

				verifyNoInteractions(orderUseCase);
			}

			@Test
			@DisplayName("orderId가 UUID 형식이 아니면 400 Bad Request")
			void getOrderContent_invalidOrderId_badRequest() throws Exception {
				// when & then
				mockMvc.perform(get("/api/v1/orders/{orderId}/content/{orderProductId}", "invalid-order-id", ORDER_PRODUCT_ID)
						.header(AuthHeaders.USER_ID, BUYER_ID.toString())
						.header(AuthHeaders.USER_ROLE, AuthHeaders.USER))
					.andExpect(status().isBadRequest())
					.andExpect(jsonPath("$.success").value(false))
					.andExpect(jsonPath("$.code").value(ErrorCode.INVALID_INPUT_VALUE.getCode()));

				verifyNoInteractions(orderUseCase);
			}

			@Test
			@DisplayName("구매 콘텐츠를 열람할 수 없으면 403 Forbidden과 E001을 반환한다")
			void getOrderContent_accessDenied_forbidden() throws Exception {
				// given
				when(orderUseCase.getOrderContent(eq(BUYER_ID), eq(ORDER_ID), eq(ORDER_PRODUCT_ID)))
					.thenThrow(new OrderException(ErrorCode.ORDER_CONTENT_ACCESS_DENIED));

				// when & then
				mockMvc.perform(get("/api/v1/orders/{orderId}/content/{orderProductId}", ORDER_ID, ORDER_PRODUCT_ID)
						.header(AuthHeaders.USER_ID, BUYER_ID.toString())
						.header(AuthHeaders.USER_ROLE, AuthHeaders.USER))
					.andExpect(status().isForbidden())
					.andExpect(jsonPath("$.success").value(false))
					.andExpect(jsonPath("$.code").value(ErrorCode.ORDER_CONTENT_ACCESS_DENIED.getCode()));
			}
		}
	}

	@Nested
	@DisplayName("내 주문 상세 조회 (GET /api/v1/orders/{orderId})")
	class GetOrderDetail {

		@Nested
		@DisplayName("성공 케이스")
		class Success {

			@Test
			@DisplayName("내 주문 상세 조회 성공")
			void getOrderDetail_success() throws Exception {
				// given
				OrderDetailProductResponse product = new OrderDetailProductResponse(
					ORDER_PRODUCT_ID,
					PRODUCT_ID_1,
					SELLER_ID_1,
					PRODUCT_TITLE_1,
					PRODUCT_TYPE_PROMPT,
					"GPT-4",
					PRODUCT_AMOUNT_1,
					OrderStatus.PAID,
					true,
					false
				);
				OrderDetailResponse response = new OrderDetailResponse(
					ORDER_ID,
					ORDER_NUMBER,
					BUYER_ID,
					OrderStatus.PAID,
					List.of(product),
					TOTAL_AMOUNT,
					TOTAL_ITEM_COUNT,
					PAID_AT,
					null,
					null,
					CREATED_AT,
					false
				);

				when(orderUseCase.getOrderDetail(eq(BUYER_ID), eq(ORDER_ID)))
					.thenReturn(response);

				// when & then
				mockMvc.perform(get("/api/v1/orders/{orderId}", ORDER_ID)
						.header(AuthHeaders.USER_ID, BUYER_ID.toString())
						.header(AuthHeaders.USER_ROLE, AuthHeaders.USER))
					.andExpect(status().isOk())
					.andExpect(jsonPath("$.success").value(true))
					.andExpect(jsonPath("$.message").value("success"))
					.andExpect(jsonPath("$.data.orderId").value(ORDER_ID.toString()))
					.andExpect(jsonPath("$.data.orderNumber").value(ORDER_NUMBER))
					.andExpect(jsonPath("$.data.buyerId").value(BUYER_ID.toString()))
					.andExpect(jsonPath("$.data.orderStatus").value("PAID"))
					.andExpect(jsonPath("$.data.totalAmount").value(TOTAL_AMOUNT))
					.andExpect(jsonPath("$.data.totalProductCount").value(TOTAL_ITEM_COUNT))
					.andExpect(jsonPath("$.data.paidAt").value("2026-06-20T12:00:00"))
					.andExpect(jsonPath("$.data.canceledAt").doesNotExist())
					.andExpect(jsonPath("$.data.refundedAt").doesNotExist())
					.andExpect(jsonPath("$.data.createdAt").value("2026-06-20T11:58:00"))
					.andExpect(jsonPath("$.data.hasDownloadProduct").value(false))
					.andExpect(jsonPath("$.data.products[0].orderProductId").value(ORDER_PRODUCT_ID.toString()))
					.andExpect(jsonPath("$.data.products[0].productId").value(PRODUCT_ID_1.toString()))
					.andExpect(jsonPath("$.data.products[0].sellerId").value(SELLER_ID_1.toString()))
					.andExpect(jsonPath("$.data.products[0].productTitleSnapshot").value(PRODUCT_TITLE_1))
					.andExpect(jsonPath("$.data.products[0].productTypeSnapshot").value(PRODUCT_TYPE_PROMPT))
					.andExpect(jsonPath("$.data.products[0].productAmountSnapshot").value(PRODUCT_AMOUNT_1))
					.andExpect(jsonPath("$.data.products[0].orderStatus").value("PAID"))
					.andExpect(jsonPath("$.data.products[0].isContentAccessible").value(true))
					.andExpect(jsonPath("$.data.products[0].download").value(false));

				verify(orderUseCase).getOrderDetail(eq(BUYER_ID), eq(ORDER_ID));
			}
		}

		@Nested
		@DisplayName("실패 케이스")
		class Failure {

			@Test
			@DisplayName("X-User-Id 헤더가 없으면 401 Unauthorized")
			void getOrderDetail_withoutUserIdHeader_unauthorized() throws Exception {
				// when & then
				mockMvc.perform(get("/api/v1/orders/{orderId}", ORDER_ID))
					.andExpect(status().isUnauthorized())
					.andExpect(jsonPath("$.success").value(false))
					.andExpect(jsonPath("$.code").value(ErrorCode.INVALID_AUTHENTICATION.getCode()));

				verifyNoInteractions(orderUseCase);
			}

			@Test
			@DisplayName("orderId가 UUID 형식이 아니면 400 Bad Request")
			void getOrderDetail_invalidOrderId_badRequest() throws Exception {
				// when & then
				mockMvc.perform(get("/api/v1/orders/{orderId}", "invalid-order-id")
						.header(AuthHeaders.USER_ID, BUYER_ID.toString())
						.header(AuthHeaders.USER_ROLE, AuthHeaders.USER))
					.andExpect(status().isBadRequest())
					.andExpect(jsonPath("$.success").value(false))
					.andExpect(jsonPath("$.code").value(ErrorCode.INVALID_INPUT_VALUE.getCode()));

				verifyNoInteractions(orderUseCase);
			}

			@Test
			@DisplayName("주문이 없으면 404 Not Found와 O001을 반환한다")
			void getOrderDetail_orderNotFound_notFound() throws Exception {
				// given
				when(orderUseCase.getOrderDetail(eq(BUYER_ID), eq(ORDER_ID)))
					.thenThrow(new OrderException(ErrorCode.ORDER_NOT_FOUND));

				// when & then
				mockMvc.perform(get("/api/v1/orders/{orderId}", ORDER_ID)
						.header(AuthHeaders.USER_ID, BUYER_ID.toString())
						.header(AuthHeaders.USER_ROLE, AuthHeaders.USER))
					.andExpect(status().isNotFound())
					.andExpect(jsonPath("$.success").value(false))
					.andExpect(jsonPath("$.code").value(ErrorCode.ORDER_NOT_FOUND.getCode()));
			}

			@Test
			@DisplayName("본인 주문이 아니면 403 Forbidden과 A004를 반환한다")
			void getOrderDetail_notOwner_forbidden() throws Exception {
				// given
				when(orderUseCase.getOrderDetail(eq(BUYER_ID), eq(ORDER_ID)))
					.thenThrow(new OrderException(ErrorCode.FORBIDDEN));

				// when & then
				mockMvc.perform(get("/api/v1/orders/{orderId}", ORDER_ID)
						.header(AuthHeaders.USER_ID, BUYER_ID.toString())
						.header(AuthHeaders.USER_ROLE, AuthHeaders.USER))
					.andExpect(status().isForbidden())
					.andExpect(jsonPath("$.success").value(false))
					.andExpect(jsonPath("$.code").value(ErrorCode.FORBIDDEN.getCode()));
			}
		}
	}

	@Nested
	@DisplayName("주문 생성 (POST /api/v1/orders)")
	class CreateOrder {

		@Nested
		@DisplayName("성공 케이스")
		class Success {

			@Test
			@DisplayName("주문 생성 성공")
			void createOrder_success() throws Exception {
				// given
				UUID orderId = UUID.fromString("33333333-3333-3333-3333-333333333333");
				UUID orderProductId1 = UUID.fromString("44444444-4444-4444-4444-444444444441");
				UUID orderProductId2 = UUID.fromString("44444444-4444-4444-4444-444444444442");

				CreateOrderRequest request = createOrderRequest();

				OrderProductsResponse productResponse1 = new OrderProductsResponse(
					orderProductId1,
					PRODUCT_ID_1,
					SELLER_ID_1,
					PRODUCT_TITLE_1,
					PRODUCT_TYPE_PROMPT,
					"GPT-4",
					PRODUCT_AMOUNT_1,
					OrderStatus.PENDING
				);
				OrderProductsResponse productResponse2 = new OrderProductsResponse(
					orderProductId2,
					PRODUCT_ID_2,
					SELLER_ID_2,
					PRODUCT_TITLE_2,
					PRODUCT_TYPE_PROMPT,
					"GPT-4",
					PRODUCT_AMOUNT_2,
					OrderStatus.PENDING
				);

				CreateOrderResponse response = new CreateOrderResponse(
					orderId,
					ORDER_NUMBER,
					BUYER_ID,
					OrderStatus.PENDING,
					List.of(productResponse1, productResponse2),
					TOTAL_AMOUNT,
					LocalDateTime.of(2026, 6, 19, 10, 0),
					null
				);

				when(orderUseCase.createOrder(eq(BUYER_ID), eq(request)))
					.thenReturn(response);

				// when & then
				mockMvc.perform(post("/api/v1/orders")
						.header(AuthHeaders.USER_ID, BUYER_ID.toString())
						.header(AuthHeaders.USER_ROLE, AuthHeaders.USER)
						.contentType(MediaType.APPLICATION_JSON)
						.content(objectMapper.writeValueAsString(request)))
					.andExpect(status().isOk())
					.andExpect(jsonPath("$.success").value(true))
					.andExpect(jsonPath("$.message").value("success"))
					.andExpect(jsonPath("$.data.orderId").value(orderId.toString()))
					.andExpect(jsonPath("$.data.orderNumber").value(ORDER_NUMBER))
					.andExpect(jsonPath("$.data.buyerId").value(BUYER_ID.toString()))
					.andExpect(jsonPath("$.data.orderStatus").value("PENDING"))
					.andExpect(jsonPath("$.data.totalAmount").value(TOTAL_AMOUNT))
					.andExpect(jsonPath("$.data.products[0].orderProductId").value(orderProductId1.toString()))
					.andExpect(jsonPath("$.data.products[0].productId").value(PRODUCT_ID_1.toString()))
					.andExpect(jsonPath("$.data.products[0].sellerId").value(SELLER_ID_1.toString()))
					.andExpect(jsonPath("$.data.products[0].productTitleSnapshot").value(PRODUCT_TITLE_1))
					.andExpect(jsonPath("$.data.products[0].productTypeSnapshot").value(PRODUCT_TYPE_PROMPT))
					.andExpect(jsonPath("$.data.products[0].productModelSnapshot").value("GPT-4"))
					.andExpect(jsonPath("$.data.products[0].productAmountSnapshot").value(PRODUCT_AMOUNT_1))
					.andExpect(jsonPath("$.data.products[0].orderStatus").value("PENDING"))
					.andExpect(jsonPath("$.data.products[1].orderProductId").value(orderProductId2.toString()))
					.andExpect(jsonPath("$.data.products[1].productId").value(PRODUCT_ID_2.toString()))
					.andExpect(jsonPath("$.data.products[1].sellerId").value(SELLER_ID_2.toString()))
					.andExpect(jsonPath("$.data.products[1].productTitleSnapshot").value(PRODUCT_TITLE_2))
					.andExpect(jsonPath("$.data.products[1].productTypeSnapshot").value(PRODUCT_TYPE_PROMPT))
					.andExpect(jsonPath("$.data.products[1].productModelSnapshot").value("GPT-4"))
					.andExpect(jsonPath("$.data.products[1].productAmountSnapshot").value(PRODUCT_AMOUNT_2))
					.andExpect(jsonPath("$.data.products[1].orderStatus").value("PENDING"));

				verify(orderUseCase).createOrder(eq(BUYER_ID), eq(request));
			}

			@Test
			@DisplayName("USER 권한과 SELLER 권한을 함께 가진 사용자는 주문을 생성할 수 있다")
			void createOrder_userWithSellerRole_success() throws Exception {
				UUID orderId = UUID.fromString("33333333-3333-3333-3333-333333333333");
				CreateOrderRequest request = createOrderRequest();
				CreateOrderResponse response = new CreateOrderResponse(
					orderId,
					ORDER_NUMBER,
					BUYER_ID,
					OrderStatus.PENDING,
					List.of(),
					TOTAL_AMOUNT,
					LocalDateTime.of(2026, 6, 19, 10, 0),
					null
				);

				when(orderUseCase.createOrder(eq(BUYER_ID), eq(request)))
					.thenReturn(response);

				mockMvc.perform(post("/api/v1/orders")
						.header(AuthHeaders.USER_ID, BUYER_ID.toString())
						.header(AuthHeaders.USER_ROLE, AuthHeaders.USER + "," + AuthHeaders.SELLER)
						.contentType(MediaType.APPLICATION_JSON)
						.content(objectMapper.writeValueAsString(request)))
					.andExpect(status().isOk())
					.andExpect(jsonPath("$.success").value(true))
					.andExpect(jsonPath("$.data.orderId").value(orderId.toString()));

				verify(orderUseCase).createOrder(eq(BUYER_ID), eq(request));
			}
		}

		@Nested
		@DisplayName("실패 케이스")
		class Failure {

			@Test
			@DisplayName("X-User-Id 헤더가 없으면 401 Unauthorized")
			void createOrder_withoutUserIdHeader_unauthorized() throws Exception {
				// given
				CreateOrderRequest request = createOrderRequest();

				// when & then
				mockMvc.perform(post("/api/v1/orders")
						.contentType(MediaType.APPLICATION_JSON)
						.content(objectMapper.writeValueAsString(request)))
					.andExpect(status().isUnauthorized())
					.andExpect(jsonPath("$.success").value(false))
					.andExpect(jsonPath("$.code").value(ErrorCode.INVALID_AUTHENTICATION.getCode()));
			}

			@Test
			@DisplayName("X-User-Role 헤더가 없으면 401 Unauthorized")
			void createOrder_withoutUserRoleHeader_unauthorized() throws Exception {
				CreateOrderRequest request = createOrderRequest();

				mockMvc.perform(post("/api/v1/orders")
						.header(AuthHeaders.USER_ID, BUYER_ID.toString())
						.contentType(MediaType.APPLICATION_JSON)
						.content(objectMapper.writeValueAsString(request)))
					.andExpect(status().isUnauthorized())
					.andExpect(jsonPath("$.success").value(false))
					.andExpect(jsonPath("$.code").value(ErrorCode.INVALID_AUTHENTICATION.getCode()));

				verifyNoInteractions(orderUseCase);
			}

			@Test
			@DisplayName("X-User-Role이 USER가 아니면 403 Forbidden")
			void createOrder_nonUserRole_forbidden() throws Exception {
				CreateOrderRequest request = createOrderRequest();

				mockMvc.perform(post("/api/v1/orders")
						.header(AuthHeaders.USER_ID, BUYER_ID.toString())
						.header(AuthHeaders.USER_ROLE, AuthHeaders.SELLER)
						.contentType(MediaType.APPLICATION_JSON)
						.content(objectMapper.writeValueAsString(request)))
					.andExpect(status().isForbidden())
					.andExpect(jsonPath("$.success").value(false))
					.andExpect(jsonPath("$.code").value(ErrorCode.FORBIDDEN.getCode()));

				verifyNoInteractions(orderUseCase);
			}

			@Test
			@DisplayName("X-User-Id 헤더가 UUID 형식이 아니면 400 Bad Request")
			void createOrder_invalidUserIdHeader_badRequest() throws Exception {
				// given
				CreateOrderRequest request = createOrderRequest();

				// when & then
				mockMvc.perform(post("/api/v1/orders")
						.header(AuthHeaders.USER_ID, "invalid-uuid")
						.header(AuthHeaders.USER_ROLE, AuthHeaders.USER)
						.contentType(MediaType.APPLICATION_JSON)
						.content(objectMapper.writeValueAsString(request)))
					.andExpect(status().isBadRequest());
			}

			@Test
			@DisplayName("RequestBody가 없으면 400 Bad Request")
			void createOrder_withoutRequestBody_badRequest() throws Exception {
				// given
				// when & then
				mockMvc.perform(post("/api/v1/orders")
						.header(AuthHeaders.USER_ID, BUYER_ID.toString())
						.header(AuthHeaders.USER_ROLE, AuthHeaders.USER)
						.contentType(MediaType.APPLICATION_JSON))
					.andExpect(status().isBadRequest());
			}

			@Test
			@DisplayName("productIds가 비어 있으면 400 Bad Request")
			void createOrder_emptyProductIds_badRequest() throws Exception {
				// given
				CreateOrderRequest request = createOrderRequestWithEmptyProductIds();

				// when & then
				mockMvc.perform(post("/api/v1/orders")
						.header(AuthHeaders.USER_ID, BUYER_ID.toString())
						.header(AuthHeaders.USER_ROLE, AuthHeaders.USER)
						.contentType(MediaType.APPLICATION_JSON)
						.content(objectMapper.writeValueAsString(request)))
					.andExpect(status().isBadRequest());
			}
		}
	}

	@Nested
	@DisplayName("내 주문 목록 조회 (GET /api/v1/orders)")
	class GetOrders {

		@Nested
		@DisplayName("성공 케이스")
		class Success {

			@Test
			@DisplayName("내 주문 목록 조회 성공")
			void getOrders_success() throws Exception {
				// given
				OrderListResponse order = new OrderListResponse(
					ORDER_ID,
					ORDER_PRODUCT_ID,
					PRODUCT_ID_1,
					OrderStatus.PAID,
					true,
					PRODUCT_TYPE_PROMPT,
					PRODUCT_TITLE_1,
					PRODUCT_MODEL,
					4.5,
					PAID_AT,
					CREATED_AT
				);
				Page<OrderListResponse> response = new PageImpl<>(List.of(order), PageRequest.of(0, 20), 1);
				PageRequestParams request = new PageRequestParams(
					1,
					20,
					OrderStatus.PAID,
					LocalDate.of(2026, 6, 1),
					LocalDate.of(2026, 6, 30)
				);

				when(orderUseCase.getOrders(eq(BUYER_ID), eq(request)))
					.thenReturn(response);

				// when & then
				mockMvc.perform(get("/api/v1/orders")
						.header(AuthHeaders.USER_ID, BUYER_ID.toString())
						.header(AuthHeaders.USER_ROLE, AuthHeaders.USER)
						.param("page", "1")
						.param("size", "20")
						.param("status", "PAID")
						.param("from", "2026-06-01")
						.param("to", "2026-06-30"))
					.andExpect(status().isOk())
					.andExpect(jsonPath("$.success").value(true))
					.andExpect(jsonPath("$.message").value("success"))
					.andExpect(jsonPath("$.data[0].orderId").value(ORDER_ID.toString()))
					.andExpect(jsonPath("$.data[0].orderProductId").value(ORDER_PRODUCT_ID.toString()))
					.andExpect(jsonPath("$.data[0].productId").value(PRODUCT_ID_1.toString()))
					.andExpect(jsonPath("$.data[0].orderStatus").value("PAID"))
					.andExpect(jsonPath("$.data[0].isRefund").value(true))
					.andExpect(jsonPath("$.data[0].productType").value(PRODUCT_TYPE_PROMPT))
					.andExpect(jsonPath("$.data[0].title").value(PRODUCT_TITLE_1))
					.andExpect(jsonPath("$.data[0].model").value(PRODUCT_MODEL))
					.andExpect(jsonPath("$.data[0].rating").value(4.5))
					// .andExpect(jsonPath("$.data[0].thumbnailUrl").value(PRODUCT_THUMBNAIL_URL))
					.andExpect(jsonPath("$.data[0].paidAt").value("2026-06-20T12:00:00"))
					.andExpect(jsonPath("$.data[0].createdAt").value("2026-06-20T11:58:00"))
					.andExpect(jsonPath("$.meta.page").value(1))
					.andExpect(jsonPath("$.meta.size").value(20))
					.andExpect(jsonPath("$.meta.total").value(1))
					.andExpect(jsonPath("$.meta.hasNext").value(false));

				verify(orderUseCase).getOrders(eq(BUYER_ID), eq(request));
			}

			@Test
			@DisplayName("내 주문 목록 조회 응답은 rating이 null이어도 정상이다")
			void getOrders_nullRating_success() throws Exception {
				// given
				OrderListResponse order = new OrderListResponse(
					ORDER_ID,
					ORDER_PRODUCT_ID,
					PRODUCT_ID_1,
					OrderStatus.PAID,
					true,
					PRODUCT_TYPE_PROMPT,
					PRODUCT_TITLE_1,
					PRODUCT_MODEL,
					null,
					PAID_AT,
					CREATED_AT
				);
				Page<OrderListResponse> response = new PageImpl<>(List.of(order), PageRequest.of(0, 20), 1);
				PageRequestParams request = new PageRequestParams(1, 20, null, null, null);

				when(orderUseCase.getOrders(eq(BUYER_ID), eq(request)))
					.thenReturn(response);

				// when & then
				mockMvc.perform(get("/api/v1/orders")
						.header(AuthHeaders.USER_ID, BUYER_ID.toString())
						.header(AuthHeaders.USER_ROLE, AuthHeaders.USER))
					.andExpect(status().isOk())
					.andExpect(jsonPath("$.data[0].rating").doesNotExist());
			}
		}

		@Nested
		@DisplayName("실패 케이스")
		class Failure {

			@Test
			@DisplayName("날짜 형식이 잘못되면 400 Bad Request와 V001을 반환한다")
			void getOrders_invalidDate_badRequest() throws Exception {
				// when & then
				mockMvc.perform(get("/api/v1/orders")
						.header(AuthHeaders.USER_ID, BUYER_ID.toString())
						.header(AuthHeaders.USER_ROLE, AuthHeaders.USER)
						.param("from", "2026/06/01"))
					.andExpect(status().isBadRequest())
					.andExpect(jsonPath("$.success").value(false))
					.andExpect(jsonPath("$.code").value(ErrorCode.INVALID_INPUT_VALUE.getCode()));

				verifyNoInteractions(orderUseCase);
			}

			@Test
			@DisplayName("size가 100을 초과하면 400 Bad Request와 V001을 반환한다")
			void getOrders_sizeOverLimit_badRequest() throws Exception {
				// when & then
				mockMvc.perform(get("/api/v1/orders")
						.header(AuthHeaders.USER_ID, BUYER_ID.toString())
						.header(AuthHeaders.USER_ROLE, AuthHeaders.USER)
						.param("size", "101"))
					.andExpect(status().isBadRequest())
					.andExpect(jsonPath("$.success").value(false))
					.andExpect(jsonPath("$.code").value(ErrorCode.INVALID_INPUT_VALUE.getCode()));

				verifyNoInteractions(orderUseCase);
			}

			@Test
			@DisplayName("from이 to보다 늦으면 400 Bad Request와 V001을 반환한다")
			void getOrders_fromAfterTo_badRequest() throws Exception {
				// when & then
				mockMvc.perform(get("/api/v1/orders")
						.header(AuthHeaders.USER_ID, BUYER_ID.toString())
						.header(AuthHeaders.USER_ROLE, AuthHeaders.USER)
						.param("from", "2026-06-30")
						.param("to", "2026-06-01"))
					.andExpect(status().isBadRequest())
					.andExpect(jsonPath("$.success").value(false))
					.andExpect(jsonPath("$.code").value(ErrorCode.INVALID_INPUT_VALUE.getCode()));

				verifyNoInteractions(orderUseCase);
			}
		}
	}

	@Nested
	@DisplayName("내 결제 내역 조회 (GET /api/v1/orders/payments)")
	class GetOrderPayments {

		@Nested
		@DisplayName("성공 케이스")
		class Success {

			@Test
			@DisplayName("내 결제 내역 조회 성공")
			void getOrderPayments_success() throws Exception {
				// given
				OrderPaymentListResponse payment = new OrderPaymentListResponse(
					ORDER_ID,
					ORDER_PRODUCT_ID,
					PAYMENT_ID,
					PaymentStatus.PAID,
					false,
					PRODUCT_TYPE_PROMPT,
					PRODUCT_TITLE_1,
					PRODUCT_AMOUNT_1,
					PAID_AT
				);
				Page<OrderPaymentListResponse> response = new PageImpl<>(List.of(payment), PageRequest.of(0, 20), 1);
				PageRequestParams request = new PageRequestParams(1, 20, null, null, null);

				when(orderUseCase.getOrderPayments(eq(BUYER_ID), eq(request)))
					.thenReturn(response);

				// when & then
				mockMvc.perform(get("/api/v1/orders/payments")
						.header(AuthHeaders.USER_ID, BUYER_ID.toString())
						.header(AuthHeaders.USER_ROLE, AuthHeaders.USER)
						.param("page", "1")
						.param("size", "20"))
					.andExpect(status().isOk())
					.andExpect(jsonPath("$.success").value(true))
					.andExpect(jsonPath("$.message").value("success"))
					.andExpect(jsonPath("$.data[0].orderId").value(ORDER_ID.toString()))
					.andExpect(jsonPath("$.data[0].orderProductId").value(ORDER_PRODUCT_ID.toString()))
					.andExpect(jsonPath("$.data[0].paymentId").value(PAYMENT_ID.toString()))
					.andExpect(jsonPath("$.data[0].paymentStatus").value("PAID"))
					.andExpect(jsonPath("$.data[0].isRefund").value(false))
					.andExpect(jsonPath("$.data[0].productType").value(PRODUCT_TYPE_PROMPT))
					.andExpect(jsonPath("$.data[0].title").value(PRODUCT_TITLE_1))
					.andExpect(jsonPath("$.data[0].amount").value(PRODUCT_AMOUNT_1))
					.andExpect(jsonPath("$.data[0].paidAt").value("2026-06-20T12:00:00"))
					.andExpect(jsonPath("$.meta.page").value(1))
					.andExpect(jsonPath("$.meta.size").value(20))
					.andExpect(jsonPath("$.meta.total").value(1))
					.andExpect(jsonPath("$.meta.hasNext").value(false));

				verify(orderUseCase).getOrderPayments(eq(BUYER_ID), eq(request));
			}
		}

		@Nested
		@DisplayName("실패 케이스")
		class Failure {

			@Test
			@DisplayName("내 결제 내역 조회 시 X-User-Id 헤더가 없으면 401 Unauthorized")
			void getOrderPayments_withoutUserIdHeader_unauthorized() throws Exception {
				// when & then
				mockMvc.perform(get("/api/v1/orders/payments"))
					.andExpect(status().isUnauthorized())
					.andExpect(jsonPath("$.success").value(false))
					.andExpect(jsonPath("$.code").value(ErrorCode.INVALID_AUTHENTICATION.getCode()));

				verifyNoInteractions(orderUseCase);
			}

			@Test
			@DisplayName("내 결제 내역 조회 시 size가 100을 초과하면 400 Bad Request")
			void getOrderPayments_sizeOverLimit_badRequest() throws Exception {
				// when & then
				mockMvc.perform(get("/api/v1/orders/payments")
						.header(AuthHeaders.USER_ID, BUYER_ID.toString())
						.header(AuthHeaders.USER_ROLE, AuthHeaders.USER)
						.param("size", "101"))
					.andExpect(status().isBadRequest())
					.andExpect(jsonPath("$.success").value(false))
					.andExpect(jsonPath("$.code").value(ErrorCode.INVALID_INPUT_VALUE.getCode()));

				verifyNoInteractions(orderUseCase);
			}
		}
	}
}
