package com.prompthub.order.presentation;

import com.prompthub.order.application.dto.RefundResult;
import com.prompthub.order.application.dto.CreateOrderResult;
import com.prompthub.order.application.usecase.ConfirmDownloadUseCase;
import com.prompthub.order.application.usecase.CreateOrderUseCase;
import com.prompthub.order.application.usecase.OrderQueryUseCase;
import com.prompthub.order.application.service.refund.OrderRefundService;
import com.prompthub.order.domain.enums.OrderStatus;
import com.prompthub.order.domain.enums.OrderProductStatus;
import com.prompthub.order.global.exception.ErrorCode;
import com.prompthub.order.global.exception.GlobalExceptionHandler;
import com.prompthub.order.global.exception.OrderException;
import com.prompthub.order.global.web.AuthHeaders;
import com.prompthub.order.presentation.dto.request.PageRequestParams;
import com.prompthub.order.presentation.dto.response.OrderContentResponse;
import com.prompthub.order.presentation.dto.response.OrderDetailProductResponse;
import com.prompthub.order.presentation.dto.response.OrderDetailResponse;
import com.prompthub.order.presentation.dto.response.OrderListResponse;
import com.prompthub.order.presentation.dto.response.OrderListProductResponse;
import com.prompthub.order.presentation.dto.response.ProductDownloadResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentMatchers;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;
import java.time.LocalDate;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class OrderControllerTest {

	private MockMvc mockMvc;

	@Mock
	private ConfirmDownloadUseCase confirmDownloadUseCase;

	@Mock
	private OrderQueryUseCase orderQueryUseCase;

	@Mock
	private CreateOrderUseCase createOrderUseCase;

	@Mock
	private OrderRefundService orderRefundService;

	@BeforeEach
	void setUp() {
		LocalValidatorFactoryBean validator = new LocalValidatorFactoryBean();
		validator.afterPropertiesSet();

		mockMvc = MockMvcBuilders.standaloneSetup(new OrderController(
			confirmDownloadUseCase,
			orderQueryUseCase,
			createOrderUseCase,
			orderRefundService
		))
			.setControllerAdvice(new GlobalExceptionHandler())
			.setValidator(validator)
			.build();
	}


	@Test
	@DisplayName("payment-ready 경로는 더 이상 노출하지 않는다")
	void paymentReadyRoute_returnsNotFound() throws Exception {
		mockMvc.perform(post("/api/v2/orders/{orderId}/payment-ready", ORDER_ID)
				.header(AuthHeaders.USER_ID, BUYER_ID.toString())
				.contentType(MediaType.APPLICATION_JSON)
				.content("{}"))
			.andExpect(status().isNotFound());

		verifyNoInteractions(orderQueryUseCase);
	}

	@Test
	@DisplayName("무료 주문은 기존 응답 계약으로 완료 상태를 반환한다")
	void createOrder_freeOrder_returnsCompletedResponse() throws Exception {
		com.prompthub.order.domain.model.Order order =
			com.prompthub.order.domain.model.Order.create(BUYER_ID, ORDER_NUMBER, 0);
		order.addOrderProduct(com.prompthub.order.domain.model.OrderProduct.create(
			PRODUCT_ID_1, SELLER_ID_1, PRODUCT_TITLE_1, 0
		));
		order.completeFreeOrder();
		when(createOrderUseCase.createOrder(eq(BUYER_ID), ArgumentMatchers.any()))
			.thenReturn(CreateOrderResult.from(order));

		mockMvc.perform(post("/api/v2/orders")
				.header(AuthHeaders.USER_ID, BUYER_ID.toString())
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
					{"products":[{"productId":"%s","productTitle":"무료 상품"}]}
					""".formatted(PRODUCT_ID_1)))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.totalAmount").value(0))
			.andExpect(jsonPath("$.data.order.orderStatus").value("COMPLETED"))
			.andExpect(jsonPath("$.data.order.products[0].orderProductStatus").value("PAID"));
	}

	@Test
	@DisplayName("중복 무료 구매는 O018과 409를 반환한다")
	void createOrder_duplicateFreeProduct_returnsConflict() throws Exception {
		when(createOrderUseCase.createOrder(eq(BUYER_ID), ArgumentMatchers.any()))
			.thenThrow(new OrderException(ErrorCode.ORDER_PRODUCT_ALREADY_OWNED));

		mockMvc.perform(post("/api/v2/orders")
				.header(AuthHeaders.USER_ID, BUYER_ID.toString())
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
					{"products":[{"productId":"%s","productTitle":"무료 상품"}]}
					""".formatted(PRODUCT_ID_1)))
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value(ErrorCode.ORDER_PRODUCT_ALREADY_OWNED.getCode()));
	}

	@Nested
	@DisplayName("주문상품 다운로드 확정 (PATCH /api/v1/orders/{orderId}/products/{orderProductId}/download)")
	class ConfirmDownload {

		@Test
		@DisplayName("주문상품 다운로드 확정 성공")
		void confirmDownload_success() throws Exception {
			// given
			ProductDownloadResponse response = new ProductDownloadResponse(true);

			when(confirmDownloadUseCase.confirmDownload(eq(BUYER_ID), eq(ORDER_ID), eq(ORDER_PRODUCT_ID)))
				.thenReturn(response);

			// when & then
			mockMvc.perform(patch("/api/v2/orders/{orderId}/products/{orderProductId}/download", ORDER_ID, ORDER_PRODUCT_ID)
					.header(AuthHeaders.USER_ID, BUYER_ID.toString()))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.success").value(true))
				.andExpect(jsonPath("$.message").value("success"))
				.andExpect(jsonPath("$.data.downloaded").value(true))
				.andExpect(jsonPath("$.data.isDownload").doesNotExist());

			verify(confirmDownloadUseCase).confirmDownload(eq(BUYER_ID), eq(ORDER_ID), eq(ORDER_PRODUCT_ID));
		}
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

				when(orderQueryUseCase.getOrderContent(eq(BUYER_ID), eq(ORDER_ID), eq(ORDER_PRODUCT_ID)))
					.thenReturn(response);

				// when & then
				mockMvc.perform(get("/api/v2/orders/{orderId}/content/{orderProductId}", ORDER_ID, ORDER_PRODUCT_ID)
						.header(AuthHeaders.USER_ID, BUYER_ID.toString()))
					.andExpect(status().isOk())
					.andExpect(jsonPath("$.success").value(true))
					.andExpect(jsonPath("$.message").value("success"))
					.andExpect(jsonPath("$.data.orderId").value(ORDER_ID.toString()))
					.andExpect(jsonPath("$.data.orderProductId").value(ORDER_PRODUCT_ID.toString()))
					.andExpect(jsonPath("$.data.orderNumber").value(ORDER_NUMBER))
					.andExpect(jsonPath("$.data.productId").value(PRODUCT_ID_1.toString()))
					.andExpect(jsonPath("$.data.downloaded").value(true))
					.andExpect(jsonPath("$.data.isDownload").doesNotExist())
					.andExpect(jsonPath("$.data.productTitle").value(PRODUCT_TITLE_1))
					.andExpect(jsonPath("$.data.content").value(content));

				verify(orderQueryUseCase).getOrderContent(eq(BUYER_ID), eq(ORDER_ID), eq(ORDER_PRODUCT_ID));
			}
		}

		@Nested
		@DisplayName("실패 케이스")
		class Failure {

			@Test
			@DisplayName("X-User-Id 헤더가 없으면 401 Unauthorized")
			void getOrderContent_withoutUserIdHeader_unauthorized() throws Exception {
				// when & then
				mockMvc.perform(get("/api/v2/orders/{orderId}/content/{orderProductId}", ORDER_ID, ORDER_PRODUCT_ID))
					.andExpect(status().isUnauthorized())
					.andExpect(jsonPath("$.success").value(false))
					.andExpect(jsonPath("$.code").value(ErrorCode.INVALID_AUTHENTICATION.getCode()));

				verifyNoInteractions(orderQueryUseCase);
			}

			@Test
			@DisplayName("orderId가 UUID 형식이 아니면 400 Bad Request")
			void getOrderContent_invalidOrderId_badRequest() throws Exception {
				// when & then
				mockMvc.perform(get("/api/v2/orders/{orderId}/content/{orderProductId}", "invalid-order-id", ORDER_PRODUCT_ID)
						.header(AuthHeaders.USER_ID, BUYER_ID.toString()))
					.andExpect(status().isBadRequest())
					.andExpect(jsonPath("$.success").value(false))
					.andExpect(jsonPath("$.code").value(ErrorCode.INVALID_INPUT_VALUE.getCode()));

				verifyNoInteractions(orderQueryUseCase);
			}

			@Test
			@DisplayName("구매 콘텐츠를 열람할 수 없으면 403 Forbidden과 E001을 반환한다")
			void getOrderContent_accessDenied_forbidden() throws Exception {
				// given
				when(orderQueryUseCase.getOrderContent(eq(BUYER_ID), eq(ORDER_ID), eq(ORDER_PRODUCT_ID)))
					.thenThrow(new OrderException(ErrorCode.ORDER_CONTENT_ACCESS_DENIED));

				// when & then
				mockMvc.perform(get("/api/v2/orders/{orderId}/content/{orderProductId}", ORDER_ID, ORDER_PRODUCT_ID)
						.header(AuthHeaders.USER_ID, BUYER_ID.toString()))
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
				int totalAmount = PRODUCT_AMOUNT_1 * 2 + PRODUCT_AMOUNT_2 * 2;
				OrderDetailProductResponse productA1 = new OrderDetailProductResponse(
					ORDER_PRODUCT_ID,
					PRODUCT_ID_1,
					SELLER_ID_1,
					PRODUCT_TITLE_1,
					PRODUCT_TYPE_PROMPT,
					"GPT-4",
					PRODUCT_AMOUNT_1,
					OrderProductStatus.PAID,
					true,
					true,
					false
				);
				OrderDetailProductResponse productB1 = new OrderDetailProductResponse(
					UUID.randomUUID(), UUID.randomUUID(), UUID.fromString("00000000-0000-0000-0000-000000000202"),
					"B1", PRODUCT_TYPE_PROMPT, "GPT-4", PRODUCT_AMOUNT_2, OrderProductStatus.REFUNDED,
					false, false, false
				);
				OrderDetailProductResponse productA2 = new OrderDetailProductResponse(
					UUID.randomUUID(), UUID.randomUUID(), SELLER_ID_1,
					"A2", PRODUCT_TYPE_PROMPT, "GPT-4", PRODUCT_AMOUNT_1, OrderProductStatus.PAID,
					true, true, false
				);
				OrderDetailProductResponse productC1 = new OrderDetailProductResponse(
					UUID.randomUUID(), UUID.randomUUID(), UUID.fromString("00000000-0000-0000-0000-000000000203"),
					"C1", PRODUCT_TYPE_PROMPT, "GPT-4", PRODUCT_AMOUNT_2, OrderProductStatus.PAID,
					true, false, true
				);
				OrderDetailResponse response = new OrderDetailResponse(
					ORDER_ID,
					ORDER_NUMBER,
					BUYER_ID,
					OrderStatus.PAID,
					List.of(productA1, productB1, productA2, productC1),
					totalAmount,
					4,
					PAID_AT,
					null,
					null,
					CREATED_AT,
					false
				);

				when(orderQueryUseCase.getOrderDetail(eq(BUYER_ID), eq(ORDER_ID)))
					.thenReturn(response);

				// when & then
				mockMvc.perform(get("/api/v2/orders/{orderId}", ORDER_ID)
						.header(AuthHeaders.USER_ID, BUYER_ID.toString()))
					.andExpect(status().isOk())
					.andExpect(jsonPath("$.success").value(true))
					.andExpect(jsonPath("$.message").value("success"))
					.andExpect(jsonPath("$.data.orderId").value(ORDER_ID.toString()))
					.andExpect(jsonPath("$.data.orderNumber").value(ORDER_NUMBER))
					.andExpect(jsonPath("$.data.buyerId").value(BUYER_ID.toString()))
					.andExpect(jsonPath("$.data.orderStatus").value("COMPLETED"))
					.andExpect(jsonPath("$.data.totalAmount").value(totalAmount))
					.andExpect(jsonPath("$.data.totalProductCount").value(4))
					.andExpect(jsonPath("$.data.paidAt").value("2026-06-20T12:00:00"))
					.andExpect(jsonPath("$.data.canceledAt").doesNotExist())
					.andExpect(jsonPath("$.data.refundedAt").doesNotExist())
					.andExpect(jsonPath("$.data.createdAt").value("2026-06-20T11:58:00"))
					.andExpect(jsonPath("$.data.hasDownloadedProduct").value(false))
					.andExpect(jsonPath("$.data.hasDownloadProduct").doesNotExist())
					.andExpect(jsonPath("$.data.products[0].orderProductId").value(ORDER_PRODUCT_ID.toString()))
					.andExpect(jsonPath("$.data.products[0].productId").value(PRODUCT_ID_1.toString()))
					.andExpect(jsonPath("$.data.products[0].sellerId").value(SELLER_ID_1.toString()))
					.andExpect(jsonPath("$.data.products[0].productTitleSnapshot").value(PRODUCT_TITLE_1))
					.andExpect(jsonPath("$.data.products[0].productTypeSnapshot").value(PRODUCT_TYPE_PROMPT))
					.andExpect(jsonPath("$.data.products[0].productAmountSnapshot").value(PRODUCT_AMOUNT_1))
					.andExpect(jsonPath("$.data.products[0].orderStatus").value("PAID"))
					.andExpect(jsonPath("$.data.products[0].isContentAccessible").value(true))
					.andExpect(jsonPath("$.data.products[0].isRefundable").value(true))
					.andExpect(jsonPath("$.data.products[0].isRefund").doesNotExist())
					.andExpect(jsonPath("$.data.products[0].downloaded").value(false))
					.andExpect(jsonPath("$.data.products[0].download").doesNotExist())
					.andExpect(jsonPath("$.data.products[1].sellerId").value("00000000-0000-0000-0000-000000000202"))
					.andExpect(jsonPath("$.data.products[2].sellerId").value(SELLER_ID_1.toString()))
					.andExpect(jsonPath("$.data.products[3].sellerId").value("00000000-0000-0000-0000-000000000203"))
					.andExpect(jsonPath("$.data.products[1].orderStatus").value("REFUNDED"))
					.andExpect(jsonPath("$.data.products[1].isRefundable").value(false))
					.andExpect(jsonPath("$.data.products[3].downloaded").value(true))
					.andExpect(jsonPath("$.data.products[3].isRefundable").value(false));

				verify(orderQueryUseCase).getOrderDetail(eq(BUYER_ID), eq(ORDER_ID));
			}
		}

		@Nested
		@DisplayName("실패 케이스")
		class Failure {

			@Test
			@DisplayName("X-User-Id 헤더가 없으면 401 Unauthorized")
			void getOrderDetail_withoutUserIdHeader_unauthorized() throws Exception {
				// when & then
				mockMvc.perform(get("/api/v2/orders/{orderId}", ORDER_ID))
					.andExpect(status().isUnauthorized())
					.andExpect(jsonPath("$.success").value(false))
					.andExpect(jsonPath("$.code").value(ErrorCode.INVALID_AUTHENTICATION.getCode()));

				verifyNoInteractions(orderQueryUseCase);
			}

			@Test
			@DisplayName("orderId가 UUID 형식이 아니면 400 Bad Request")
			void getOrderDetail_invalidOrderId_badRequest() throws Exception {
				// when & then
				mockMvc.perform(get("/api/v2/orders/{orderId}", "invalid-order-id")
						.header(AuthHeaders.USER_ID, BUYER_ID.toString()))
					.andExpect(status().isBadRequest())
					.andExpect(jsonPath("$.success").value(false))
					.andExpect(jsonPath("$.code").value(ErrorCode.INVALID_INPUT_VALUE.getCode()));

				verifyNoInteractions(orderQueryUseCase);
			}

			@Test
			@DisplayName("주문이 없으면 404 Not Found와 O001을 반환한다")
			void getOrderDetail_orderNotFound_notFound() throws Exception {
				// given
				when(orderQueryUseCase.getOrderDetail(eq(BUYER_ID), eq(ORDER_ID)))
					.thenThrow(new OrderException(ErrorCode.ORDER_NOT_FOUND));

				// when & then
				mockMvc.perform(get("/api/v2/orders/{orderId}", ORDER_ID)
						.header(AuthHeaders.USER_ID, BUYER_ID.toString()))
					.andExpect(status().isNotFound())
					.andExpect(jsonPath("$.success").value(false))
					.andExpect(jsonPath("$.code").value(ErrorCode.ORDER_NOT_FOUND.getCode()));
			}

			@Test
			@DisplayName("본인 주문이 아니면 403 Forbidden과 A004를 반환한다")
			void getOrderDetail_notOwner_forbidden() throws Exception {
				// given
				when(orderQueryUseCase.getOrderDetail(eq(BUYER_ID), eq(ORDER_ID)))
					.thenThrow(new OrderException(ErrorCode.FORBIDDEN));

				// when & then
				mockMvc.perform(get("/api/v2/orders/{orderId}", ORDER_ID)
						.header(AuthHeaders.USER_ID, BUYER_ID.toString()))
					.andExpect(status().isForbidden())
					.andExpect(jsonPath("$.success").value(false))
					.andExpect(jsonPath("$.code").value(ErrorCode.FORBIDDEN.getCode()));
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
				OrderListProductResponse product = new OrderListProductResponse(
					ORDER_PRODUCT_ID,
					PRODUCT_ID_1,
					OrderProductStatus.PAID,
					PRODUCT_AMOUNT_1,
					true,
					false,
					PRODUCT_TYPE_PROMPT,
					PRODUCT_TITLE_1,
					PRODUCT_MODEL,
					4.5
				);
				OrderListResponse order = new OrderListResponse(
					ORDER_ID,
					ORDER_NUMBER,
					OrderStatus.PAID,
					TOTAL_AMOUNT,
					List.of(product),
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

				when(orderQueryUseCase.getOrders(eq(BUYER_ID), eq(request)))
					.thenReturn(response);

				// when & then
				mockMvc.perform(get("/api/v2/orders")
						.header(AuthHeaders.USER_ID, BUYER_ID.toString())
						.param("page", "1")
						.param("size", "20")
						.param("status", "PAID")
						.param("from", "2026-06-01")
						.param("to", "2026-06-30"))
					.andExpect(status().isOk())
					.andExpect(jsonPath("$.success").value(true))
					.andExpect(jsonPath("$.message").value("success"))
					.andExpect(jsonPath("$.data[0].orderId").value(ORDER_ID.toString()))
					.andExpect(jsonPath("$.data[0].orderNumber").value(ORDER_NUMBER))
					.andExpect(jsonPath("$.data[0].orderStatus").value("COMPLETED"))
					.andExpect(jsonPath("$.data[0].totalAmount").value(TOTAL_AMOUNT))
					.andExpect(jsonPath("$.data[0].orderProductId").doesNotExist())
					.andExpect(jsonPath("$.data[0].products.length()").value(1))
					.andExpect(jsonPath("$.data[0].products[0].orderProductId").value(ORDER_PRODUCT_ID.toString()))
					.andExpect(jsonPath("$.data[0].products[0].productId").value(PRODUCT_ID_1.toString()))
					.andExpect(jsonPath("$.data[0].products[0].orderProductStatus").value("PAID"))
					.andExpect(jsonPath("$.data[0].products[0].amount").value(PRODUCT_AMOUNT_1))
					.andExpect(jsonPath("$.data[0].products[0].isRefundable").value(true))
					.andExpect(jsonPath("$.data[0].products[0].productType").value(PRODUCT_TYPE_PROMPT))
					.andExpect(jsonPath("$.data[0].products[0].title").value(PRODUCT_TITLE_1))
					.andExpect(jsonPath("$.data[0].products[0].model").value(PRODUCT_MODEL))
					.andExpect(jsonPath("$.data[0].products[0].rating").value(4.5))
					.andExpect(jsonPath("$.data[0].paidAt").value("2026-06-20T12:00:00"))
					.andExpect(jsonPath("$.data[0].createdAt").value("2026-06-20T11:58:00"))
					.andExpect(jsonPath("$.meta.page").value(1))
					.andExpect(jsonPath("$.meta.size").value(20))
					.andExpect(jsonPath("$.meta.total").value(1))
					.andExpect(jsonPath("$.meta.hasNext").value(false));

				verify(orderQueryUseCase).getOrders(eq(BUYER_ID), eq(request));
			}

			@Test
			@DisplayName("내 주문 목록 조회 응답은 rating이 null이어도 정상이다")
			void getOrders_nullRating_success() throws Exception {
				// given
				OrderListProductResponse product = new OrderListProductResponse(
					ORDER_PRODUCT_ID,
					PRODUCT_ID_1,
					OrderProductStatus.PAID,
					PRODUCT_AMOUNT_1,
					true,
					false,
					PRODUCT_TYPE_PROMPT,
					PRODUCT_TITLE_1,
					PRODUCT_MODEL,
					null
				);
				OrderListResponse order = new OrderListResponse(
					ORDER_ID,
					ORDER_NUMBER,
					OrderStatus.PAID,
					TOTAL_AMOUNT,
					List.of(product),
					PAID_AT,
					CREATED_AT
				);
				Page<OrderListResponse> response = new PageImpl<>(List.of(order), PageRequest.of(0, 20), 1);
				PageRequestParams request = new PageRequestParams(1, 20, null, null, null);

				when(orderQueryUseCase.getOrders(eq(BUYER_ID), eq(request)))
					.thenReturn(response);

				// when & then
				mockMvc.perform(get("/api/v2/orders")
						.header(AuthHeaders.USER_ID, BUYER_ID.toString()))
					.andExpect(status().isOk())
					.andExpect(jsonPath("$.data[0].products[0].rating").doesNotExist());
			}
		}

		@Nested
		@DisplayName("실패 케이스")
		class Failure {

			@Test
			@DisplayName("날짜 형식이 잘못되면 400 Bad Request와 V001을 반환한다")
			void getOrders_invalidDate_badRequest() throws Exception {
				// when & then
				mockMvc.perform(get("/api/v2/orders")
						.header(AuthHeaders.USER_ID, BUYER_ID.toString())
						.param("from", "2026/06/01"))
					.andExpect(status().isBadRequest())
					.andExpect(jsonPath("$.success").value(false))
					.andExpect(jsonPath("$.code").value(ErrorCode.INVALID_INPUT_VALUE.getCode()));

				verifyNoInteractions(orderQueryUseCase);
			}

			@Test
			@DisplayName("size가 100을 초과하면 400 Bad Request와 V001을 반환한다")
			void getOrders_sizeOverLimit_badRequest() throws Exception {
				// when & then
				mockMvc.perform(get("/api/v2/orders")
						.header(AuthHeaders.USER_ID, BUYER_ID.toString())
						.param("size", "101"))
					.andExpect(status().isBadRequest())
					.andExpect(jsonPath("$.success").value(false))
					.andExpect(jsonPath("$.code").value(ErrorCode.INVALID_INPUT_VALUE.getCode()));

				verifyNoInteractions(orderQueryUseCase);
			}

			@Test
			@DisplayName("from이 to보다 늦으면 400 Bad Request와 V001을 반환한다")
			void getOrders_fromAfterTo_badRequest() throws Exception {
				// when & then
				mockMvc.perform(get("/api/v2/orders")
						.header(AuthHeaders.USER_ID, BUYER_ID.toString())
						.param("from", "2026-06-30")
						.param("to", "2026-06-01"))
					.andExpect(status().isBadRequest())
					.andExpect(jsonPath("$.success").value(false))
					.andExpect(jsonPath("$.code").value(ErrorCode.INVALID_INPUT_VALUE.getCode()));

				verifyNoInteractions(orderQueryUseCase);
			}
		}
	}

	@Nested
	@DisplayName("주문 상품 다건 부분 환불 요청")
	class RequestRefund {

		@Test
		void requestRefund_returnsAcceptedResult() throws Exception {
			UUID secondProductId = UUID.randomUUID();
			UUID refundRequestId = UUID.randomUUID();
			when(orderRefundService.requestRefund(
				eq(BUYER_ID),
				eq(ORDER_ID),
				eq(List.of(ORDER_PRODUCT_ID, secondProductId))
			)).thenReturn(new RefundResult(
				refundRequestId,
				ORDER_ID,
				List.of(ORDER_PRODUCT_ID, secondProductId),
				30_000,
				"REQUESTED"
			));

			mockMvc.perform(post("/api/v2/orders/{orderId}/refund", ORDER_ID)
					.header(AuthHeaders.USER_ID, BUYER_ID)
					.contentType(MediaType.APPLICATION_JSON)
					.content("""
						{"orderProductIds":["%s","%s"]}
						""".formatted(ORDER_PRODUCT_ID, secondProductId)))
				.andExpect(status().isAccepted())
				.andExpect(jsonPath("$.data.refundRequestId").value(refundRequestId.toString()))
				.andExpect(jsonPath("$.data.orderProductIds.length()").value(2))
				.andExpect(jsonPath("$.data.refundAmount").value(30_000))
				.andExpect(jsonPath("$.data.status").value("REQUESTED"));

			verify(orderRefundService).requestRefund(
				BUYER_ID,
				ORDER_ID,
				List.of(ORDER_PRODUCT_ID, secondProductId)
			);
		}

		@Test
		void requestRefund_rejectsEmptyProducts() throws Exception {
			mockMvc.perform(post("/api/v2/orders/{orderId}/refund", ORDER_ID)
					.header(AuthHeaders.USER_ID, BUYER_ID)
					.contentType(MediaType.APPLICATION_JSON)
					.content("{\"orderProductIds\":[]}"))
				.andExpect(status().isBadRequest());

			verifyNoInteractions(orderRefundService);
		}
	}

	@Nested
	@DisplayName("열람 가능한 구매 상품 조회")
	class GetAccessiblePaidProducts {

		@Test
		@DisplayName("상품 상세 페이지용 구매 여부를 boolean으로 반환한다")
		void hasAccessiblePaidProduct_success() throws Exception {
			when(orderQueryUseCase.hasAccessiblePaidProduct(BUYER_ID, PRODUCT_ID_1)).thenReturn(true);

			mockMvc.perform(get("/api/v2/orders/product/{productId}/paid", PRODUCT_ID_1)
					.header(AuthHeaders.USER_ID, BUYER_ID.toString()))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.success").value(true))
				.andExpect(jsonPath("$.data").value(true));

			verify(orderQueryUseCase).hasAccessiblePaidProduct(BUYER_ID, PRODUCT_ID_1);
		}

		@Test
		@DisplayName("상품 상세 페이지용 다운로드 여부를 반환한다")
		void getProductDownloadStatus_success() throws Exception {
			when(orderQueryUseCase.isProductDownloaded(BUYER_ID, PRODUCT_ID_1)).thenReturn(true);

			mockMvc.perform(get("/api/v2/orders/products/{productId}", PRODUCT_ID_1)
					.header(AuthHeaders.USER_ID, BUYER_ID.toString()))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.success").value(true))
				.andExpect(jsonPath("$.data.downloaded").value(true));

			verify(orderQueryUseCase).isProductDownloaded(BUYER_ID, PRODUCT_ID_1);
		}

		@Test
		@DisplayName("미다운로드 구매 상품은 false를 반환한다")
		void getProductDownloadStatus_notDownloaded_returnsFalse() throws Exception {
			when(orderQueryUseCase.isProductDownloaded(BUYER_ID, PRODUCT_ID_1)).thenReturn(false);

			mockMvc.perform(get("/api/v2/orders/products/{productId}", PRODUCT_ID_1)
					.header(AuthHeaders.USER_ID, BUYER_ID.toString()))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.downloaded").value(false));

			verify(orderQueryUseCase).isProductDownloaded(BUYER_ID, PRODUCT_ID_1);
		}

		@Test
		@DisplayName("마이페이지용 구매 상품 ID 목록을 반환한다")
		void getAccessiblePaidProductIds_success() throws Exception {
			when(orderQueryUseCase.getAccessiblePaidProductIds(BUYER_ID))
				.thenReturn(List.of(PRODUCT_ID_1, PRODUCT_ID_2));

			mockMvc.perform(get("/api/v2/orders/users")
					.header(AuthHeaders.USER_ID, BUYER_ID.toString()))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.success").value(true))
				.andExpect(jsonPath("$.data[0]").value(PRODUCT_ID_1.toString()))
				.andExpect(jsonPath("$.data[1]").value(PRODUCT_ID_2.toString()));

			verify(orderQueryUseCase).getAccessiblePaidProductIds(BUYER_ID);
		}

		@Test
		@DisplayName("사용자 ID 헤더가 없으면 401을 반환한다")
		void hasAccessiblePaidProduct_missingUserId_unauthorized() throws Exception {
			mockMvc.perform(get("/api/v2/orders/product/{productId}/paid", PRODUCT_ID_1))
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.code").value(ErrorCode.INVALID_AUTHENTICATION.getCode()));

			verifyNoInteractions(orderQueryUseCase);
		}

		@Test
		@DisplayName("다운로드 여부 조회에 사용자 ID 헤더가 없으면 401을 반환한다")
		void getProductDownloadStatus_missingUserId_unauthorized() throws Exception {
			mockMvc.perform(get("/api/v2/orders/products/{productId}", PRODUCT_ID_1))
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.code").value(ErrorCode.INVALID_AUTHENTICATION.getCode()));

			verifyNoInteractions(orderQueryUseCase);
		}
	}
}
