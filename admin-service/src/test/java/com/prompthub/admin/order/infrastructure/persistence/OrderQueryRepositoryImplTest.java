package com.prompthub.admin.order.infrastructure.persistence;

import com.prompthub.admin.order.application.dto.DailyTransactionProjection;
import com.prompthub.admin.order.application.dto.OrderListProjection;
import com.prompthub.admin.order.domain.enums.OrderStatus;
import com.prompthub.admin.order.infrastructure.persistence.config.QuerydslConfig;
import com.prompthub.admin.order.presentation.dto.request.OrderSearchCondition;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.jdbc.Sql;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@Import({QuerydslConfig.class, OrderQueryRepositoryImpl.class})
@ActiveProfiles("test")
@Sql("/sql/orders.sql")
class OrderQueryRepositoryImplTest {

	@Autowired
	private OrderQueryRepositoryImpl repository;

	@Test
	void COMPLETED_상태로_필터링하면_해당_주문의_모든_상품을_반환한다() {
		Page<OrderListProjection> result = repository.searchOrders(
			new OrderSearchCondition("COMPLETED", 1, 20).resolve(),
			PageRequest.of(0, 20)
		);

		assertThat(result.getTotalElements()).isEqualTo(1);
		OrderListProjection projection = result.getContent().getFirst();
		assertThat(projection.orderId()).isEqualTo(UUID.fromString("aaaaaaaa-0000-0000-0000-000000000001"));
		assertThat(projection.orderNumber()).isEqualTo("ORD-20260610-0001");
		assertThat(projection.buyerId())
			.isEqualTo(UUID.fromString("dddddddd-0000-0000-0000-000000000001"));
		assertThat(projection.totalOrderAmount()).isEqualTo(30000);
		assertThat(projection.orderStatus()).isEqualTo(OrderStatus.COMPLETED);
		assertThat(projection.orderProducts()).containsExactly(
			new OrderListProjection.OrderProductSummary(
				UUID.fromString("cccccccc-0000-0000-0000-000000000001"), "프롬프트 상품 1", 10_000, "PAID"
			),
			new OrderListProjection.OrderProductSummary(
				UUID.fromString("cccccccc-0000-0000-0000-000000000002"), "프롬프트 상품 2", 20_000, "PAID"
			)
		);
	}

	@Test
	void 월간_실거래액은_완료금액에서_환불금액을_뺀다() {
		long result = repository.sumMonthlyTransactionAmount(
			LocalDateTime.of(2026, 6, 1, 0, 0),
			LocalDateTime.of(2026, 7, 1, 0, 0)
		);

		// completedAt 기준 6월 합계(30000+10000) - 환불된 상품 금액(10000) = 30000
		assertThat(result).isEqualTo(30000L);
	}

	@Test
	void 일별_거래는_완료일과_환불일을_기준으로_집계한다() {
		List<DailyTransactionProjection> result = repository.findDailyTransactions(
			LocalDateTime.of(2026, 6, 10, 0, 0),
			LocalDateTime.of(2026, 6, 13, 0, 0)
		);

		assertThat(result).hasSize(3);
		assertThat(result.get(0).date()).isEqualTo(java.time.LocalDate.of(2026, 6, 10));
		assertThat(result.get(0).transactionCount()).isEqualTo(1L);
		assertThat(result.get(0).transactionAmount()).isEqualTo(30000L);
		assertThat(result.get(1).date()).isEqualTo(java.time.LocalDate.of(2026, 6, 11));
		assertThat(result.get(1).transactionCount()).isEqualTo(1L);
		assertThat(result.get(1).transactionAmount()).isEqualTo(10000L);
		assertThat(result.get(2).date()).isEqualTo(java.time.LocalDate.of(2026, 6, 12));
		assertThat(result.get(2).transactionCount()).isZero();
		assertThat(result.get(2).transactionAmount()).isEqualTo(-10000L);
	}

	@Test
	void 생성일시가_같은_주문도_ID_내림차순으로_페이지가_겹치지_않는다() {
		Page<OrderListProjection> firstPage = repository.searchOrders(
			new OrderSearchCondition("ALL", 1, 1).resolve(), PageRequest.of(0, 1));
		Page<OrderListProjection> secondPage = repository.searchOrders(
			new OrderSearchCondition("ALL", 2, 1).resolve(), PageRequest.of(1, 1));

		assertThat(firstPage.getContent()).extracting(OrderListProjection::orderId)
			.containsExactly(UUID.fromString("aaaaaaaa-0000-0000-0000-000000000005"));
		assertThat(secondPage.getContent()).extracting(OrderListProjection::orderId)
			.containsExactly(UUID.fromString("aaaaaaaa-0000-0000-0000-000000000004"));
	}
}
