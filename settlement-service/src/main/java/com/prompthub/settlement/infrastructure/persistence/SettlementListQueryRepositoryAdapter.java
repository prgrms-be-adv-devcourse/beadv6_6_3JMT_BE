package com.prompthub.settlement.infrastructure.persistence;

import com.prompthub.settlement.domain.model.Settlement;
import com.prompthub.settlement.domain.model.enums.PayoutStatus;
import com.prompthub.settlement.domain.model.enums.SettlementDisplayStatus;
import com.prompthub.settlement.domain.model.enums.SettlementStatus;
import com.prompthub.settlement.domain.repository.SettlementListQueryRepository;
import jakarta.persistence.criteria.Predicate;
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class SettlementListQueryRepositoryAdapter implements SettlementListQueryRepository {

    private final SettlementListQueryJpaRepository jpaRepository;

    @Override
    public SettlementPage findPage(SettlementDisplayStatus status, int page, int size) {
        Pageable pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "calculatedAt"));
        Page<Settlement> result = jpaRepository.findAll(displayStatusSpec(status), pageable);
        return new SettlementPage(result.getContent(), result.getTotalElements());
    }

    /**
     * 표시 상태에 매핑되는 {@code (settlementStatus, payoutStatus)} 조합만 OR 결합한 술어를 만든다.
     * 조합은 {@link SettlementDisplayStatus#from} 을 단일 출처로 역산하므로 매핑이 어긋날 수 없다.
     * {@code status} 가 null 이면 전체(필터 없음).
     */
    private static Specification<Settlement> displayStatusSpec(SettlementDisplayStatus status) {
        if (status == null) {
            return null;
        }
        return (root, query, cb) -> {
            List<Predicate> combos = new ArrayList<>();
            for (SettlementStatus settlementStatus : SettlementStatus.values()) {
                for (PayoutStatus payoutStatus : PayoutStatus.values()) {
                    if (SettlementDisplayStatus.from(settlementStatus, payoutStatus) == status) {
                        combos.add(cb.and(
                                cb.equal(root.get("settlementStatus"), settlementStatus),
                                cb.equal(root.get("payoutStatus"), payoutStatus)));
                    }
                }
            }
            return cb.or(combos.toArray(new Predicate[0]));
        };
    }
}
