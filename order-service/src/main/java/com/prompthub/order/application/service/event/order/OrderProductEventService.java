package com.prompthub.order.application.service.event.order;

import com.prompthub.order.application.event.product.ProductDeletedEvent;
import com.prompthub.order.application.event.product.ProductPriceChangedEvent;
import com.prompthub.order.application.event.product.ProductStoppedEvent;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Slf4j
@Service
public class OrderProductEventService {

	public void handleProductStopped(ProductStoppedEvent event) {
		log.info("상품 판매 중지 이벤트를 수신했습니다. productId={}, occurredAt={}",
			event.productId(), event.occurredAt());
	}

	public void handleProductDeleted(ProductDeletedEvent event) {
		log.info("상품 삭제 이벤트를 수신했습니다. productId={}, occurredAt={}",
			event.productId(), event.occurredAt());
	}

	public void handleProductPriceChanged(ProductPriceChangedEvent event) {
		log.info("상품 가격 변경 이벤트를 수신했습니다. productId={}, previousPrice={}, changedPrice={}, occurredAt={}",
			event.productId(), event.previousPrice(), event.changedPrice(), event.occurredAt());
	}
}
