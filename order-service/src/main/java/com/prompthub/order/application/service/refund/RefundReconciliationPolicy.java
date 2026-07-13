package com.prompthub.order.application.service.refund;

import com.prompthub.order.domain.model.OrderRefund;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

@Component
public class RefundReconciliationPolicy {

	public Decision decide(OrderRefund refund, LocalDateTime checkedAt) {
		return switch (refund.getReconciliationAttempt()) {
			case 0 -> new Decision(Action.RESCHEDULE, checkedAt.plusMinutes(2));
			case 1 -> new Decision(Action.RESCHEDULE, checkedAt.plusMinutes(5));
			case 2 -> new Decision(Action.RESCHEDULE, checkedAt.plusMinutes(10));
			default -> new Decision(Action.TIMEOUT, null);
		};
	}

	public enum Action {
		RESCHEDULE,
		TIMEOUT
	}

	public record Decision(Action action, LocalDateTime nextCheckAt) {
	}
}
