package com.prompthub.order.application.service.order;

import com.prompthub.order.application.dto.CreateOrderCommand;
import com.prompthub.order.application.dto.ProductOrderSnapshot;
import com.prompthub.order.infra.messaging.kafka.event.PaymentApprovedPayload;
import com.prompthub.order.domain.enums.OrderStatus;
import com.prompthub.order.domain.enums.OrderProductStatus;
import com.prompthub.order.domain.model.Order;
import com.prompthub.order.global.exception.ErrorCode;
import com.prompthub.order.global.exception.OrderException;
import com.prompthub.order.presentation.dto.request.PageRequestParams;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static com.prompthub.order.fixture.OrderFixture.*;
import static com.prompthub.order.fixture.OrderV2Fixture.PRODUCT_A1;
import static com.prompthub.order.fixture.OrderV2Fixture.REQUEST_TITLE_A1;
import static com.prompthub.order.fixture.OrderV2Fixture.command;
import static com.prompthub.order.fixture.OrderV2Fixture.requestedProductIds;
import static com.prompthub.order.fixture.OrderV2Fixture.shuffledSnapshots;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OrderPolicyServiceTest {

	private final OrderPolicyService orderPolicyService = new OrderPolicyService();

	@Nested
	@DisplayName("주문 생성 요청 검증")
	class ValidateCreateOrderCommand {

		@Test
		@DisplayName("상품 ID 목록이 유효하면 예외가 발생하지 않는다")
		void validProductsSuccess() {
			orderPolicyService.validateCreateOrderCommand(command());
		}

		@Test
		@DisplayName("상품 ID 목록이 null이면 입력값 검증 예외가 발생한다")
		void nullProductsThrowsException() {
			assertThatThrownBy(() ->
				orderPolicyService.validateCreateOrderCommand(new CreateOrderCommand(null)))
				.isInstanceOf(OrderException.class)
				.satisfies(exception ->
					assertThat(((OrderException) exception).getErrorCode()).isEqualTo(ErrorCode.INVALID_INPUT_VALUE)
				);
		}

		@Test
		@DisplayName("상품 ID 목록이 비어 있으면 입력값 검증 예외가 발생한다")
		void emptyProductsThrowsException() {
			assertThatThrownBy(() ->
				orderPolicyService.validateCreateOrderCommand(new CreateOrderCommand(List.of())))
				.isInstanceOf(OrderException.class)
				.satisfies(exception ->
					assertThat(((OrderException) exception).getErrorCode()).isEqualTo(ErrorCode.INVALID_INPUT_VALUE)
				);
		}

		@Test
		@DisplayName("중복 상품 ID가 있으면 입력값 검증 예외가 발생한다")
		void duplicatedProductIdsThrowsException() {
			CreateOrderCommand duplicated = new CreateOrderCommand(List.of(
				new CreateOrderCommand.Product(PRODUCT_A1, REQUEST_TITLE_A1),
				new CreateOrderCommand.Product(PRODUCT_A1, "다른 제목")
			));

			assertThatThrownBy(() ->
				orderPolicyService.validateCreateOrderCommand(duplicated))
				.isInstanceOf(OrderException.class)
				.satisfies(exception ->
					assertThat(((OrderException) exception).getErrorCode()).isEqualTo(ErrorCode.INVALID_INPUT_VALUE)
				);
		}

		@Test
		@DisplayName("공백 제목이 있으면 입력값 검증 예외가 발생한다")
		void blankTitleThrowsException() {
			CreateOrderCommand blank = new CreateOrderCommand(List.of(
				new CreateOrderCommand.Product(PRODUCT_A1, "   ")
			));

			assertThatThrownBy(() -> orderPolicyService.validateCreateOrderCommand(blank))
				.isInstanceOf(OrderException.class)
				.satisfies(exception -> assertThat(((OrderException) exception).getErrorCode())
					.isEqualTo(ErrorCode.INVALID_INPUT_VALUE));
		}
	}

	@Nested
	@DisplayName("상품 스냅샷 검증")
	class ValidateProductSnapshots {

		@Test
		@DisplayName("요청 상품과 조회 상품이 일치하면 예외가 발생하지 않는다")
		void matchingProducts_success() {
			orderPolicyService.validateProductSnapshots(requestedProductIds(), shuffledSnapshots());
		}

		@Test
		@DisplayName("상품 스냅샷 응답이 null이면 입력값 검증 예외가 발생한다")
		void nullProductSnapshots_throwsException() {
			assertThatThrownBy(() -> orderPolicyService.validateProductSnapshots(requestedProductIds(), null))
				.isInstanceOf(OrderException.class);
		}

		@Test
		@DisplayName("요청 상품 수와 조회 상품 수가 다르면 입력값 검증 예외가 발생한다")
		void productSnapshotSizeMismatch_throwsException() {
			List<ProductOrderSnapshot> snapshots = shuffledSnapshots().subList(0, 3);

			assertThatThrownBy(() -> orderPolicyService.validateProductSnapshots(requestedProductIds(), snapshots))
				.isInstanceOf(OrderException.class);
		}

		@Test
		@DisplayName("조회되지 않은 상품 ID가 있으면 입력값 검증 예외가 발생한다")
		void missingProductSnapshot_throwsException() {
			List<ProductOrderSnapshot> snapshots = List.of(
				shuffledSnapshots().get(0),
				shuffledSnapshots().get(1),
				shuffledSnapshots().get(2),
				new ProductOrderSnapshot(
					UNKNOWN_PRODUCT_ID,
					SELLER_ID_1,
					UNKNOWN_PRODUCT_TITLE,
					PRODUCT_TYPE_PROMPT,
					PRODUCT_MODEL,
					PRODUCT_AMOUNT_1
				)
			);

			assertThatThrownBy(() -> orderPolicyService.validateProductSnapshots(requestedProductIds(), snapshots))
				.isInstanceOf(OrderException.class);
		}
	}

	@Nested
	@DisplayName("주문 목록 조회 정책")
	class OrderListPolicy {

		@Test
		@DisplayName("page가 null이면 기본 페이지 1을 반환한다")
		void resolvePage_null_returnsDefault() {
			assertThat(orderPolicyService.resolvePage(null)).isEqualTo(1);
		}

		@Test
		@DisplayName("page가 1보다 작으면 입력값 검증 예외가 발생한다")
		void resolvePage_underOne_throwsException() {
			assertThatThrownBy(() -> orderPolicyService.resolvePage(0))
				.isInstanceOf(OrderException.class)
				.satisfies(exception ->
					assertThat(((OrderException) exception).getErrorCode()).isEqualTo(ErrorCode.INVALID_INPUT_VALUE)
				);
		}

		@Test
		@DisplayName("size가 null이면 기본 크기 20을 반환한다")
		void resolveSize_null_returnsDefault() {
			assertThat(orderPolicyService.resolveSize(null)).isEqualTo(20);
		}

		@Test
		@DisplayName("size가 1보다 작으면 입력값 검증 예외가 발생한다")
		void resolveSize_underOne_throwsException() {
			assertThatThrownBy(() -> orderPolicyService.resolveSize(0))
				.isInstanceOf(OrderException.class)
				.satisfies(exception ->
					assertThat(((OrderException) exception).getErrorCode()).isEqualTo(ErrorCode.INVALID_INPUT_VALUE)
				);
		}

		@Test
		@DisplayName("size가 100을 초과하면 입력값 검증 예외가 발생한다")
		void resolveSize_overMax_throwsException() {
			assertThatThrownBy(() -> orderPolicyService.resolveSize(101))
				.isInstanceOf(OrderException.class)
				.satisfies(exception ->
					assertThat(((OrderException) exception).getErrorCode()).isEqualTo(ErrorCode.INVALID_INPUT_VALUE)
				);
		}

		@Test
		@DisplayName("from이 to보다 늦으면 입력값 검증 예외가 발생한다")
		void validateDateRange_fromAfterTo_throwsException() {
			PageRequestParams request = new PageRequestParams(
				1,
				20,
				null,
				LocalDate.of(2026, 6, 30),
				LocalDate.of(2026, 6, 1)
			);

			assertThatThrownBy(() -> orderPolicyService.validateDateRange(request))
				.isInstanceOf(OrderException.class)
				.satisfies(exception ->
					assertThat(((OrderException) exception).getErrorCode()).isEqualTo(ErrorCode.INVALID_INPUT_VALUE)
				);
		}

		@Test
		@DisplayName("주문과 주문 상품이 PAID이고 다운로드하지 않았으면 환불 가능하다")
		void isRefundable_paidAndNotDownloaded_returnsTrue() {
			assertThat(orderPolicyService.isRefundable(OrderStatus.COMPLETED, OrderProductStatus.PAID, false)).isTrue();
		}

		@Test
		@DisplayName("다운로드한 상품은 환불 가능하지 않다")
		void isRefundable_downloaded_returnsFalse() {
			assertThat(orderPolicyService.isRefundable(OrderStatus.COMPLETED, OrderProductStatus.PAID, true)).isFalse();
		}

		@Test
		@DisplayName("PAID가 아닌 주문은 환불 가능하지 않다")
		void isRefundable_notPaidOrder_returnsFalse() {
			assertThat(orderPolicyService.isRefundable(OrderStatus.FAILED, OrderProductStatus.PAID, false)).isFalse();
		}
	}

	@Nested
	@DisplayName("환불/취소 사전 검증")
	class RefundOrCancelPolicy {

		@Test
		@DisplayName("다운로드한 주문상품이 없으면 예외가 발생하지 않는다")
		void validateNoDownloadedProduct_withoutDownloadedProduct_success() {
			Order order = createPaidOrderWithProducts();

			orderPolicyService.validateNoDownloadedProduct(order);
		}

		@Test
		@DisplayName("다운로드한 주문상품이 있으면 O002 예외가 발생한다")
		void validateNoDownloadedProduct_withDownloadedProduct_throwsException() {
			Order order = createPaidOrderWithProducts();
			order.getOrderProducts().getFirst().markDownloaded();

			assertThatThrownBy(() -> orderPolicyService.validateNoDownloadedProduct(order))
				.isInstanceOf(OrderException.class)
				.satisfies(exception ->
					assertThat(((OrderException) exception).getErrorCode()).isEqualTo(ErrorCode.ORDER_CANCEL_NOT_ALLOWED)
				);
		}
	}

	@Nested
	@DisplayName("결제 승인 정책")
	class PaymentApprovalPolicy {

		@Test
		@DisplayName("주문이 PENDING이고 승인 금액이 일치하면 예외가 발생하지 않는다")
		void validatePaymentApproval_pendingAndAmountMatched_success() {
			Order order = createPendingOrderWithProducts();
			PaymentApprovedPayload payload = createPaymentApprovedPayload(order.getId(), TOTAL_AMOUNT);

			orderPolicyService.validatePaymentApproval(order, payload);
		}

		@Test
		@DisplayName("주문 상태가 PENDING이 아니면 결제 승인 상태 예외가 발생한다")
		void validatePaymentApproval_notPending_throwsException() {
			Order order = createPendingOrderWithProducts();
			order.markCompleted(APPROVED_AT);
			PaymentApprovedPayload payload = createPaymentApprovedPayload(order.getId(), TOTAL_AMOUNT);

			assertThatThrownBy(() -> orderPolicyService.validatePaymentApproval(order, payload))
				.isInstanceOf(OrderException.class)
				.satisfies(exception ->
					assertThat(((OrderException) exception).getErrorCode())
						.isEqualTo(ErrorCode.ORDER_PAYMENT_STATUS_INVALID)
				);
		}

		@Test
		@DisplayName("승인 금액과 주문 금액이 다르면 결제 승인 금액 불일치 예외가 발생한다")
		void validatePaymentApproval_amountMismatch_throwsException() {
			Order order = createPendingOrderWithProducts();
			PaymentApprovedPayload payload = createPaymentApprovedPayload(order.getId(), PRODUCT_AMOUNT_2);

			assertThatThrownBy(() -> orderPolicyService.validatePaymentApproval(order, payload))
				.isInstanceOf(OrderException.class)
				.satisfies(exception ->
					assertThat(((OrderException) exception).getErrorCode())
						.isEqualTo(ErrorCode.ORDER_PAYMENT_AMOUNT_MISMATCH)
				);
		}
	}
}
