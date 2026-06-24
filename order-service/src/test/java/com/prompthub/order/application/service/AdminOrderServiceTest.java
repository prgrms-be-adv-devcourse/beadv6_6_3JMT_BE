package com.prompthub.order.application.service;

import com.prompthub.order.application.client.SellerClient;
import com.prompthub.order.application.dto.AdminOrderListProjection;
import com.prompthub.order.domain.enums.OrderStatus;
import com.prompthub.order.domain.repository.AdminOrderQueryRepository;
import com.prompthub.order.presentation.dto.request.AdminOrderSearchCondition;
import com.prompthub.order.presentation.dto.response.AdminOrderListResponse;
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

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.prompthub.order.fixture.OrderFixture.ORDER_ID;
import static com.prompthub.order.fixture.OrderFixture.PRODUCT_TITLE_1;
import static com.prompthub.order.fixture.OrderFixture.SELLER_ID_1;
import static com.prompthub.order.fixture.OrderFixture.TOTAL_AMOUNT;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;

@ExtendWith(MockitoExtension.class)
class AdminOrderServiceTest {

	@Mock
	private AdminOrderQueryRepository adminOrderQueryRepository;

	@Mock
	private SellerClient sellerClient;

	@InjectMocks
	private AdminOrderService adminOrderService;

	@Nested
	@DisplayName("관리자 주문 목록 조회")
	class GetAdminOrders {

		@Test
		@DisplayName("ALL 상태는 상태 필터 없이 조회하고 판매자 닉네임을 bulk 매핑한다")
		void getAdminOrders_allStatus_success() {
			AdminOrderSearchCondition condition = new AdminOrderSearchCondition("ALL", 1, 20);
			AdminOrderListProjection projection = adminOrderProjection(SELLER_ID_1);
			given(adminOrderQueryRepository.searchAdminOrders(any(), any()))
				.willReturn(new PageImpl<>(List.of(projection), PageRequest.of(0, 20), 1));
			given(sellerClient.getSellerNicknames(List.of(SELLER_ID_1)))
				.willReturn(Map.of(SELLER_ID_1, "판매자A"));

			Page<AdminOrderListResponse> response = adminOrderService.getAdminOrders(condition.resolve());

			assertThat(response.getContent()).hasSize(1);
			assertThat(response.getContent().getFirst().sellerNickname()).isEqualTo("판매자A");
			assertThat(response.getContent().getFirst().productTitle()).isEqualTo(PRODUCT_TITLE_1);

			ArgumentCaptor<AdminOrderSearchCondition> conditionCaptor = ArgumentCaptor.forClass(AdminOrderSearchCondition.class);
			then(adminOrderQueryRepository).should().searchAdminOrders(conditionCaptor.capture(), any());
			assertThat(conditionCaptor.getValue().resolvedOrderStatus()).isNull();
			then(sellerClient).should().getSellerNicknames(List.of(SELLER_ID_1));
		}

		@Test
		@DisplayName("특정 주문 상태는 해당 상태로 조회한다")
		void getAdminOrders_paidStatus_success() {
			AdminOrderSearchCondition condition = new AdminOrderSearchCondition("PAID", 1, 20);
			given(adminOrderQueryRepository.searchAdminOrders(any(), any()))
				.willReturn(new PageImpl<>(List.of(adminOrderProjection(SELLER_ID_1)), PageRequest.of(0, 20), 1));
			given(sellerClient.getSellerNicknames(anyList()))
				.willReturn(Map.of(SELLER_ID_1, "판매자A"));

			adminOrderService.getAdminOrders(condition.resolve());

			ArgumentCaptor<AdminOrderSearchCondition> conditionCaptor = ArgumentCaptor.forClass(AdminOrderSearchCondition.class);
			then(adminOrderQueryRepository).should().searchAdminOrders(conditionCaptor.capture(), any());
			assertThat(conditionCaptor.getValue().resolvedOrderStatus()).isEqualTo(OrderStatus.PAID);
		}

		@Test
		@DisplayName("판매자 조회 실패 시 알 수 없음으로 대체한다")
		void getAdminOrders_sellerClientFailure_fallbackUnknown() {
			AdminOrderSearchCondition condition = new AdminOrderSearchCondition("ALL", 1, 20);
			given(adminOrderQueryRepository.searchAdminOrders(any(), any()))
				.willReturn(new PageImpl<>(List.of(adminOrderProjection(SELLER_ID_1)), PageRequest.of(0, 20), 1));
			given(sellerClient.getSellerNicknames(anyList()))
				.willThrow(new RuntimeException("user-service unavailable"));

			Page<AdminOrderListResponse> response = adminOrderService.getAdminOrders(condition.resolve());

			assertThat(response.getContent().getFirst().sellerNickname()).isEqualTo("알 수 없음");
		}
	}

	private AdminOrderListProjection adminOrderProjection(UUID sellerId) {
		return new AdminOrderListProjection(
			ORDER_ID,
			sellerId,
			PRODUCT_TITLE_1,
			2,
			TOTAL_AMOUNT,
			OrderStatus.PAID,
			LocalDateTime.of(2026, 6, 24, 10, 0)
		);
	}
}
