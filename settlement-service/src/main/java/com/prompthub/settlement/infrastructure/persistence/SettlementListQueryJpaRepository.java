package com.prompthub.settlement.infrastructure.persistence;

import com.prompthub.settlement.domain.model.Settlement;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.repository.Repository;

/**
 * 정산 목록 조회 전용 Spring Data 리포지토리.
 *
 * <p>조회 전용이므로 {@link Repository} 기반에 {@link JpaSpecificationExecutor} 만 더해
 * 저장 API를 노출하지 않는다(CQRS-lite). 동적 상태 필터는 Specification 으로 구성한다.
 */
public interface SettlementListQueryJpaRepository
        extends Repository<Settlement, UUID>, JpaSpecificationExecutor<Settlement> {
}
