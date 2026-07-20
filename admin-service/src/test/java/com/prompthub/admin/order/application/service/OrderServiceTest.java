package com.prompthub.admin.order.application.service;

import com.prompthub.admin.order.application.dto.DailyTransactionProjection;
import com.prompthub.admin.order.application.dto.OrderListProjection;
import com.prompthub.admin.order.domain.enums.OrderStatus;
import com.prompthub.admin.order.domain.model.SellerNickname;
import com.prompthub.admin.order.infrastructure.persistence.SellerNicknameRepository;
import com.prompthub.admin.order.presentation.dto.request.OrderSearchCondition;
import com.prompthub.admin.order.presentation.dto.response.MonthlyTradeAmountResponse;
import com.prompthub.admin.order.presentation.dto.response.OrderListResponse;
import com.prompthub.admin.order.presentation.dto.response.WeeklyTransactionResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.InjectMocks;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;

@ExtendWith(MockitoExtension.class)
class OrderServiceTest {

	private static final UUID ORDER_ID = UUID.fromString("00000000-0000-0000-0000-000000000501");
	private static final UUID SELLER_ID_1 = UUID.fromString("00000000-0000-0000-0000-000000000201");
	private static final UUID SELLER_ID_2 = UUID.fromString("00000000-0000-0000-0000-000000000202");
	private static final String PRODUCT_TITLE_1 = "프롬프트 상품 1";

	@Mock
	private OrderQueryService orderQueryService;

	@Mock
	private SellerNicknameRepository sellerNicknameRepository;

	@InjectMocks
	private OrderService orderService;

	@Nested
	@DisplayName("관리자 주문 목록 조회")
	class GetOrders {

		@Test
		@DisplayName("한 주문의 모든 판매자를 한 번의 bulk 조회로 닉네임과 함께 매핑한다")
		void getOrders_mapsAllSellersWithSingleBulkLookup() {
			OrderSearchCondition condition = new OrderSearchCondition("ALL", 1, 20);
			OrderListProjection projection = orderProjection(
				ORDER_ID,
				List.of(
					new OrderListProjection.SellerSummary(SELLER_ID_1, 2, 30_000),
					new OrderListProjection.SellerSummary(SELLER_ID_2, 1, 15_000)
				)
			);
			given(orderQueryService.searchOrders(any(), any()))
				.willReturn(new PageImpl<>(List.of(projection), PageRequest.of(0, 20), 1));
			given(sellerNicknameRepository.findAllById(List.of(SELLER_ID_1, SELLER_ID_2)))
				.willReturn(List.of(nickname(SELLER_ID_1, "판매자A")));

			Page<OrderListResponse> response = orderService.getOrders(condition.resolve());

			assertThat(response.getContent()).hasSize(1);
			assertThat(response.getContent().getFirst().sellers()).containsExactly(
				new OrderListResponse.SellerSummary(SELLER_ID_1, "판매자A", 2, 30_000),
				new OrderListResponse.SellerSummary(SELLER_ID_2, "알 수 없음", 1, 15_000)
			);
		}

		@Test
		@DisplayName("주문 목록이 비어 있으면 판매자 조회를 생략한다")
		void getOrders_emptyOrders_skipsSellerLookup() {
			OrderSearchCondition condition = new OrderSearchCondition("ALL", 1, 20);
			given(orderQueryService.searchOrders(any(), any()))
				.willReturn(new PageImpl<>(List.of(), PageRequest.of(0, 20), 0));

			Page<OrderListResponse> response = orderService.getOrders(condition.resolve());

			assertThat(response.getContent()).isEmpty();
			then(sellerNicknameRepository).should(never()).findAllById(any());
		}
	}

	@Test
	@DisplayName("이번 달 실제 거래액을 조회한다")
	void getMonthlyTransactionAmount_success() {
		given(orderQueryService.sumMonthlyTransactionAmount(any(), any()))
			.willReturn(25_000L);

		MonthlyTradeAmountResponse response = orderService.getMonthlyTransactionAmount();

		assertThat(response.monthlyTransactionAmount()).isEqualTo(25_000L);
	}

	@Test
	@DisplayName("최근 7일 거래량은 누락된 날짜를 0으로 채우고 합계를 계산한다")
	void getWeeklyTransactions_success() {
		LocalDate today = LocalDate.now();
		given(orderQueryService.findDailyTransactions(any(), any()))
			.willReturn(List.of(new DailyTransactionProjection(today, 2L, 30_000L)));

		WeeklyTransactionResponse response = orderService.getWeeklyTransactions();

		assertThat(response.dailyTransactions()).hasSize(7);
		assertThat(response.totalTransactionCount()).isEqualTo(2L);
		assertThat(response.totalTransactionAmount()).isEqualTo(30_000L);
	}

	private SellerNickname nickname(UUID sellerId, String nickname) {
		// SellerNickname 은 protected 기본 생성자만 가진 읽기 전용 엔티티라
		// 다른 패키지의 테스트에서는 리플렉션으로만 인스턴스를 만들 수 있다.
		try {
			var constructor = SellerNickname.class.getDeclaredConstructor();
			constructor.setAccessible(true);
			SellerNickname entity = constructor.newInstance();
			org.springframework.test.util.ReflectionTestUtils.setField(entity, "sellerId", sellerId);
			org.springframework.test.util.ReflectionTestUtils.setField(entity, "nickname", nickname);
			return entity;
		} catch (ReflectiveOperationException exception) {
			throw new IllegalStateException(exception);
		}
	}

	private OrderListProjection orderProjection(UUID orderId, List<OrderListProjection.SellerSummary> sellers) {
		return new OrderListProjection(
			orderId,
			PRODUCT_TITLE_1,
			2,
			45_000,
			OrderStatus.COMPLETED,
			LocalDateTime.of(2026, 6, 24, 10, 0),
			sellers
		);
	}
}
