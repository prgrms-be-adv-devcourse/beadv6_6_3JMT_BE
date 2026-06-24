package com.prompthub.order.application.service;

import com.prompthub.order.application.client.SellerClient;
import com.prompthub.order.application.dto.AdminOrderListProjection;
import com.prompthub.order.application.usecase.AdminOrderUseCase;
import com.prompthub.order.domain.repository.AdminOrderQueryRepository;
import com.prompthub.order.presentation.dto.request.AdminOrderSearchCondition;
import com.prompthub.order.presentation.dto.response.AdminOrderListResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@Slf4j
@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class AdminOrderService implements AdminOrderUseCase {

	private static final String UNKNOWN_SELLER_NICKNAME = "알 수 없음";

	private final AdminOrderQueryRepository adminOrderQueryRepository;
	private final SellerClient sellerClient;

	@Override
	public Page<AdminOrderListResponse> getAdminOrders(AdminOrderSearchCondition condition) {
		PageRequest pageable = PageRequest.of(
			condition.page() - 1,
			condition.size(),
			Sort.by(Sort.Direction.DESC, "createdAt")
		);
		Page<AdminOrderListProjection> orders = adminOrderQueryRepository.searchAdminOrders(condition, pageable);
		Map<UUID, String> sellerNicknames = getSellerNicknames(orders.getContent());

		return orders.map(order -> toAdminOrderListResponse(order, sellerNicknames));
	}

	private Map<UUID, String> getSellerNicknames(List<AdminOrderListProjection> orders) {
		List<UUID> sellerIds = orders.stream()
			.map(AdminOrderListProjection::sellerId)
			.distinct()
			.toList();

		if (sellerIds.isEmpty()) {
			return Map.of();
		}

		try {
			return sellerClient.getSellerNicknames(sellerIds);
		} catch (RuntimeException exception) {
			log.warn("판매자 닉네임 bulk 조회에 실패했습니다. sellerIds={}", sellerIds, exception);
			return Map.of();
		}
	}

	private AdminOrderListResponse toAdminOrderListResponse(
		AdminOrderListProjection projection,
		Map<UUID, String> sellerNicknames
	) {
		return new AdminOrderListResponse(
			projection.orderId(),
			sellerNicknames.getOrDefault(projection.sellerId(), UNKNOWN_SELLER_NICKNAME),
			projection.productTitle(),
			projection.totalOrderCount(),
			projection.totalOrderAmount(),
			projection.orderStatus(),
			projection.createdAt()
		);
	}
}
