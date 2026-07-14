package com.prompthub.order.application.service.refund;

import com.prompthub.order.domain.model.OrderRefund;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

@Component
public class RefundReconciliationPolicy {

	public Decision decide(OrderRefund refund, LocalDateTime checkedAt) {
		return switch (refund.getReconciliationAttempt()) {
			case 0 -> Decision.reschedule(1, refund.getRequestedAt().plusMinutes(5));
			case 1 -> Decision.reschedule(2, refund.getRequestedAt().plusMinutes(10));
			case 2 -> Decision.reschedule(3, refund.getRequestedAt().plusMinutes(20));
			case 3 -> Decision.markUnknown(4, checkedAt.plusMinutes(30));
			case 4 -> Decision.reschedule(5, checkedAt.plusHours(1));
			case 5 -> Decision.reschedule(6, checkedAt.plusHours(3));
			default -> Decision.manualReview();
		};
	}

	public enum Action {
		RESCHEDULE,
		MARK_UNKNOWN,
		MANUAL_REVIEW
	}

	public record Decision(Action action, int attempt, LocalDateTime nextCheckAt) {
		static Decision reschedule(int attempt, LocalDateTime nextCheckAt) {
			return new Decision(Action.RESCHEDULE, attempt, nextCheckAt);
		}

		static Decision markUnknown(int attempt, LocalDateTime nextCheckAt) {
			return new Decision(Action.MARK_UNKNOWN, attempt, nextCheckAt);
		}

		static Decision manualReview() {
			return new Decision(Action.MANUAL_REVIEW, 6, null);
		}
	}
}
