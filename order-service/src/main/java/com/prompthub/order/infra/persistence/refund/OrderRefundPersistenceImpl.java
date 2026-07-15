package com.prompthub.order.infra.persistence.refund;

import com.prompthub.order.domain.model.OrderRefund;
import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

@RequiredArgsConstructor
public class OrderRefundPersistenceImpl implements OrderRefundPersistenceCustom {

	private final EntityManager entityManager;

	@Override
	public List<OrderRefund> findDueRequestedForUpdate(LocalDateTime now, int batchSize) {
		Objects.requireNonNull(now, "now must not be null");
		if (batchSize <= 0) {
			throw new IllegalArgumentException("batchSize must be positive");
		}

		@SuppressWarnings("unchecked")
		List<UUID> refundIds = entityManager.createNativeQuery("""
			select id
			from order_refund
			where status = 'REQUESTED'
			  and next_check_at is not null
			  and next_check_at <= :now
			order by next_check_at, id
			limit :batchSize
			for update skip locked
			""")
			.setParameter("now", now)
			.setParameter("batchSize", batchSize)
			.getResultList();

		if (refundIds.isEmpty()) {
			return List.of();
		}

		return entityManager.createQuery("""
			select r
			from OrderRefund r
			join fetch r.product
			where r.id in :refundIds
			order by r.nextCheckAt, r.id
			""", OrderRefund.class)
			.setParameter("refundIds", refundIds)
			.getResultList();
	}
}
