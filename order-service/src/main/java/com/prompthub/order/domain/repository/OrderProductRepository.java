package com.prompthub.order.domain.repository;

import java.util.UUID;

public interface OrderProductRepository {

	boolean tryMarkDownloaded(UUID orderProductId);
}
