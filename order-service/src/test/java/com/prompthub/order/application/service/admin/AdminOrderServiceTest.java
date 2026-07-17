package com.prompthub.order.application.service.admin;


import com.prompthub.order.application.client.SellerClient;
import com.prompthub.order.application.dto.AdminDailyTransactionProjection;
import com.prompthub.order.application.dto.AdminOrderListProjection;
import com.prompthub.order.domain.enums.OrderStatus;
import com.prompthub.order.presentation.dto.request.AdminOrderSearchCondition;
import com.prompthub.order.presentation.dto.response.AdminMonthlyTradeAmountResponse;
import com.prompthub.order.presentation.dto.response.AdminOrderListResponse;
import com.prompthub.order.presentation.dto.response.AdminWeeklyTransactionResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.transaction.annotation.Transactional;

import java.lang.reflect.Method;
import java.time.LocalDateTime;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.prompthub.order.fixture.OrderFixture.ORDER_ID;
import static com.prompthub.order.fixture.OrderFixture.PRODUCT_TITLE_1;
import static com.prompthub.order.fixture.OrderFixture.SELLER_ID_1;
import static com.prompthub.order.fixture.OrderFixture.SELLER_ID_2;
import static com.prompthub.order.fixture.OrderFixture.TOTAL_AMOUNT;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.Mockito.never;

@ExtendWith(MockitoExtension.class)
class AdminOrderServiceTest {

	@Mock
	private AdminOrderQueryService adminOrderQueryService;

	@Mock
	private SellerClient sellerClient;

	@InjectMocks
	private AdminOrderService adminOrderService;

	@Nested
	@DisplayName("관리자 주문 목록 조회")
	class GetAdminOrders {

		@Test
		@DisplayName("한 주문의 모든 판매자를 한 번의 bulk 조회로 닉네임과 함께 매핑한다")
		void getAdminOrders_mapsAllSellersWithSingleBulkLookup() {
			AdminOrderSearchCondition condition = new AdminOrderSearchCondition("ALL", 1, 20);
			UUID sellerId3 = UUID.fromString("00000000-0000-0000-0000-000000000203");
			AdminOrderListProjection projection = adminOrderProjection(
				ORDER_ID,
				List.of(
					new AdminOrderListProjection.SellerSummary(SELLER_ID_1, 2, 30_000),
					new AdminOrderListProjection.SellerSummary(SELLER_ID_2, 1, 15_000),
					new AdminOrderListProjection.SellerSummary(sellerId3, 1, 25_000)
				)
			);
			given(adminOrderQueryService.searchAdminOrders(any(), any()))
				.willReturn(new PageImpl<>(List.of(projection), PageRequest.of(0, 20), 1));
			given(sellerClient.getSellerNicknames(List.of(SELLER_ID_1, SELLER_ID_2, sellerId3)))
				.willReturn(Map.of(SELLER_ID_1, "판매자A", SELLER_ID_2, "판매자B"));

			Page<AdminOrderListResponse> response = adminOrderService.getAdminOrders(condition.resolve());

			assertThat(response.getContent()).hasSize(1);
			assertThat(response.getContent().getFirst().sellerCount()).isEqualTo(3);
			assertThat(response.getContent().getFirst().sellers()).containsExactly(
				new AdminOrderListResponse.SellerSummary(SELLER_ID_1, "판매자A", 2, 30_000),
				new AdminOrderListResponse.SellerSummary(SELLER_ID_2, "판매자B", 1, 15_000),
				new AdminOrderListResponse.SellerSummary(sellerId3, "알 수 없음", 1, 25_000)
			);
			assertThat(response.getContent().getFirst().productTitle()).isEqualTo(PRODUCT_TITLE_1);

			ArgumentCaptor<AdminOrderSearchCondition> conditionCaptor = ArgumentCaptor.forClass(AdminOrderSearchCondition.class);
			then(adminOrderQueryService).should().searchAdminOrders(conditionCaptor.capture(), any());
			assertThat(conditionCaptor.getValue().resolvedOrderStatus()).isNull();
			then(sellerClient).should().getSellerNicknames(List.of(SELLER_ID_1, SELLER_ID_2, sellerId3));
		}

