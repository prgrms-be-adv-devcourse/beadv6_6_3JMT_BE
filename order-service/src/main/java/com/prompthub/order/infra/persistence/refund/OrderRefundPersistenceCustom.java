package com.prompthub.order.infra.persistence.refund;

import com.prompthub.order.domain.model.OrderRefund;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

public interface OrderRefundPersistenceCustom {

	@Transactional(propagation = Propagation.MANDATORY)
	List<OrderRefund> findDueRequestedForUpdate(LocalDateTime now, int batchSize);
}
