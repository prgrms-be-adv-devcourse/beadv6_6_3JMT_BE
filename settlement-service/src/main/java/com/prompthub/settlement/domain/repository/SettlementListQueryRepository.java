package com.prompthub.settlement.domain.repository;

import com.prompthub.settlement.domain.model.Settlement;
import com.prompthub.settlement.domain.model.enums.SettlementDisplayStatus;
import java.util.List;

/**
 * 정산 목록(페이징) 조회 아웃바운드 포트.
 *
 * <p>조회 전용으로, 커맨드/배치용 {@code SettlementRepository} 와 분리한다(CQRS-lite).
 * 표시 상태 필터의 술어 변환은 {@code SettlementDisplayStatus.from} 을 단일 출처로 어댑터에서 구성한다.
 *
 * @see com.prompthub.settlement.domain.model.enums.SettlementDisplayStatus
 */
public interface SettlementListQueryRepository {

    /**
     * 표시 상태로 필터링한 정산을 페이징 조회한다.
     *
     * @param status 표시 상태 필터. {@code null} 이면 전체.
     * @param page   0-base 페이지 번호.
     * @param size   페이지 크기.
     */
    SettlementPage findPage(SettlementDisplayStatus status, int page, int size);

    record SettlementPage(List<Settlement> content, long totalElements) {
    }
}