		@Test
		@DisplayName("특정 주문 상태는 해당 상태로 조회한다")
		void getAdminOrders_completedStatus_success() {
			AdminOrderSearchCondition condition = new AdminOrderSearchCondition("COMPLETED", 1, 20);
			given(adminOrderQueryService.searchAdminOrders(any(), any()))
				.willReturn(new PageImpl<>(List.of(adminOrderProjection(SELLER_ID_1)), PageRequest.of(0, 20), 1));
			given(sellerClient.getSellerNicknames(any())).willReturn(Map.of());

			adminOrderService.getAdminOrders(condition.resolve());

			ArgumentCaptor<AdminOrderSearchCondition> conditionCaptor = ArgumentCaptor.forClass(AdminOrderSearchCondition.class);
			then(adminOrderQueryService).should().searchAdminOrders(conditionCaptor.capture(), any());
			assertThat(conditionCaptor.getValue().resolvedOrderStatus()).isEqualTo(OrderStatus.COMPLETED);
		}

		@Test
		@DisplayName("판매자 조회 결과가 없으면 알 수 없음으로 응답한다")
		void getAdminOrders_missingSellerNickname_usesUnknownFallback() {
			AdminOrderSearchCondition condition = new AdminOrderSearchCondition("ALL", 1, 20);
			AdminOrderListProjection projection = adminOrderProjection(SELLER_ID_1);
			given(adminOrderQueryService.searchAdminOrders(any(), any()))
				.willReturn(new PageImpl<>(List.of(projection), PageRequest.of(0, 20), 1));
			given(sellerClient.getSellerNicknames(List.of(SELLER_ID_1))).willReturn(Map.of());

			Page<AdminOrderListResponse> response = adminOrderService.getAdminOrders(condition.resolve());

			assertThat(response.getContent().getFirst().sellers().getFirst().sellerNickname()).isEqualTo("알 수 없음");
		}

		@Test
		@DisplayName("동일 판매자의 주문이 여러 건이면 판매자 ID를 중복 제거해서 한 번만 조회한다")
		void getAdminOrders_deduplicatesSellerIds() {
			AdminOrderSearchCondition condition = new AdminOrderSearchCondition("ALL", 1, 20);
			UUID secondOrderId = UUID.fromString("00000000-0000-0000-0000-000000000102");
			AdminOrderListProjection first = adminOrderProjection(ORDER_ID, SELLER_ID_1);
			AdminOrderListProjection second = adminOrderProjection(secondOrderId, SELLER_ID_1);
			given(adminOrderQueryService.searchAdminOrders(any(), any()))
				.willReturn(new PageImpl<>(List.of(first, second), PageRequest.of(0, 20), 2));
			given(sellerClient.getSellerNicknames(List.of(SELLER_ID_1)))
				.willReturn(Map.of(SELLER_ID_1, "판매자A"));

			Page<AdminOrderListResponse> response = adminOrderService.getAdminOrders(condition.resolve());

			assertThat(response.getContent()).flatExtracting(AdminOrderListResponse::sellers)
				.extracting(AdminOrderListResponse.SellerSummary::sellerNickname)
				.containsExactly("판매자A", "판매자A");
			then(sellerClient).should().getSellerNicknames(List.of(SELLER_ID_1));
		}

		@Test
		@DisplayName("주문 목록이 비어 있으면 빈 Set 기준으로 판매자 조회를 생략한다")
		void getAdminOrders_emptyOrders_skipsSellerLookup() {
			AdminOrderSearchCondition condition = new AdminOrderSearchCondition("ALL", 1, 20);
			given(adminOrderQueryService.searchAdminOrders(any(), any()))
				.willReturn(new PageImpl<>(List.of(), PageRequest.of(0, 20), 0));

			Page<AdminOrderListResponse> response = adminOrderService.getAdminOrders(condition.resolve());

			assertThat(response.getContent()).isEmpty();
			then(sellerClient).should(never()).getSellerNicknames(any());
		}
	}

