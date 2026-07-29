package com.prompthub.admin.settlement.repository;

import com.prompthub.admin.settlement.entity.Settlement;
import com.prompthub.admin.settlement.entity.SettlementDelivery;
import com.prompthub.admin.settlement.entity.enums.SettlementDeliveryStatus;
import com.prompthub.admin.settlement.entity.enums.SettlementDisplayStatus;
import com.prompthub.admin.settlement.repository.SettlementStatusAggregate;
import com.prompthub.admin.settlement.repository.SettlementWeeklyStatusCount;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.data.repository.Repository;

public interface SettlementQueryJpaRepository
	extends Repository<Settlement, UUID> {

	@Query("""
			select new com.prompthub.admin.settlement.repository.SettlementStatusAggregate(
				s.status, sum(s.settlementTotalAmount), count(s))
			from Settlement s
			group by s.status
			""")
	List<SettlementStatusAggregate> aggregateByStatus();

	@Query("""
			select new com.prompthub.admin.settlement.repository.SettlementStatusAggregate(
				s.status, sum(s.settlementTotalAmount), count(s))
			from Settlement s
			where s.periodStart >= :periodStart
			  and s.periodStart < :periodEnd
			group by s.status
			""")
	List<SettlementStatusAggregate> aggregateByStatusBetween(
		@Param("periodStart") LocalDate periodStart,
		@Param("periodEnd") LocalDate periodEnd);

	@Query("""
			select s from Settlement s
			where s.sellerId = :sellerId
			  and s.periodStart >= :periodStart
			  and s.periodStart < :periodEnd
			order by s.periodStart asc, s.sellerSettlementId asc
			""")
	List<Settlement> findWeeklySettlements(
		@Param("sellerId") UUID sellerId,
		@Param("periodStart") LocalDate periodStart,
		@Param("periodEnd") LocalDate periodEnd);

	@Query("""
			select s from Settlement s
			where (:status is null or s.status = :status)
			  and (:periodStart is null or s.periodStart >= :periodStart)
			  and (:periodEnd is null or s.periodStart < :periodEnd)
			order by s.periodStart desc, s.sellerSettlementId asc
			""")
	Page<Settlement> findWeeklyPage(
		@Param("status") SettlementDisplayStatus status,
		@Param("periodStart") LocalDate periodStart,
		@Param("periodEnd") LocalDate periodEnd,
		Pageable pageable);

	@Query("""
			select new com.prompthub.admin.settlement.repository.SettlementWeeklyStatusCount(
				s.status, count(s))
			from Settlement s
			where (:periodStart is null or s.periodStart >= :periodStart)
			  and (:periodEnd is null or s.periodStart < :periodEnd)
			group by s.status
			""")
	List<SettlementWeeklyStatusCount> countWeeklyStatuses(
		@Param("periodStart") LocalDate periodStart,
		@Param("periodEnd") LocalDate periodEnd);

	@Query("""
			select delivery
			from SettlementDelivery delivery
			where (:status is null or delivery.status = :status)
			  and (:problemOnly = false
			       or delivery.status in (
			           com.prompthub.admin.settlement.entity.enums.SettlementDeliveryStatus.DELIVERY_FAILED,
			           com.prompthub.admin.settlement.entity.enums.SettlementDeliveryStatus.MISMATCH))
			  and (:identifier is null
			       or delivery.settlementId = :identifier
			       or delivery.deliveryRequestId = :identifier)
			order by
			    case when delivery.lastAttemptAt is null then 1 else 0 end,
			    delivery.lastAttemptAt desc,
			    delivery.createdAt desc,
			    delivery.settlementDeliveryId desc
			""")
	Page<SettlementDelivery> findDeliveryPage(
		@Param("status") SettlementDeliveryStatus status,
		@Param("problemOnly") boolean problemOnly,
		@Param("identifier") UUID identifier,
		Pageable pageable);

	@Query("""
			select new com.prompthub.admin.settlement.repository.SettlementDeliveryStatusCount(
			    delivery.status, count(delivery))
			from SettlementDelivery delivery
			group by delivery.status
			""")
	List<SettlementDeliveryStatusCount> countDeliveryByStatus();

	@Query("""
			select delivery
			from SettlementDelivery delivery
			where delivery.settlementDeliveryId = :settlementDeliveryId
			""")
	Optional<SettlementDelivery> findDeliveryById(
		@Param("settlementDeliveryId") UUID settlementDeliveryId);
}
