package com.prompthub.order.infra.persistence.order;

import com.prompthub.order.domain.repository.OrderProductRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.UUID;

@Component
@RequiredArgsConstructor
public class OrderProductAdapter implements OrderProductRepository {

	private final OrderProductPersistence persistence;

	@Override
	public boolean tryMarkDownloaded(UUID orderProductId) {
		return persistence.tryMarkDownloaded(orderProductId) == 1;
	}
}