	@Test
	@DisplayName("관리자 주문 서비스는 외부 판매자 조회를 위해 클래스 레벨 트랜잭션을 열지 않는다")
	void adminOrderService_hasNoClassLevelTransaction() {
		assertThat(AdminOrderService.class.getAnnotation(Transactional.class)).isNull();
	}

	@Test
	@DisplayName("관리자 주문 조회 전용 서비스가 읽기 전용 트랜잭션을 담당한다")
	void adminOrderQueryService_hasReadOnlyTransaction() {
		Class<?> queryServiceClass = assertDoesNotThrow(() ->
			Class.forName("com.prompthub.order.application.service.admin.AdminOrderQueryService")
		);
		Transactional classTransaction = queryServiceClass.getAnnotation(Transactional.class);

		assertThat(classTransaction).isNotNull();
		assertThat(classTransaction.readOnly()).isTrue();

		Method searchMethod = assertDoesNotThrow(() -> queryServiceClass.getDeclaredMethod(
			"searchAdminOrders",
			AdminOrderSearchCondition.class,
			PageRequest.class
		));
		assertThat(searchMethod).isNotNull();
	}

	@Test
	@DisplayName("이번 달 실제 거래액을 조회한다")
	void getMonthlyTransactionAmount_success() {
		given(adminOrderQueryService.sumMonthlyTransactionAmount(any(), any()))
			.willReturn(25_000L);

		AdminMonthlyTradeAmountResponse response = adminOrderService.getMonthlyTransactionAmount();

		assertThat(response.monthlyTransactionAmount()).isEqualTo(25_000L);
		then(adminOrderQueryService).should().sumMonthlyTransactionAmount(any(), any());
	}

	@Test
	@DisplayName("최근 7일 거래량은 누락된 날짜를 0으로 채우고 합계를 계산한다")
	void getWeeklyTransactions_success() {
		LocalDate today = LocalDate.now();
		given(adminOrderQueryService.findDailyTransactions(any(), any()))
			.willReturn(List.of(new AdminDailyTransactionProjection(today, 2L, 30_000L)));

		AdminWeeklyTransactionResponse response = adminOrderService.getWeeklyTransactions();

		assertThat(response.dailyTransactions()).hasSize(7);
		assertThat(response.period().endDate()).isEqualTo(today);
		assertThat(response.period().startDate()).isEqualTo(today.minusDays(6));
		assertThat(response.totalTransactionCount()).isEqualTo(2L);
		assertThat(response.totalTransactionAmount()).isEqualTo(30_000L);
		assertThat(response.dailyTransactions().getLast().date()).isEqualTo(today);
		assertThat(response.dailyTransactions().getLast().transactionCount()).isEqualTo(2L);
		assertThat(response.dailyTransactions().getLast().transactionAmount()).isEqualTo(30_000L);
		assertThat(response.dailyTransactions().getFirst().transactionCount()).isZero();
		assertThat(response.dailyTransactions().getFirst().transactionAmount()).isZero();
	}

	private AdminOrderListProjection adminOrderProjection(UUID sellerId) {
		return adminOrderProjection(ORDER_ID, sellerId);
	}

	private AdminOrderListProjection adminOrderProjection(UUID orderId, UUID sellerId) {
		return adminOrderProjection(orderId, List.of(new AdminOrderListProjection.SellerSummary(sellerId, 2, TOTAL_AMOUNT)));
	}

	private AdminOrderListProjection adminOrderProjection(
		UUID orderId,
		List<AdminOrderListProjection.SellerSummary> sellers
	) {
		return new AdminOrderListProjection(
			orderId,
			PRODUCT_TITLE_1,
			2,
			TOTAL_AMOUNT,
			OrderStatus.PAID,
			LocalDateTime.of(2026, 6, 24, 10, 0),
			sellers
		);
	}
}
